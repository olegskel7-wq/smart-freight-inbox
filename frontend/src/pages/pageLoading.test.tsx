import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AnalyticsResponse, AnalyticsSummary, DocumentsResponse, KnowledgeResponse, Page, RatesResponse,
  Tenant, TicketDetail, TicketStats, TicketSummary, TrainingExamplesResponse, User, WebhooksResponse,
} from '../types.ts'
import AnalyticsPage from './AnalyticsPage.tsx'
import DocumentsPage from './DocumentsPage.tsx'
import KnowledgePage from './KnowledgePage.tsx'
import RatesPage from './RatesPage.tsx'
import TenantsPage from './TenantsPage.tsx'
import TicketDetailPage from './TicketDetailPage.tsx'
import TicketsListPage from './TicketsListPage.tsx'
import TrainingExamplesPage from './TrainingExamplesPage.tsx'
import UsersPage from './UsersPage.tsx'
import WebhooksPage from './WebhooksPage.tsx'

// Pins how every page loads its data today — the request it sends on mount, the loading state, the
// load error, the refetch when a URL filter changes and the reload after a mutation — as the safety
// net for moving the pages onto one fetch-on-mount hook (docs/refactoring.md, Stage 1.3). fetch is
// stubbed, so the real useApi runs.

// One stable object, like the real context value (see SettingsPage.test.tsx). A test swaps it for a
// new one to act out a silent token renewal, which rebuilds useApi's client.
const SIGNED_IN = { user: { access_token: 'test-token', profile: { sub: 'me' } }, signinRedirect: vi.fn() }
const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))

// The live stream is a STOMP socket; the tests fire its callback by hand.
let liveEvent: () => void = () => {}
vi.mock('../api/useTicketStream.ts', () => ({
  useTicketStream: (onEvent: () => void) => {
    liveEvent = onEvent
    return true
  },
}))

// Chart.js needs a real canvas; the charts are not what these tests pin.
vi.mock('react-chartjs-2', () => ({ Bar: () => null }))

type Call = { method: string; url: string; auth: string | null }

let calls: Call[]
let routes: Record<string, () => Response | Promise<Response>>

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const failure = (message: string) => () => json({ error: 'Internal', message }, 500)

// A response the test releases by hand, to look at the page while the request is in flight.
function held(response: () => Response) {
  let release = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { route: () => gate.then(response), release: () => act(async () => release()) }
}

