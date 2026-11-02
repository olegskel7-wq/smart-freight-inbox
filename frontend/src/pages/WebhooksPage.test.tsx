import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WebhookDelivery, WebhookSubscription, WebhooksResponse } from '../types.ts'
import { formatDateTime } from '../util/format.ts'
import WebhooksPage from './WebhooksPage.tsx'

// Pins what the webhooks page shows and sends today — the subscriptions table and its status
// badges, the add form (all events / picked types, optional fields, the reset after a create), the
// signing secret shown once, Test / Enable / Disable / Delete and their messages, and the per-row
// delivery log — as the safety net for splitting the page into components (docs/refactoring.md,
// Stage 1.3). pageLoading.test.tsx covers Loading… and the load error.

const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))

type Call = { method: string; url: string; body: string | null }

let calls: Call[]
let routes: Record<string, () => Response | Promise<Response>>

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const failure = (message: string) => () => json({ error: 'Bad Request', message }, 400)

// A response the test releases by hand, to look at the page while the request is in flight.
function held(response: () => Response) {
  let release = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { route: () => gate.then(response), release: () => act(async () => release()) }
}

beforeEach(() => {
  oidc.auth = { user: { access_token: 'test-token', profile: { sub: 'me' } }, signinRedirect: vi.fn() }
  calls = []
  routes = { 'GET /api/webhooks': () => json(SNAPSHOT) }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? init.body : null })
    const route = routes[`${method} ${url}`]
    return route ? route() : json({ message: `unexpected ${method} ${url}` }, 404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const AT = '2026-10-01T09:30:00Z'
const LATER = '2026-10-01T09:31:05Z'

const sub = (id: number, url: string, extra: Partial<WebhookSubscription> = {}): WebhookSubscription => ({
  id, url, eventTypes: ['*'], description: null, enabled: true, disabledReason: null,
  createdAt: AT, updatedAt: AT, ...extra,
})
const ON = sub(1, 'https://crm.example.com/hook', { description: 'HubSpot via Zapier' })
const AUTO_OFF = sub(2, 'https://dead.example.com/hook', {
  eventTypes: ['ticket.created', 'reply.sent'], enabled: false,
  disabledReason: 'Disabled after 3 consecutive failed deliveries',
})
const OFF = sub(3, 'https://off.example.com/hook', { eventTypes: ['quote.sent'], enabled: false })
const EVENT_TYPES = ['ticket.created', 'reply.sent', 'quote.sent', 'booking.confirmed']

const snapshot = (subscriptions: WebhookSubscription[], secretConfigured = true): WebhooksResponse =>
  ({ subscriptions, availableEventTypes: EVENT_TYPES, secretConfigured })
const SNAPSHOT = snapshot([ON, AUTO_OFF, OFF])

const delivery = (id: number, status: string, extra: Partial<WebhookDelivery> = {}): WebhookDelivery => ({
  id, eventType: 'ticket.created', status, attempts: 1, responseStatus: 200, lastError: null,
  createdAt: AT, nextAttemptAt: AT, deliveredAt: LATER, ...extra,
})

const gets = (url: string) => calls.filter((c) => c.method === 'GET' && c.url === url)
const mutations = () => calls.filter((c) => c.method !== 'GET')
const bodyOf = (call: Call) => JSON.parse(call.body ?? 'null') as unknown

const rowOf = (url: string) => screen.getByText(url).closest('tr') as HTMLElement
const inRow = (url: string) => within(rowOf(url))
const alertWith = (text: string) => screen.getByText(text).closest('.alert') as HTMLElement
const closeButton = (alert: HTMLElement) => alert.querySelector('.btn-close') as HTMLElement
const urlInput = () => screen.getByPlaceholderText('https://hooks.zapier.com/hooks/catch/…')
const descriptionInput = () => screen.getByPlaceholderText('e.g. HubSpot via Zapier')
const secretInput = () => screen.getByPlaceholderText('Leave blank to generate a strong secret')
const createButton = () => screen.getByRole('button', { name: 'Create webhook' })

async function renderPage() {
  render(<WebhooksPage />)
  await screen.findByText(ON.url)
}

describe('WebhooksPage — subscriptions table', () => {
  it('lists every subscription with its description, events and status', async () => {
    await renderPage()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Webhooks')
    expect(screen.getByText('Subscriptions (3)')).toBeInTheDocument()

    expect(inRow(ON.url).getByText('HubSpot via Zapier')).toHaveClass('text-muted', 'small')
    expect(inRow(ON.url).getByText('all')).toHaveClass('badge', 'text-bg-light')
    expect(inRow(ON.url).getByText('Enabled')).toHaveClass('badge', 'text-bg-success')
    expect(inRow(ON.url).getByRole('button', { name: 'Disable' })).toBeInTheDocument()

    expect(inRow(AUTO_OFF.url).getByText('ticket.created')).toHaveClass('badge')
    expect(inRow(AUTO_OFF.url).getByText('reply.sent')).toHaveClass('badge')
    expect(inRow(AUTO_OFF.url).getByText('Auto-disabled')).toHaveClass('badge', 'text-bg-warning')
    expect(inRow(AUTO_OFF.url).getByText(AUTO_OFF.disabledReason as string)).toHaveClass('text-muted')
    expect(inRow(AUTO_OFF.url).getByRole('button', { name: 'Enable' })).toBeInTheDocument()

    expect(inRow(OFF.url).getByText('quote.sent')).toHaveClass('badge')
    expect(inRow(OFF.url).getByText('Disabled')).toHaveClass('badge', 'text-bg-secondary')
    expect(inRow(OFF.url).queryByText('Auto-disabled')).not.toBeInTheDocument()

    for (const url of [ON.url, AUTO_OFF.url, OFF.url]) {
      expect(inRow(url).getAllByRole('button').map((b) => b.textContent))
        .toEqual(['Test', 'Log', url === ON.url ? 'Disable' : 'Enable', '✕'])
    }
    expect(screen.getByText('How webhooks work')).toBeInTheDocument()
  })

  it('says there are no webhooks yet when the list is empty', async () => {
    routes['GET /api/webhooks'] = () => json(snapshot([]))

    render(<WebhooksPage />)

    expect(await screen.findByText('No webhooks yet. Add one above to push events out.')).toBeInTheDocument()
    expect(screen.getByText('Subscriptions (0)')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('warns and disables Create when no signing key is configured', async () => {
    routes['GET /api/webhooks'] = () => json(snapshot([ON], false))

    await renderPage()

    expect(screen.getByText('Signing key not configured — set MAILBOX_ENCRYPTION_KEY to create webhooks'))
      .toHaveClass('badge', 'text-bg-warning')
    expect(createButton()).toBeDisabled()
  })

  it('shows no warning and an enabled Create when the key is configured', async () => {
    await renderPage()

    expect(screen.queryByText(/Signing key not configured/)).not.toBeInTheDocument()
    expect(createButton()).toBeEnabled()
  })
})

describe('WebhooksPage — add webhook', () => {
  const CREATED = sub(4, 'https://new.example.com/hook')

  it('creates an all-events webhook with blank optional fields sent as null', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = () => json({ secret: 'whsec_abc123', snapshot: snapshot([ON, CREATED]) })
    await renderPage()

    expect(screen.getByLabelText('All events')).toBeChecked()
    expect(screen.queryByLabelText('ticket.created')).not.toBeInTheDocument()
    await user.type(urlInput(), CREATED.url)
    await user.click(createButton())

    expect(await screen.findByText(CREATED.url)).toBeInTheDocument()
    expect(mutations()).toHaveLength(1)
    expect(bodyOf(mutations()[0])).toEqual({ url: CREATED.url, eventTypes: ['*'], description: null, secret: null })
    expect(screen.queryByText(AUTO_OFF.url)).not.toBeInTheDocument()
    expect(gets('/api/webhooks')).toHaveLength(1)
  })

  it('sends the picked event types in the order they were ticked, with description and secret', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = () => json({ secret: 'own-secret', snapshot: snapshot([ON, CREATED]) })
    await renderPage()

    await user.click(screen.getByLabelText('All events'))
    expect(EVENT_TYPES.map((t) => screen.getByLabelText(t))).toHaveLength(4)
    await user.click(screen.getByLabelText('quote.sent'))
    await user.click(screen.getByLabelText('ticket.created'))
    await user.click(screen.getByLabelText('reply.sent'))
    await user.click(screen.getByLabelText('quote.sent'))
    expect(screen.getByLabelText('ticket.created')).toBeChecked()
    expect(screen.getByLabelText('quote.sent')).not.toBeChecked()
    await user.type(urlInput(), CREATED.url)
    await user.type(descriptionInput(), 'Pipedrive')
    await user.type(secretInput(), 'own-secret')
    await user.click(createButton())

    await screen.findByText(CREATED.url)
    expect(bodyOf(mutations()[0])).toEqual({
      url: CREATED.url, eventTypes: ['ticket.created', 'reply.sent'], description: 'Pipedrive', secret: 'own-secret',
    })
  })

  it('keeps the picked types while All events is toggled back and forth, and ignores them when it is on', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = () => json({ secret: 's', snapshot: snapshot([ON, CREATED]) })
    await renderPage()

    await user.click(screen.getByLabelText('All events'))
    await user.click(screen.getByLabelText('booking.confirmed'))
    await user.click(screen.getByLabelText('All events'))
    expect(screen.queryByLabelText('booking.confirmed')).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('All events'))
    expect(screen.getByLabelText('booking.confirmed')).toBeChecked()
    await user.click(screen.getByLabelText('All events'))
    await user.type(urlInput(), CREATED.url)
    await user.click(createButton())

    await screen.findByText(CREATED.url)
    expect(bodyOf(mutations()[0])).toMatchObject({ eventTypes: ['*'] })
  })

  it('shows the signing secret once with a Copy button, and resets the form', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = () => json({ secret: 'whsec_abc123', snapshot: snapshot([ON, CREATED]) })
    await renderPage()

    await user.click(screen.getByLabelText('All events'))
    await user.click(screen.getByLabelText('reply.sent'))
    await user.type(urlInput(), CREATED.url)
    await user.type(descriptionInput(), 'Pipedrive')
    await user.type(secretInput(), 'own-secret')
    await user.click(createButton())

    const secret = await screen.findByText('whsec_abc123')
    expect(secret.tagName).toBe('CODE')
    expect(alertWith('Signing secret (shown once)')).toHaveClass('alert-info')
    expect(screen.getByText('Webhook created. Copy the signing secret now — it is shown only once.'))
      .toHaveClass('alert', 'alert-success')
    expect(urlInput()).toHaveValue('')
    expect(descriptionInput()).toHaveValue('')
    expect(secretInput()).toHaveValue('')
    expect(screen.getByLabelText('All events')).toBeChecked()
    await user.click(screen.getByLabelText('All events'))
    expect(screen.getByLabelText('reply.sent')).not.toBeChecked()

    await user.click(within(alertWith('Signing secret (shown once)')).getByRole('button', { name: 'Copy' }))
    expect(await navigator.clipboard.readText()).toBe('whsec_abc123')

    await user.click(closeButton(alertWith('Signing secret (shown once)')))
    expect(screen.queryByText('whsec_abc123')).not.toBeInTheDocument()
    expect(screen.getByText(/Webhook created\./)).toBeInTheDocument()
  })

  it('keeps the form and shows the server message when the create is rejected', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = failure('Webhook URL must use HTTPS')
    await renderPage()

    await user.type(urlInput(), 'https://10.0.0.1/hook')
    await user.type(descriptionInput(), 'Internal')
    await user.click(createButton())

    expect(await screen.findByText('Webhook URL must use HTTPS')).toHaveClass('alert', 'alert-danger')
    expect(urlInput()).toHaveValue('https://10.0.0.1/hook')
    expect(descriptionInput()).toHaveValue('Internal')
    expect(screen.getByText('Subscriptions (3)')).toBeInTheDocument()
  })

  it('hides the previous secret and message as soon as another create starts', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = () => json({ secret: 'whsec_first', snapshot: snapshot([ON, CREATED]) })
    await renderPage()
    await user.type(urlInput(), CREATED.url)
    await user.click(createButton())
    await screen.findByText('whsec_first')

    const second = held(failure('Duplicate endpoint'))
    routes['POST /api/webhooks'] = second.route
    await user.type(urlInput(), CREATED.url)
    await user.click(createButton())

    expect(screen.queryByText('whsec_first')).not.toBeInTheDocument()
    expect(screen.queryByText(/Webhook created\./)).not.toBeInTheDocument()
    await second.release()
    expect(await screen.findByText('Duplicate endpoint')).toHaveClass('alert-danger')
  })
})

