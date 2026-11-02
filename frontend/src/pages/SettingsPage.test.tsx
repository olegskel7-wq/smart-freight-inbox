import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Settings } from '../types.ts'
import SettingsPage from './SettingsPage.tsx'

// Smoke tests that pin what the settings page loads, renders and sends today — the safety net for
// splitting it into section components (docs/refactoring.md, Stage 1.3). fetch is stubbed, so the
// real useApi runs: these assert the actual method, path, bearer token and JSON body.

// One stable object, like the real context value. A test swaps it for a new one to act out a silent
// token renewal, which hands the page a new context value with a new access token.
const SIGNED_IN = { user: { access_token: 'test-token' }, signinRedirect: () => {} }
const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))

const SETTINGS: Settings = {
  cipherConfigured: true,
  platformOllamaUrl: 'http://ollama:11434',
  ollamaUrlOverride: '',
  smtpHost: 'smtp.example.com',
  smtpPort: 587,
  smtpUser: 'platform@example.com',
  draftEnabled: true,
  extractionPrompt: 'Extract {email}',
  draftingPrompt: 'Draft {email}',
  autoSendEnabled: false,
  autoSendThreshold: '0.8',
  autoSendTypes: ['STATUS_UPDATE'],
  allTicketTypes: ['RFQ', 'STATUS_UPDATE', 'GENERAL'],
  statusUpdateEnabled: false,
  statusUpdateMilestones: 'AT_PORT,RELEASED,DELIVERED',
  quoteFollowUpEnabled: false,
  quoteFollowUpDelayHours: '48',
  smartRoutingEnabled: true,
  escalationAssignee: '',
  mailboxes: [
    {
      id: 5, host: 'imap.example.com', port: 993, username: 'ops@example.com', sendsAs: null,
      enabled: true, running: true, loginOk: true, lastLoginOkAt: null, loginError: null,
      smtpHost: null, smtpPort: null, fromAddress: null,
    },
  ],
}

type Call = { method: string; url: string; auth: string | null; body: unknown }

let calls: Call[]
let respond: (method: string, url: string) => Response

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  oidc.auth = SIGNED_IN
  calls = []
  respond = (method, url) => {
    if (url === '/api/users') return json([{ id: 'u1', email: 'op@example.com', role: 'OPERATOR' }])
    if (url.startsWith('/api/settings')) return json(SETTINGS)
    return json({ message: `unexpected ${method} ${url}` }, 404)
  }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const headers = new Headers(init?.headers)
    calls.push({
      method, url, auth: headers.get('Authorization'),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    })
    return respond(method, url)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const posts = (url: string) => calls.filter((c) => c.method === 'POST' && c.url === url)

// The card that holds a section heading — scopes queries to one section.
const card = (heading: string) => within(screen.getByText(heading).closest('.card') as HTMLElement)

const MAILBOX = SETTINGS.mailboxes[0]

const mailboxRow = (overrides: Partial<typeof MAILBOX>) => ({ ...MAILBOX, ...overrides })