beforeEach(() => {
  oidc.auth = SIGNED_IN
  calls = []
  routes = {}
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, auth: new Headers(init?.headers).get('Authorization') })
    const route = routes[`${method} ${url}`]
    return route ? route() : json({ message: `unexpected ${method} ${url}` }, 404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const gets = (url: string) => calls.filter((c) => c.method === 'GET' && c.url === url)

const renderAt = (page: ReactElement, path = '/') =>
  render(<MemoryRouter initialEntries={[path]}>{page}</MemoryRouter>)

const AT = '2026-10-01T09:30:00Z'

const DOCUMENTS: DocumentsResponse = {
  featureEnabled: true,
  documents: [{ id: 1, identifier: 'MSKU1234565', docType: 'POD', filename: 'pod-1234.pdf', contentType: 'application/pdf', fileSize: 2048, uploadedAt: AT }],
  docTypes: [{ value: 'POD', label: 'Proof of delivery' }, { value: 'CMR', label: 'CMR note' }],
}
const KNOWLEDGE: KnowledgeResponse = {
  featureEnabled: true,
  documents: [{ id: 1, title: 'Opening hours', content: 'Mon–Fri 8–18', source: null, createdAt: AT, updatedAt: AT }],
}
const RATES: RatesResponse = {
  featureEnabled: true,
  rates: [{ id: 1, carrier: 'Northern Haulage', origin: 'Felixstowe', destination: 'Leeds', rate: '400.00', currency: 'GBP', marginPercent: '10', sellPrice: '440.00', validUntil: null, expired: false }],
}
const TENANTS: Tenant[] = [{ id: 1, name: 'Felixstowe Freight Ltd', userCount: 2, createdAt: AT }]
const EXAMPLE = { id: 1, ticketType: 'RFQ', aiDraftPreview: 'AI draft', operatorFinalPreview: 'Final reply', createdAt: AT }
const TRAINING: TrainingExamplesResponse = {
  featureEnabled: true,
  examples: [{ ...EXAMPLE, enquiryPreview: 'Quote for 2 pallets' }],
}
const USERS: User[] = [{ id: 'u1', email: 'op@example.com', role: 'OPERATOR' }]
const WEBHOOKS: WebhooksResponse = {
  subscriptions: [{ id: 1, url: 'https://crm.example.com/hook', eventTypes: ['*'], description: null, enabled: true, disabledReason: null, createdAt: AT, updatedAt: AT }],
  availableEventTypes: ['ticket.created'],
  secretConfigured: true,
}

// Pages that load one snapshot on mount and render it as a table.
const SNAPSHOT_PAGES: { name: string; page: () => ReactElement; url: string; body: unknown; shows: string; fallback: string }[] = [
  { name: 'DocumentsPage', page: () => <DocumentsPage />, url: '/api/documents', body: DOCUMENTS, shows: 'pod-1234.pdf', fallback: 'Failed to load documents' },
  { name: 'KnowledgePage', page: () => <KnowledgePage />, url: '/api/knowledge', body: KNOWLEDGE, shows: 'Opening hours', fallback: 'Failed to load knowledge base' },
  { name: 'RatesPage', page: () => <RatesPage />, url: '/api/rates', body: RATES, shows: 'Northern Haulage', fallback: 'Failed to load rates' },
  { name: 'TenantsPage', page: () => <TenantsPage />, url: '/api/admin/tenants', body: TENANTS, shows: 'Felixstowe Freight Ltd', fallback: 'Failed to load tenants' },
  { name: 'TrainingExamplesPage', page: () => <TrainingExamplesPage />, url: '/api/training-examples', body: TRAINING, shows: 'Quote for 2 pallets', fallback: 'Failed to load training examples' },
  { name: 'UsersPage', page: () => <UsersPage />, url: '/api/users', body: USERS, shows: 'op@example.com', fallback: 'Failed to load users' },
  { name: 'WebhooksPage', page: () => <WebhooksPage />, url: '/api/webhooks', body: WEBHOOKS, shows: 'https://crm.example.com/hook', fallback: 'Failed to load webhooks' },
]

describe.each(SNAPSHOT_PAGES)('$name', ({ page, url, body, shows }) => {
  it('shows Loading…, then the snapshot fetched once with the bearer token', async () => {
    routes[`GET ${url}`] = () => json(body)

    renderAt(page())

    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(await screen.findByText(shows)).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
    expect(gets(url)).toEqual([{ method: 'GET', url, auth: 'Bearer test-token' }])
  })

  it('replaces the page with the server message when the load fails', async () => {
    routes[`GET ${url}`] = failure('Backend down')

    renderAt(page())

    expect(await screen.findByText('Backend down')).toHaveClass('alert', 'alert-danger')
    expect(screen.queryByText(shows)).not.toBeInTheDocument()
  })
})

describe('DocumentsPage', () => {
  it('defaults the type picker to the first document type once loaded', async () => {
    routes['GET /api/documents'] = () => json(DOCUMENTS)

    renderAt(<DocumentsPage />)

    await screen.findByText('pod-1234.pdf')
    expect(screen.getByDisplayValue('Proof of delivery')).toBeInTheDocument()
  })
})

describe('TrainingExamplesPage', () => {
  it('sends the user to sign in when the load answers 401', async () => {
    const signedOut = { ...SIGNED_IN, signinRedirect: vi.fn() }
    oidc.auth = signedOut
    routes['GET /api/training-examples'] = () => new Response(null, { status: 401 })

    renderAt(<TrainingExamplesPage />)

    expect(await screen.findByText('Unauthorized')).toHaveClass('alert-danger')
    expect(signedOut.signinRedirect).toHaveBeenCalledOnce()
  })

  it('re-renders from the snapshot a delete returns, without reloading', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    routes['GET /api/training-examples'] = () => json(TRAINING)
    routes['DELETE /api/training-examples/1'] = () =>
      json({ featureEnabled: true, examples: [{ ...EXAMPLE, id: 2, enquiryPreview: 'Where is MSKU1234565?' }] })

    renderAt(<TrainingExamplesPage />)
    await user.click(await screen.findByRole('button', { name: '✕' }))

    expect(await screen.findByText('Where is MSKU1234565?')).toBeInTheDocument()
    expect(screen.queryByText('Quote for 2 pallets')).not.toBeInTheDocument()
    expect(screen.getByText('Training example deleted.')).toBeInTheDocument()
    expect(gets('/api/training-examples')).toHaveLength(1)
  })
})