describe('WebhooksPage — row actions', () => {
  it('disables an enabled webhook by re-posting it with enabled false and no secret', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks/1'] = () => json(snapshot([{ ...ON, enabled: false }, AUTO_OFF, OFF]))
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Disable' }))

    expect(await screen.findByText('Webhook disabled.')).toHaveClass('alert', 'alert-success')
    expect(bodyOf(mutations()[0])).toEqual({
      url: ON.url, eventTypes: ['*'], description: 'HubSpot via Zapier', enabled: false, secret: null,
    })
    expect(inRow(ON.url).getByText('Disabled')).toBeInTheDocument()
    expect(inRow(ON.url).getByRole('button', { name: 'Enable' })).toBeInTheDocument()
    expect(gets('/api/webhooks')).toHaveLength(1)
  })

  it('enables an auto-disabled webhook', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks/2'] = () =>
      json(snapshot([ON, { ...AUTO_OFF, enabled: true, disabledReason: null }, OFF]))
    await renderPage()

    await user.click(inRow(AUTO_OFF.url).getByRole('button', { name: 'Enable' }))

    expect(await screen.findByText('Webhook enabled.')).toHaveClass('alert-success')
    expect(bodyOf(mutations()[0])).toEqual({
      url: AUTO_OFF.url, eventTypes: ['ticket.created', 'reply.sent'], description: null, enabled: true, secret: null,
    })
    expect(inRow(AUTO_OFF.url).getByText('Enabled')).toBeInTheDocument()
  })

  it('queues a test event with an empty body', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks/3/test'] = () => json(SNAPSHOT)
    await renderPage()

    await user.click(inRow(OFF.url).getByRole('button', { name: 'Test' }))

    expect(await screen.findByText('Test event queued.')).toHaveClass('alert-success')
    expect(mutations()).toEqual([{ method: 'POST', url: '/api/webhooks/3/test', body: '{}' }])
  })

  it('deletes after confirmation and renders the snapshot it returns', async () => {
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    routes['DELETE /api/webhooks/2'] = () => json(snapshot([ON, OFF]))
    await renderPage()

    await user.click(inRow(AUTO_OFF.url).getByRole('button', { name: '✕' }))

    expect(await screen.findByText('Webhook deleted.')).toHaveClass('alert-success')
    expect(confirm).toHaveBeenCalledWith('Delete this webhook and its delivery history?')
    expect(mutations()).toEqual([{ method: 'DELETE', url: '/api/webhooks/2', body: null }])
    expect(screen.queryByText(AUTO_OFF.url)).not.toBeInTheDocument()
    expect(screen.getByText('Subscriptions (2)')).toBeInTheDocument()
  })

  it('sends nothing when the delete is not confirmed', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: '✕' }))

    expect(mutations()).toHaveLength(0)
    expect(screen.getByText(ON.url)).toBeInTheDocument()
  })

  it.each([
    { action: 'Test', route: 'POST /api/webhooks/1/test' },
    { action: 'Disable', route: 'POST /api/webhooks/1' },
    { action: '✕', route: 'DELETE /api/webhooks/1' },
  ])('$action: keeps the table and shows the server message on failure', async ({ action, route }) => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    routes[route] = failure('Subscription not found')
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: action }))

    expect(await screen.findByText('Subscription not found')).toHaveClass('alert', 'alert-danger')
    expect(inRow(ON.url).getByText('Enabled')).toBeInTheDocument()
    expect(screen.getByText('Subscriptions (3)')).toBeInTheDocument()
  })

  it('clears the last message when the next action starts, and the close button dismisses it', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks/1/test'] = () => json(SNAPSHOT)
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Test' }))
    await screen.findByText('Test event queued.')

    const next = held(() => json(SNAPSHOT))
    routes['POST /api/webhooks/3/test'] = next.route
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Test' }))
    expect(screen.queryByText('Test event queued.')).not.toBeInTheDocument()
    await next.release()

    await user.click(closeButton(await screen.findByText('Test event queued.')))
    expect(screen.queryByText('Test event queued.')).not.toBeInTheDocument()
  })
})