describe('SettingsPage', () => {
  it('loads the snapshot with the bearer token and renders every section', async () => {
    render(<SettingsPage />)

    expect(await screen.findByText('Mailboxes')).toBeInTheDocument()
    for (const heading of ['AI Endpoint', 'Notifications', 'Auto-send', 'AI Prompts']) {
      expect(screen.getByText(heading)).toBeInTheDocument()
    }
    expect(screen.getByText('imap.example.com')).toBeInTheDocument()
    expect(screen.getByText('platform')).toBeInTheDocument() // mailbox without SMTP replies via platform
    expect(calls).toContainEqual({ method: 'GET', url: '/api/settings', auth: 'Bearer test-token', body: undefined })
    expect(calls.some((c) => c.url === '/api/users')).toBe(true)
  })

  it('shows the server message when the snapshot cannot be loaded', async () => {
    respond = () => json({ error: 'Internal', message: 'Settings unavailable' }, 500)

    render(<SettingsPage />)

    expect(await screen.findByText('Settings unavailable')).toHaveClass('alert-danger')
  })

  it('saves auto-send with the edited types and threshold', async () => {
    const user = userEvent.setup()
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')

    await user.click(screen.getByLabelText(/Enable automatic sending/))
    await user.click(screen.getByLabelText('RFQ'))
    await user.clear(screen.getByLabelText('Confidence threshold'))
    await user.type(screen.getByLabelText('Confidence threshold'), '0.9')
    await user.click(screen.getByRole('button', { name: 'Save auto-send settings' }))

    expect(posts('/api/settings/autosend')).toEqual([
      expect.objectContaining({ body: { enabled: true, threshold: '0.9', types: ['STATUS_UPDATE', 'RFQ'] } }),
    ])
    // The response snapshot still says OFF, so the page reports the save as "feature is off".
    expect(await screen.findByText('Auto-send settings saved (feature is off).')).toBeInTheDocument()
  })

  it('warns when auto-send comes back on with no allowed types', async () => {
    const user = userEvent.setup()
    respond = (method, url) =>
      method === 'POST' && url === '/api/settings/autosend'
        ? json({ ...SETTINGS, autoSendEnabled: true, autoSendTypes: [] })
        : url === '/api/users' ? json([]) : json(SETTINGS)
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')

    await user.click(screen.getByRole('button', { name: 'Save auto-send settings' }))

    expect(await screen.findByText(/Auto-send is on but no ticket types are allowed/)).toHaveClass('alert-warning')
  })

  it('saves and clears the AI endpoint override', async () => {
    const user = userEvent.setup()
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')
    const endpoint = within(screen.getByText('AI Endpoint').closest('.card') as HTMLElement)

    await user.type(endpoint.getByRole('textbox'), '  http://tenant-ollama:11434 ')
    await user.click(endpoint.getByRole('button', { name: 'Save endpoint' }))

    expect(posts('/api/settings/ollama')[0].body).toEqual({ ollamaBaseUrl: '  http://tenant-ollama:11434 ' })
    expect(await screen.findByText('Ollama endpoint saved: http://tenant-ollama:11434')).toBeInTheDocument()

    await user.clear(endpoint.getByRole('textbox'))
    await user.click(endpoint.getByRole('button', { name: 'Save endpoint' }))

    expect(await screen.findByText('Ollama endpoint override cleared — using the platform instance.'))
      .toBeInTheDocument()
  })

  it('shows the server message when a save is rejected', async () => {
    const user = userEvent.setup()
    respond = (method, url) =>
      method === 'POST'
        ? json({ error: 'Bad Request', message: 'Ollama URL must start with http:// or https://' }, 400)
        : url === '/api/users' ? json([]) : json(SETTINGS)
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')

    await user.click(screen.getByRole('button', { name: 'Save endpoint' }))

    expect(await screen.findByText('Ollama URL must start with http:// or https://')).toHaveClass('alert-danger')
  })
})