const SUMMARY = {
  periodDays: 30, totalReceived: 42, relevant: 40, irrelevant: 2, irrelevantPct: 5,
  manualSends: 10, autoSends: 5, totalSends: 15, autoSendPct: 33, avgResponseFormatted: '12m',
  slaBreached: 1, slaBreachedPct: 2, aiDraftsSent: 15, aiDraftsAccepted: 9, aiDraftsEdited: 6,
  aiAcceptancePct: 60, aiAcceptanceFormatted: '60%', quotesSent: 4, quotesConverted: 1,
  quoteConversionPct: 25, quoteConversionFormatted: '25%', byType: [], untyped: 0, untypedPct: 0,
  byStatus: [], volumeLabels: [], volumeRelevant: [], volumeJunk: [], confidenceBins: [], confidenceCount: 0,
} satisfies AnalyticsSummary
const analytics = (days: number, totalReceived: number): AnalyticsResponse => ({
  featureEnabled: true, queryEnabled: true, days, allowedPeriods: [7, 30, 90],
  summary: { ...SUMMARY, periodDays: days, totalReceived },
})

describe('AnalyticsPage', () => {
  it('loads the period from the URL and refetches when another period is picked', async () => {
    const user = userEvent.setup()
    routes['GET /api/analytics?days=30'] = () => json(analytics(30, 42))
    routes['GET /api/analytics?days=7'] = () => json(analytics(7, 11))

    renderAt(<AnalyticsPage />, '/analytics')

    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(await screen.findByText('42')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '7 days' }))

    expect(await screen.findByText('11')).toBeInTheDocument()
    expect(gets('/api/analytics?days=30')).toHaveLength(1)
    expect(gets('/api/analytics?days=7')).toHaveLength(1)
  })

  it('replaces the page with the server message when the load fails', async () => {
    routes['GET /api/analytics?days=90'] = failure('Analytics unavailable')

    renderAt(<AnalyticsPage />, '/analytics?days=90')

    expect(await screen.findByText('Analytics unavailable')).toHaveClass('alert-danger')
  })

  it('ignores an older period that answers after the newer one', async () => {
    const user = userEvent.setup()
    const slow = held(() => json(analytics(7, 11)))
    routes['GET /api/analytics?days=30'] = () => json(analytics(30, 42))
    routes['GET /api/analytics?days=7'] = slow.route
    routes['GET /api/analytics?days=90'] = () => json(analytics(90, 99))

    renderAt(<AnalyticsPage />, '/analytics')
    await screen.findByText('42')
    await user.click(screen.getByRole('button', { name: '7 days' }))
    await user.click(screen.getByRole('button', { name: '90 days' }))
    expect(await screen.findByText('99')).toBeInTheDocument()

    await slow.release()

    expect(screen.getByText('99')).toBeInTheDocument()
    expect(screen.queryByText('11')).not.toBeInTheDocument()
  })

  it('a failed question replaces the page with the error, like a failed load', async () => {
    const user = userEvent.setup()
    routes['GET /api/analytics?days=30'] = () => json(analytics(30, 42))
    routes['POST /api/analytics/ask'] = failure('Model offline')

    renderAt(<AnalyticsPage />, '/analytics')
    await user.type(await screen.findByLabelText('Ask your inbox'), 'How many RFQs?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByText('Model offline')).toHaveClass('alert-danger')
    expect(screen.queryByText('42')).not.toBeInTheDocument()
  })
})