describe('WebhooksPage — delivery log', () => {
  const logRowOf = (eventType: string) => screen.getByText(eventType, { selector: 'code' }).closest('tr') as HTMLTableRowElement

  it('loads the log under the row on Log and renders every delivery', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([
      delivery(10, 'DELIVERED', { eventType: 'reply.sent' }),
      delivery(11, 'DEAD', { eventType: 'quote.sent', attempts: 8, responseStatus: 502, lastError: 'HTTP 502', deliveredAt: null }),
      delivery(12, 'PENDING', { eventType: 'booking.confirmed', attempts: 2, responseStatus: null, deliveredAt: null }),
    ])
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))

    expect(await screen.findByText('reply.sent', { selector: 'code' })).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(1)
    expect(inRow(ON.url).getByRole('button', { name: 'Hide log' })).toBeInTheDocument()
    expect(inRow(OFF.url).getByRole('button', { name: 'Log' })).toBeInTheDocument()
    // The log row sits right under its subscription.
    expect(rowOf(ON.url).nextElementSibling?.contains(logRowOf('reply.sent'))).toBe(true)

    const cells = (eventType: string) => Array.from(logRowOf(eventType).cells).map((c) => c.textContent)
    const at = formatDateTime(AT)
    expect(cells('reply.sent')).toEqual(['reply.sent', 'DELIVERED', '1', '200', '', at, formatDateTime(LATER)])
    expect(cells('quote.sent')).toEqual(['quote.sent', 'DEAD', '8', '502', 'HTTP 502', at, '—'])
    expect(cells('booking.confirmed')).toEqual(['booking.confirmed', 'PENDING', '2', '—', '', at, '—'])
    expect(within(logRowOf('reply.sent')).getByText('DELIVERED')).toHaveClass('badge', 'text-bg-success')
    expect(within(logRowOf('quote.sent')).getByText('DEAD')).toHaveClass('badge', 'text-bg-danger')
    expect(within(logRowOf('booking.confirmed')).getByText('PENDING')).toHaveClass('badge', 'text-bg-warning')
    expect(within(logRowOf('quote.sent')).getByText('HTTP 502')).toHaveClass('text-danger')
  })

  it('hides the log on Hide log without a request, and reloads it when opened again', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'DELIVERED', { eventType: 'reply.sent' })])
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('reply.sent', { selector: 'code' })
    await user.click(inRow(ON.url).getByRole('button', { name: 'Hide log' }))

    expect(screen.queryByText('reply.sent', { selector: 'code' })).not.toBeInTheDocument()
    expect(inRow(ON.url).getByRole('button', { name: 'Log' })).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(1)

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    expect(await screen.findByText('reply.sent', { selector: 'code' })).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(2)
  })

  it('keeps one log open at a time — opening another row closes the first', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'DELIVERED', { eventType: 'reply.sent' })])
    routes['GET /api/webhooks/3/deliveries'] = () => json([delivery(20, 'DEAD', { eventType: 'quote.sent' })])
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('reply.sent', { selector: 'code' })
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Log' }))

    expect(await screen.findByText('quote.sent', { selector: 'code' })).toBeInTheDocument()
    expect(screen.queryByText('reply.sent', { selector: 'code' })).not.toBeInTheDocument()
    expect(inRow(ON.url).getByRole('button', { name: 'Log' })).toBeInTheDocument()
    expect(inRow(OFF.url).getByRole('button', { name: 'Hide log' })).toBeInTheDocument()
    expect(rowOf(OFF.url).nextElementSibling?.contains(logRowOf('quote.sent'))).toBe(true)
  })

  it.each([
    { name: 'an empty log', answer: () => json([]) },
    { name: 'a failed log load', answer: failure('Subscription not found') },
  ])('says "No deliveries yet." for $name, with no page message', async ({ answer }) => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = answer
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))

    expect(await screen.findByText('No deliveries yet.')).toBeInTheDocument()
    expect(screen.queryByText('Subscription not found')).not.toBeInTheDocument()
    expect(document.querySelector('.alert')).toBeNull()
  })

  it('drops the rows of the previously opened log when the next log fails to load', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'DELIVERED', { eventType: 'reply.sent' })])
    routes['GET /api/webhooks/3/deliveries'] = failure('Subscription not found')
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('reply.sent', { selector: 'code' })
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Log' }))

    expect(await screen.findByText('No deliveries yet.')).toBeInTheDocument()
    expect(screen.queryByText('reply.sent', { selector: 'code' })).not.toBeInTheDocument()
  })
})