// Characterisation tests for the sections the smoke tests above do not reach — written before the
// page was split into section components (Stage 1.3), so they pin today's requests, messages and
// form behaviour exactly.
describe('SettingsPage sections', () => {
  const renderLoaded = async () => {
    const user = userEvent.setup()
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')
    return user
  }

  it('saves notifications with the switch state', async () => {
    const user = await renderLoaded()

    await user.click(screen.getByLabelText(/Send email notification to operator/))
    await user.click(card('Notifications').getByRole('button', { name: 'Save' }))

    expect(posts('/api/settings/notifications')[0].body).toEqual({ draftEnabled: false })
    expect(await screen.findByText('Notifications disabled.')).toHaveClass('alert-success')
  })

  it('saves status-update automation and reports a feature that stays off', async () => {
    const user = await renderLoaded()

    await user.click(screen.getByLabelText('Enable proactive status updates'))
    await user.clear(screen.getByLabelText('Milestone statuses'))
    await user.type(screen.getByLabelText('Milestone statuses'), 'AT_PORT,DELIVERED')
    await user.click(screen.getByRole('button', { name: 'Save status-update settings' }))

    expect(posts('/api/settings/statusupdate')[0].body).toEqual({ enabled: true, milestones: 'AT_PORT,DELIVERED' })
    expect(await screen.findByText('Status-update automation saved (feature is off).')).toBeInTheDocument()
  })

  it('saves quote follow-up and re-renders the badge from the returned snapshot', async () => {
    respond = (method, url) =>
      method === 'POST' && url === '/api/settings/quotefollowup'
        ? json({ ...SETTINGS, quoteFollowUpEnabled: true, quoteFollowUpDelayHours: '24' })
        : url === '/api/users' ? json([]) : json(SETTINGS)
    const user = await renderLoaded()
    expect(card('Quote Follow-up Automation').getByText('OFF')).toBeInTheDocument()

    await user.click(screen.getByLabelText('Enable quote follow-ups'))
    // The badge follows the saved snapshot, not the unsaved switch.
    expect(card('Quote Follow-up Automation').getByText('OFF')).toBeInTheDocument()
    await user.clear(screen.getByLabelText('Delay (hours)'))
    await user.type(screen.getByLabelText('Delay (hours)'), '24')
    await user.click(screen.getByRole('button', { name: 'Save quote follow-up settings' }))

    expect(posts('/api/settings/quotefollowup')[0].body).toEqual({ enabled: true, delayHours: '24' })
    expect(await screen.findByText('Quote follow-up automation saved.')).toBeInTheDocument()
    expect(card('Quote Follow-up Automation').getByText('ON')).toBeInTheDocument()
  })

  it('saves smart routing with an operator picked from /api/users', async () => {
    const user = await renderLoaded()
    await screen.findByRole('option', { name: 'op@example.com' })

    await user.click(screen.getByLabelText(/Detect urgency/))
    await user.selectOptions(screen.getByLabelText('Escalation assignee'), 'op@example.com')
    await user.click(screen.getByRole('button', { name: 'Save smart-routing settings' }))

    expect(posts('/api/settings/routing')[0].body).toEqual({ smartRoutingEnabled: false, escalationAssignee: 'u1' })
    // The returned snapshot still has routing on, so no "(feature is off)".
    expect(await screen.findByText('Smart-routing settings saved.')).toBeInTheDocument()
  })

  it('saves both prompts', async () => {
    const user = await renderLoaded()

    await user.clear(screen.getByLabelText('Extraction prompt'))
    await user.type(screen.getByLabelText('Extraction prompt'), 'New extraction')
    await user.click(screen.getByRole('button', { name: 'Save prompts' }))

    expect(posts('/api/settings/prompts')[0].body).toEqual({
      extractionPrompt: 'New extraction', draftingPrompt: 'Draft {email}',
    })
    expect(await screen.findByText('AI prompts saved.')).toBeInTheDocument()
  })

  it('re-seeds every section from the snapshot a save returns, dropping unsaved edits elsewhere', async () => {
    const user = await renderLoaded()

    await user.type(screen.getByLabelText('Milestone statuses'), ',EXTRA')
    await user.clear(screen.getByLabelText('Confidence threshold'))
    await user.type(screen.getByLabelText('Confidence threshold'), '0.5')
    await user.click(screen.getByRole('button', { name: 'Save endpoint' }))

    await screen.findByText('Ollama endpoint override cleared — using the platform instance.')
    expect(screen.getByLabelText('Milestone statuses')).toHaveValue('AT_PORT,RELEASED,DELIVERED')
    expect(screen.getByLabelText('Confidence threshold')).toHaveValue(0.8)
  })

  it('dismisses the page message', async () => {
    const user = await renderLoaded()
    await user.click(screen.getByRole('button', { name: 'Save prompts' }))
    const alert = await screen.findByText('AI prompts saved.')

    await user.click(alert.querySelector('.btn-close') as HTMLElement)

    expect(screen.queryByText('AI prompts saved.')).not.toBeInTheDocument()
  })

  it('renders the platform SMTP block read-only', async () => {
    await renderLoaded()
    const smtp = card('Outgoing Mail (SMTP) — platform fallback')

    expect(smtp.getByText('smtp.example.com')).toBeInTheDocument()
    expect(smtp.getByText('587')).toBeInTheDocument()
    expect(smtp.getByText('platform@example.com')).toBeInTheDocument()
    expect(smtp.queryByRole('textbox')).not.toBeInTheDocument()
  })
})