const ticket = (id: number, subject: string): TicketSummary => ({
  id, senderEmail: 'Jane Doe <jane@example.com>', subject, snippet: null, ticketType: 'RFQ', urgency: null,
  sentimentNegative: false, extractedIdentifier: null, status: 'DRAFT_READY', aiConfidence: null,
  slaDeadlineAt: null, slaBreached: false, proactive: false, assigneeUserId: null, assigneeEmail: null,
  createdAt: AT, updatedAt: AT,
})
const page = (...content: TicketSummary[]): Page<TicketSummary> =>
  ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 })
const STATS: TicketStats = { open: 3, awaitingReview: 2, slaBreached: 1, autoSentToday: 0 }

describe('TicketsListPage', () => {
  it('shows Loading… until the first page arrives, then the tickets and the summary counts', async () => {
    const first = held(() => json(page(ticket(1, 'Quote Felixstowe → Leeds'))))
    routes['GET /api/tickets?page=0&view=open'] = first.route
    routes['GET /api/tickets/summary'] = () => json(STATS)

    renderAt(<TicketsListPage />, '/tickets')

    expect(await screen.findByText('Loading…')).toBeInTheDocument()
    await first.release()
    expect(await screen.findByText('Quote Felixstowe → Leeds')).toBeInTheDocument()
    expect(await screen.findByText('3 to review · 1 overdue')).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
    expect(gets('/api/tickets?page=0&view=open')).toEqual([{ method: 'GET', url: '/api/tickets?page=0&view=open', auth: 'Bearer test-token' }])
    expect(gets('/api/tickets/summary')).toHaveLength(1)
  })

  it('shows the empty state for an empty page', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page())
    routes['GET /api/tickets/summary'] = () => json(STATS)

    renderAt(<TicketsListPage />, '/tickets')

    expect(await screen.findByText('Nothing here — the queue is clear.')).toBeInTheDocument()
  })

  it('keeps the list when the summary counts fail', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page(ticket(1, 'Quote Felixstowe → Leeds')))
    routes['GET /api/tickets/summary'] = failure('Summary down')

    renderAt(<TicketsListPage />, '/tickets')

    expect(await screen.findByText('Quote Felixstowe → Leeds')).toBeInTheDocument()
    await waitFor(() => expect(gets('/api/tickets/summary')).toHaveLength(1))
    expect(screen.getByText(/Incoming customer enquiries/)).toBeInTheDocument()
    expect(screen.queryByText('Summary down')).not.toBeInTheDocument()
  })

  it('refetches with the new filter when a queue tab is picked', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=0&view=open'] = () => json(page(ticket(1, 'Open enquiry')))
    routes['GET /api/tickets?page=0&status=CLOSED'] = () => json(page(ticket(2, 'Closed enquiry')))
    routes['GET /api/tickets/summary'] = () => json(STATS)

    renderAt(<TicketsListPage />, '/tickets')
    await screen.findByText('Open enquiry')
    await user.click(screen.getByRole('link', { name: 'Closed' }))

    expect(await screen.findByText('Closed enquiry')).toBeInTheDocument()
    expect(screen.queryByText('Open enquiry')).not.toBeInTheDocument()
    expect(gets('/api/tickets/summary')).toHaveLength(1)
  })

  it('shows a load error above the list and clears it when a live update reloads', async () => {
    routes['GET /api/tickets?page=0&view=open'] = failure('Tickets unavailable')
    routes['GET /api/tickets/summary'] = () => json(STATS)

    renderAt(<TicketsListPage />, '/tickets')
    expect(await screen.findByText('Tickets unavailable')).toHaveClass('alert-danger')

    routes['GET /api/tickets?page=0&view=open'] = () => json(page(ticket(1, 'Arrived live')))
    act(() => liveEvent())

    expect(await screen.findByText('Arrived live')).toBeInTheDocument()
    expect(screen.queryByText('Tickets unavailable')).not.toBeInTheDocument()
    await waitFor(() => expect(gets('/api/tickets/summary')).toHaveLength(2))
  })

  it('drops a load error once a queue tab loads a different query', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=0&view=open'] = failure('Tickets unavailable')
    routes['GET /api/tickets?page=0&status=CLOSED'] = () => json(page(ticket(2, 'Closed enquiry')))
    routes['GET /api/tickets/summary'] = () => json(STATS)

    renderAt(<TicketsListPage />, '/tickets')
    expect(await screen.findByText('Tickets unavailable')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Closed' }))

    expect(await screen.findByText('Closed enquiry')).toBeInTheDocument()
    expect(screen.queryByText('Tickets unavailable')).not.toBeInTheDocument()
  })

  it('reloads the current page after a claim', async () => {
    const user = userEvent.setup()
    let claimed = false
    routes['GET /api/tickets?page=0&view=open'] = () =>
      json(page({ ...ticket(1, 'Quote Felixstowe → Leeds'), assigneeUserId: claimed ? 'me' : null }))
    routes['GET /api/tickets/summary'] = () => json(STATS)
    routes['POST /api/tickets/1/assign'] = () => {
      claimed = true
      return new Response(null, { status: 204 })
    }

    renderAt(<TicketsListPage />, '/tickets')
    await user.click(await screen.findByRole('button', { name: 'Claim' }))

    expect(await screen.findByRole('button', { name: 'Release' })).toBeInTheDocument()
    expect(gets('/api/tickets?page=0&view=open')).toHaveLength(2)
  })
})