describe('WebhooksPage — log loading and in-flight guards', () => {
  const logRowOf = (eventType: string) => screen.queryByText(eventType, { selector: 'code' })

  it('shows Loading… under a newly opened log instead of the previous log\'s rows', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'DELIVERED', { eventType: 'reply.sent' })])
    const third = held(() => json([delivery(20, 'DEAD', { eventType: 'quote.sent' })]))
    routes['GET /api/webhooks/3/deliveries'] = third.route
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('reply.sent', { selector: 'code' })
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Log' }))

    expect(logRowOf('reply.sent')).not.toBeInTheDocument()
    expect(within(rowOf(OFF.url).nextElementSibling as HTMLElement).getByText('Loading…')).toBeInTheDocument()
    await third.release()
    expect(await screen.findByText('quote.sent', { selector: 'code' })).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('ignores a log answer that arrives after another log was opened', async () => {
    const user = userEvent.setup()
    const first = held(() => json([delivery(10, 'DELIVERED', { eventType: 'reply.sent' })]))
    routes['GET /api/webhooks/1/deliveries'] = first.route
    routes['GET /api/webhooks/3/deliveries'] = () => json([delivery(20, 'DEAD', { eventType: 'quote.sent' })])
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('quote.sent', { selector: 'code' })
    await first.release()

    expect(screen.getByText('quote.sent', { selector: 'code' })).toBeInTheDocument()
    expect(logRowOf('reply.sent')).not.toBeInTheDocument()
  })

  it('ignores the answer of a log reopened before its first load answered', async () => {
    const user = userEvent.setup()
    const stale = held(() => json([delivery(10, 'PENDING', { eventType: 'reply.sent' })]))
    routes['GET /api/webhooks/1/deliveries'] = stale.route
    await renderPage()

    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await user.click(inRow(ON.url).getByRole('button', { name: 'Hide log' }))
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(11, 'DELIVERED', { eventType: 'booking.confirmed' })])
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('booking.confirmed', { selector: 'code' })
    await stale.release()

    expect(screen.getByText('booking.confirmed', { selector: 'code' })).toBeInTheDocument()
    expect(logRowOf('reply.sent')).not.toBeInTheDocument()
  })

  it('reloads the open log after a test event is queued for that webhook', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([])
    routes['POST /api/webhooks/1/test'] = () => json(SNAPSHOT)
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('No deliveries yet.')

    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'PENDING', { eventType: 'test', deliveredAt: null })])
    await user.click(inRow(ON.url).getByRole('button', { name: 'Test' }))

    expect(await screen.findByText('test', { selector: 'code' })).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(2)
    expect(screen.getByText('Test event queued.')).toBeInTheDocument()
  })

  it('does not fill a log opened while a test of another webhook was in flight', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'PENDING', { eventType: 'reply.sent' })])
    routes['GET /api/webhooks/3/deliveries'] = () => json([delivery(20, 'DEAD', { eventType: 'quote.sent' })])
    const test = held(() => json(SNAPSHOT))
    routes['POST /api/webhooks/1/test'] = test.route
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('reply.sent', { selector: 'code' })

    await user.click(inRow(ON.url).getByRole('button', { name: 'Test' }))
    await user.click(inRow(OFF.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('quote.sent', { selector: 'code' })
    await test.release()

    expect(await screen.findByText('Test event queued.')).toBeInTheDocument()
    expect(screen.getByText('quote.sent', { selector: 'code' })).toBeInTheDocument()
    expect(logRowOf('reply.sent')).not.toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(1)
  })

  it('does not reload a log hidden while its test was in flight', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([])
    const test = held(() => json(SNAPSHOT))
    routes['POST /api/webhooks/1/test'] = test.route
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('No deliveries yet.')

    await user.click(inRow(ON.url).getByRole('button', { name: 'Test' }))
    await user.click(inRow(ON.url).getByRole('button', { name: 'Hide log' }))
    await test.release()

    expect(await screen.findByText('Test event queued.')).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(1)
  })

  it('reloads a log reopened while its test was in flight, once the test is queued', async () => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([])
    const test = held(() => json(SNAPSHOT))
    routes['POST /api/webhooks/1/test'] = test.route
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('No deliveries yet.')

    await user.click(inRow(ON.url).getByRole('button', { name: 'Test' }))
    await user.click(inRow(ON.url).getByRole('button', { name: 'Hide log' }))
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('No deliveries yet.')
    routes['GET /api/webhooks/1/deliveries'] = () => json([delivery(10, 'PENDING', { eventType: 'test', deliveredAt: null })])
    await test.release()

    expect(await screen.findByText('test', { selector: 'code' })).toBeInTheDocument()
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(3)
  })

  it.each([
    { name: 'a test of another webhook', other: true, answer: () => json(SNAPSHOT) },
    { name: 'a failed test', other: false, answer: failure('Subscription not found') },
  ])('leaves the open log alone after $name', async ({ other, answer }) => {
    const user = userEvent.setup()
    routes['GET /api/webhooks/1/deliveries'] = () => json([])
    routes[`POST /api/webhooks/${other ? 3 : 1}/test`] = answer
    await renderPage()
    await user.click(inRow(ON.url).getByRole('button', { name: 'Log' }))
    await screen.findByText('No deliveries yet.')

    await user.click(inRow(other ? OFF.url : ON.url).getByRole('button', { name: 'Test' }))

    await screen.findByText(other ? 'Test event queued.' : 'Subscription not found')
    expect(gets('/api/webhooks/1/deliveries')).toHaveLength(1)
  })

  it('disables Create while the create is in flight, so a double click sends one request', async () => {
    const user = userEvent.setup()
    const created = held(() => json({ secret: 'whsec_once', snapshot: snapshot([ON, sub(4, 'https://new.example.com/hook')]) }))
    routes['POST /api/webhooks'] = created.route
    await renderPage()

    await user.type(urlInput(), 'https://new.example.com/hook')
    await user.dblClick(createButton())
    await user.type(urlInput(), '{Enter}')

    expect(createButton()).toBeDisabled()
    expect(mutations()).toHaveLength(1)
    await created.release()
    expect(await screen.findByText('whsec_once')).toBeInTheDocument()
    expect(createButton()).toBeEnabled()
  })

  it('enables Create again after a rejected create', async () => {
    const user = userEvent.setup()
    routes['POST /api/webhooks'] = failure('Webhook URL must use HTTPS')
    await renderPage()

    await user.type(urlInput(), 'https://10.0.0.1/hook')
    await user.click(createButton())

    await screen.findByText('Webhook URL must use HTTPS')
    expect(createButton()).toBeEnabled()
  })
})