describe('SettingsPage mailboxes', () => {
  const renderWith = async (settings: Settings = SETTINGS) => {
    const base = respond
    respond = (method, url) => (method === 'GET' && url === '/api/settings' ? json(settings) : base(method, url))
    const user = userEvent.setup()
    render(<SettingsPage />)
    await screen.findByText('Mailboxes')
    return user
  }

  const addForm = () =>
    within(screen.getByRole('button', { name: 'Add mailbox' }).closest('form') as HTMLElement)
  const editForm = () =>
    within(screen.getByRole('button', { name: 'Save changes' }).closest('form') as HTMLElement)
  const password = (form: ReturnType<typeof within>) =>
    form.getAllByDisplayValue('').find((el: HTMLElement) => el.getAttribute('type') === 'password') as HTMLElement

  it('shows each mailbox status as a badge', async () => {
    await renderWith({
      ...SETTINGS,
      mailboxes: [
        mailboxRow({ id: 1, host: 'disabled.example.com', enabled: false }),
        mailboxRow({ id: 2, host: 'failed.example.com', loginOk: false, loginError: 'AUTHENTICATE failed' }),
        mailboxRow({ id: 3, host: 'ok.example.com', lastLoginOkAt: '2026-10-01T10:00:00Z', sendsAs: 'out@example.com' }),
        mailboxRow({ id: 4, host: 'running.example.com', loginOk: null }),
        mailboxRow({ id: 5, host: 'starting.example.com', running: false, loginOk: null }),
      ],
    })

    expect(screen.getByText('Disabled')).toHaveClass('badge', 'bg-secondary')
    expect(screen.getByText('Login failed')).toHaveAttribute(
      'title', 'AUTHENTICATE failed — last good login: never. No new emails arrive from this mailbox.')
    expect(screen.getByText('Connected')).toHaveAttribute('title', expect.stringMatching(/^Last login check OK: /))
    expect(screen.getByText('Running')).toHaveClass('bg-success')
    expect(screen.getByText('Starting…')).toHaveClass('bg-warning')
    expect(screen.getByText('out@example.com').tagName).toBe('CODE')
    expect(screen.getAllByText('platform')).toHaveLength(4)
    expect(screen.getByRole('button', { name: 'Enable' })).toBeInTheDocument()
  })

  it('shows an empty-state line without mailboxes', async () => {
    await renderWith({ ...SETTINGS, mailboxes: [] })

    expect(screen.getByText(/No mailboxes yet/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('blocks saving passwords while the encryption key is missing', async () => {
    const user = await renderWith({ ...SETTINGS, cipherConfigured: false })

    expect(screen.getByText('MAILBOX_ENCRYPTION_KEY is not set.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add mailbox' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled() // blank password = keep stored
    await user.type(password(editForm()), 'x')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  it('adds a mailbox with blank optionals as null and resets the form', async () => {
    const user = await renderWith()
    const form = addForm()
    const [host, username] = form.getAllByRole('textbox')

    await user.type(host, 'imap.new.com')
    await user.type(username, 'new@example.com')
    await user.type(password(form), 'secret')
    await user.click(screen.getByRole('button', { name: 'Add mailbox' }))

    expect(posts('/api/settings/mailboxes')[0].body).toEqual({
      host: 'imap.new.com', port: 993, username: 'new@example.com', password: 'secret',
      smtpHost: null, smtpPort: null, fromAddress: null,
    })
    expect(await screen.findByText('Mailbox added — its IMAP adapter starts within ~30 seconds.')).toBeInTheDocument()
    expect(addForm().getAllByRole('textbox')[0]).toHaveValue('')
    expect(addForm().getAllByRole('spinbutton')[0]).toHaveValue(993)
  })

  it('keeps the add form and shows a rejected add inside it', async () => {
    respond = (method, url) =>
      method === 'POST' && url === '/api/settings/mailboxes'
        ? json({ message: 'Mailbox already exists' }, 409)
        : url === '/api/users' ? json([]) : json(SETTINGS)
    const user = await renderWith()
    const form = addForm()
    const [host, username, smtpHost] = form.getAllByRole('textbox')

    await user.type(host, 'imap.new.com')
    await user.type(username, 'new@example.com')
    await user.type(password(form), 'secret')
    await user.type(smtpHost, 'smtp.new.com')
    await user.type(form.getAllByRole('spinbutton')[1], '465')
    await user.click(screen.getByRole('button', { name: 'Add mailbox' }))

    expect(posts('/api/settings/mailboxes')[0].body).toEqual(
      expect.objectContaining({ smtpHost: 'smtp.new.com', smtpPort: 465 }))
    expect(await form.findByText('Mailbox already exists')).toHaveClass('alert-danger')
    expect(host).toHaveValue('imap.new.com')
    expect(document.querySelector('.alert-dismissible')).toBeNull()
  })

  it('edits a mailbox in place and closes the form on success', async () => {
    respond = (method, url) =>
      method === 'PUT' ? json(SETTINGS) : url === '/api/users' ? json([]) : json(SETTINGS)
    const user = await renderWith({
      ...SETTINGS, mailboxes: [mailboxRow({ smtpHost: 'smtp.old.com', smtpPort: 587, fromAddress: 'from@example.com' })],
    })

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    const form = editForm()
    expect(form.getByDisplayValue('imap.example.com')).toBeInTheDocument()
    expect(form.getByDisplayValue('smtp.old.com')).toBeInTheDocument()
    expect(form.getByDisplayValue('587')).toBeInTheDocument()
    expect(form.getByPlaceholderText('Leave blank to keep')).toHaveValue('')
    expect(screen.getAllByRole('button', { name: 'Cancel' })).toHaveLength(2) // row toggle + form link

    await user.clear(form.getByDisplayValue('from@example.com'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(calls.filter((c) => c.method === 'PUT')).toEqual([expect.objectContaining({
      url: '/api/settings/mailboxes/5',
      body: {
        host: 'imap.example.com', port: 993, username: 'ops@example.com', password: '',
        smtpHost: 'smtp.old.com', smtpPort: 587, fromAddress: null,
      },
    })])
    expect(await screen.findByText('Mailbox updated — changes apply within ~30 seconds.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
  })

  it('keeps the edit form open on a rejected update and clears the error on reopen', async () => {
    respond = (method, url) =>
      method === 'PUT'
        ? json({ message: 'Re-enter the password to change the server' }, 400)
        : url === '/api/users' ? json([]) : json(SETTINGS)
    const user = await renderWith()

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await editForm().findByText('Re-enter the password to change the server')).toHaveClass('alert-danger')
    expect(document.querySelector('.alert-dismissible')).toBeNull()

    await user.click(editForm().getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.queryByText('Re-enter the password to change the server')).not.toBeInTheDocument()
  })

  it('closes the edit form from the row button', async () => {
    const user = await renderWith()

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await user.click(screen.getAllByRole('button', { name: 'Cancel' })[0])

    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
  })

  it('toggles a mailbox', async () => {
    const user = await renderWith()

    await user.click(screen.getByRole('button', { name: 'Disable' }))

    expect(posts('/api/settings/mailboxes/5/toggle')[0].body).toEqual({ enabled: false })
    expect(await screen.findByText('Mailbox disabled.')).toBeInTheDocument()
  })

  it('deletes a mailbox only after confirmation and reloads the snapshot', async () => {
    respond = (method, url) =>
      method === 'DELETE' ? new Response(null, { status: 204 }) : url === '/api/users' ? json([]) : json(SETTINGS)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = await renderWith()

    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(confirm).toHaveBeenCalledWith('Delete this mailbox? Its adapter stops within ~30 seconds.')
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    confirm.mockReturnValue(true)
    const loadsBefore = calls.filter((c) => c.url === '/api/settings').length
    await user.click(screen.getByRole('button', { name: 'Delete' }))

    expect(await screen.findByText('Mailbox deleted.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.url)).toEqual(['/api/settings/mailboxes/5'])
    expect(calls.filter((c) => c.url === '/api/settings')).toHaveLength(loadsBefore + 1)
  })

  it('reports a failed delete in the page alert', async () => {
    respond = (method, url) =>
      method === 'DELETE' ? json({ message: 'Mailbox not found' }, 404) : url === '/api/users' ? json([]) : json(SETTINGS)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = await renderWith()

    await user.click(screen.getByRole('button', { name: 'Delete' }))

    expect(await screen.findByText('Mailbox not found')).toHaveClass('alert-danger')
  })

  it('keeps an unsaved edit through a silent token renewal and saves with the renewed token', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<SettingsPage />)
    const prompt = await screen.findByLabelText('Extraction prompt')
    await user.type(prompt, ' and the job ref')

    oidc.auth = { ...SIGNED_IN, user: { access_token: 'renewed-token' } }
    rerender(<SettingsPage />)
    await user.click(screen.getByRole('button', { name: 'Save prompts' }))

    expect(await screen.findByText('AI prompts saved.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'GET' && c.url === '/api/settings')).toHaveLength(1)
    const save = posts('/api/settings/prompts')[0]
    expect(save.auth).toBe('Bearer renewed-token')
    expect(save.body).toMatchObject({ extractionPrompt: 'Extract {email} and the job ref' })
  })
})