const DETAIL: TicketDetail = {
  id: 7, messageId: '<m7@example.com>', senderEmail: 'Jane Doe <jane@example.com>', subject: 'Where is MSKU1234565?',
  body: 'Hello, any news?', attachmentText: null, ticketType: 'STATUS_UPDATE', urgency: null, sentimentNegative: false,
  extractedIdentifier: 'MSKU1234565', status: 'DRAFT_READY', aiConfidence: 0.7, slaDeadlineAt: null, slaBreached: false,
  proactive: false, draftReply: 'Your container is at port.', aiOriginalDraft: 'Your container is at port.',
  requestedDocType: null, attachedDocument: null, availableDocuments: [], assigneeUserId: null, assigneeEmail: null,
  retryCount: 0, createdAt: AT, updatedAt: AT,
}

const ticketTree = () => (
  <MemoryRouter initialEntries={['/tickets/7']}>
    <Routes>
      <Route path="/tickets/:id" element={<TicketDetailPage />} />
    </Routes>
  </MemoryRouter>
)
const renderTicket = () => render(ticketTree())

describe('TicketDetailPage', () => {
  beforeEach(() => {
    routes['GET /api/tickets?view=open'] = () => json(page())
  })

  it('shows Loading…, then the ticket with its draft in the reply box', async () => {
    routes['GET /api/tickets/7'] = () => json(DETAIL)

    renderTicket()

    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(await screen.findByText('Where is MSKU1234565?', { selector: 'h1' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('Your container is at port.')).toBeInTheDocument()
    expect(gets('/api/tickets/7')).toEqual([{ method: 'GET', url: '/api/tickets/7', auth: 'Bearer test-token' }])
  })

  it('shows the server message when the ticket cannot be loaded', async () => {
    routes['GET /api/tickets/7'] = failure('Ticket not found')

    renderTicket()

    expect(await screen.findByText('Ticket not found')).toHaveClass('alert-danger')
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('keeps the typed reply and a failed-send error through a silent token renewal', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets/7'] = () => json(DETAIL)
    routes['POST /api/tickets/7/draft'] = () => json({ error: 'Bad Gateway', message: 'SMTP refused' }, 502)

    const { rerender } = renderTicket()
    await user.type(await screen.findByDisplayValue('Your container is at port.'), ' It clears customs today.')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    const sendError = 'Failed to send email — the message was not delivered.'
    expect(await screen.findByText(sendError)).toHaveClass('alert-danger')

    oidc.auth = { ...SIGNED_IN, user: { ...SIGNED_IN.user, access_token: 'renewed-token' } }
    rerender(ticketTree())
    await user.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2))
    expect(calls.filter((c) => c.method === 'POST')[1].auth).toBe('Bearer renewed-token')
    expect(gets('/api/tickets/7')).toHaveLength(1)
    expect(screen.getByDisplayValue('Your container is at port. It clears customs today.')).toBeInTheDocument()
    expect(screen.getByText(sendError)).toBeInTheDocument()
  })
})
