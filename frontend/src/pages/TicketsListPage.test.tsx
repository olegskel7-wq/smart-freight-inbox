import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Page, TicketStats, TicketSummary } from '../types.ts'
import TicketsListPage from './TicketsListPage.tsx'

// Pins what the ticket list shows and sends today — URL → request and page title, the queue tabs,
// summary chips, filters, paging, the rows and their Claim / Release, Reprocess, and the queue the
// list hands the ticket page — as the safety net for splitting the page into components
// (docs/refactoring.md, Stage 1.3). pageLoading.test.tsx covers loading, live reloads and errors.
// A real BrowserRouter: the page merges filter changes into window.location.search, so a
// MemoryRouter would hide what the address bar actually ends up holding.

const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))
vi.mock('../api/useTicketStream.ts', () => ({ useTicketStream: () => true }))

type Call = { method: string; url: string; body: string | null }

let calls: Call[]
let routes: Record<string, () => Response | Promise<Response>>

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const failure = (message: string) => () => json({ error: 'Internal', message }, 500)

function held(response: () => Response) {
  let release = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { route: () => gate.then(response), release: () => act(async () => release()) }
}

beforeEach(() => {
  oidc.auth = { user: { access_token: 'test-token', profile: { sub: 'me' } }, signinRedirect: vi.fn() }
  calls = []
  routes = { 'GET /api/tickets/summary': () => json(STATS) }
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

const gets = (url: string) => calls.filter((c) => c.method === 'GET' && c.url === url)
const posts = () => calls.filter((c) => c.method === 'POST')

// What the ticket page receives through router state when a subject is clicked.
function TicketProbe() {
  const { state } = useLocation()
  return <pre data-testid="ticket-state">{JSON.stringify(state)}</pre>
}

function renderAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(
    <BrowserRouter>
      <Routes>
        <Route path="/tickets" element={<TicketsListPage />} />
        <Route path="/tickets/:id" element={<TicketProbe />} />
      </Routes>
    </BrowserRouter>,
  )
}

const AT = '2026-10-01T09:30:00Z'
const STATS: TicketStats = { open: 3, awaitingReview: 2, slaBreached: 1, autoSentToday: 4 }

const ticket = (id: number, subject: string, extra: Partial<TicketSummary> = {}): TicketSummary => ({
  id, senderEmail: 'Jane Doe <jane@example.com>', subject, snippet: null, ticketType: 'RFQ', urgency: null,
  sentimentNegative: false, extractedIdentifier: null, status: 'DRAFT_READY', aiConfidence: null,
  slaDeadlineAt: null, slaBreached: false, proactive: false, assigneeUserId: null, assigneeEmail: null,
  createdAt: AT, updatedAt: AT, ...extra,
})
const page = (content: TicketSummary[], at = 0, totalPages = 1, totalElements = content.length): Page<TicketSummary> =>
  ({ content, page: at, size: 50, totalElements, totalPages })

const queueTabs = () => within(screen.getByRole('navigation', { name: 'Queues' }))
const rowOf = (subject: string) => screen.getByRole('link', { name: subject }).closest('.sf-row') as HTMLElement

describe('TicketsListPage — URL, request and title', () => {
  it.each([
    { path: '/tickets', request: 'page=0&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?view=open', request: 'page=0&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?assignee=mine', request: 'page=0&assignee=mine', title: 'Assigned to me', tab: 'Assigned to me' },
    { path: '/tickets?status=AUTO_SENT', request: 'page=0&status=AUTO_SENT', title: 'Auto-sent', tab: 'Auto-sent' },
    { path: '/tickets?status=CLOSED', request: 'page=0&status=CLOSED', title: 'Closed', tab: 'Closed' },
    { path: '/tickets?view=all', request: 'page=0&view=all', title: 'All', tab: 'All' },
    { path: '/tickets?view=breached', request: 'page=0&view=breached', title: 'SLA breached', tab: null },
    { path: '/tickets?view=autosent_today', request: 'page=0&view=autosent_today', title: 'Auto-sent today', tab: null },
    { path: '/tickets?view=awaiting&status=CLOSED&assignee=mine', request: 'page=0&view=awaiting', title: 'Awaiting review', tab: null },
    { path: '/tickets?status=CLOSED&assignee=mine', request: 'page=0&status=CLOSED&assignee=mine', title: 'Closed', tab: null },
    { path: '/tickets?status=AUTO_SENT&assignee=unassigned', request: 'page=0&status=AUTO_SENT&assignee=unassigned', title: 'Auto-sent', tab: null },
    { path: '/tickets?status=FAILED', request: 'page=0&status=FAILED', title: 'Failed', tab: null },
    { path: '/tickets?view=weird', request: 'page=0&view=weird', title: 'Tickets', tab: null },
    { path: '/tickets?sort=createdAt&dir=asc', request: 'page=0&sort=createdAt&dir=asc&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?sort=slaDeadlineAt', request: 'page=0&sort=slaDeadlineAt&dir=desc&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?page=2&assignee=unassigned', request: 'page=2&assignee=unassigned', title: 'Tickets', tab: null },
    { path: '/tickets?page=-3', request: 'page=0&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?page=abc', request: 'page=0&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?page=1.7', request: 'page=1&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?page=Infinity', request: 'page=0&view=open', title: 'Inbox', tab: 'Inbox' },
    { path: '/tickets?view=constructor', request: 'page=0&view=constructor', title: 'Tickets', tab: null },
    { path: '/tickets?status=toString', request: 'page=0&status=toString', title: 'Tostring', tab: null },
  ])('$path → GET /api/tickets?$request, titled "$title"', async ({ path, request, title, tab }) => {
    routes[`GET /api/tickets?${request}`] = () => json(page([ticket(1, 'Some enquiry')]))

    renderAt(path)

    expect(await screen.findByText('Some enquiry')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(title)
    expect(gets(`/api/tickets?${request}`)).toHaveLength(1)
    const current = queueTabs().queryByRole('link', { current: 'page' })
    if (tab) expect(current).toHaveTextContent(tab)
    else expect(current).toBeNull()
  })

  it('links every queue tab to its filter', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([]))
    renderAt('/tickets')
    await screen.findByText('Nothing here — the queue is clear.')

    const hrefs = queueTabs().getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])
    expect(hrefs).toEqual([
      ['Inbox', '/tickets?view=open'],
      ['Assigned to me', '/tickets?assignee=mine'],
      ['Auto-sent', '/tickets?status=AUTO_SENT'],
      ['Closed', '/tickets?status=CLOSED'],
      ['All', '/tickets?view=all'],
    ])
  })
})

describe('TicketsListPage — summary chips', () => {
  it('shows the counts as drill-down links and marks the active view', async () => {
    routes['GET /api/tickets?page=0&view=breached'] = () => json(page([]))
    renderAt('/tickets?view=breached')
    await screen.findByText('Nothing here — the queue is clear.')

    const chips = [
      ['Show open tickets', '/tickets?view=open', '3Open'],
      ['Show awaiting review tickets', '/tickets?view=awaiting', '2Awaiting review'],
      ['Show sla breached tickets', '/tickets?view=breached', '1SLA breached'],
      ['Show auto-sent today tickets', '/tickets?view=autosent_today', '4Auto-sent today'],
    ]
    for (const [title, href, text] of chips) {
      const chip = await screen.findByTitle(title)
      expect(chip).toHaveAttribute('href', href)
      await waitFor(() => expect(chip).toHaveTextContent(text))
      if (href.endsWith('breached')) expect(chip).toHaveClass('sf-chip', 'is-active')
      else expect(chip).not.toHaveClass('is-active')
    }
    expect(screen.getByText('3 to review · 1 overdue')).toBeInTheDocument()
  })

  it('shows a dash in every chip until the summary arrives', async () => {
    const summary = held(() => json(STATS))
    routes['GET /api/tickets/summary'] = summary.route
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([]))
    renderAt('/tickets')
    await screen.findByText('Nothing here — the queue is clear.')

    expect(screen.getByTitle('Show open tickets')).toHaveTextContent('—Open')
    expect(screen.getByTitle('Show open tickets')).toHaveClass('is-active')
    await summary.release()
    expect(screen.getByTitle('Show open tickets')).toHaveTextContent('3Open')
  })
})

describe('TicketsListPage — filters and sort', () => {
  it('shows the filters and sort from the URL', async () => {
    routes['GET /api/tickets?page=0&sort=createdAt&dir=asc&status=CLOSED&assignee=mine'] = () => json(page([ticket(1, 'My closed enquiry')]))
    renderAt('/tickets?status=CLOSED&assignee=mine&sort=createdAt&dir=asc')
    await screen.findByRole('link', { name: 'My closed enquiry' })

    expect(screen.getByLabelText('Assignee')).toHaveValue('mine')
    expect(screen.getByLabelText('Status')).toHaveValue('CLOSED')
    expect(screen.getByLabelText('Sort')).toHaveValue('createdAt:asc')
    expect(screen.getByText('1 tickets')).toBeInTheDocument()
  })

  it('shows All / All in the filters while the URL names a view', async () => {
    routes['GET /api/tickets?page=0&view=awaiting'] = () => json(page([ticket(2, 'Awaiting enquiry')]))
    renderAt('/tickets?view=awaiting&status=CLOSED&assignee=mine')
    await screen.findByRole('link', { name: 'Awaiting enquiry' })
    expect(screen.getByLabelText('Assignee')).toHaveValue('all')
    expect(screen.getByLabelText('Status')).toHaveValue('')
    expect(screen.getByLabelText('Sort')).toHaveValue('')
  })

  it('lists every ticket status in the Status filter', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([]))
    renderAt('/tickets')
    await screen.findByText('Nothing here — the queue is clear.')

    const options = within(screen.getByLabelText('Status')).getAllByRole('option').map((o) => [o.getAttribute('value'), o.textContent])
    expect(options).toEqual([
      ['', 'All'], ['RECEIVED', 'Received'], ['NEW', 'Queued'], ['PROCESSING', 'Drafting'],
      ['DRAFT_READY', 'Draft ready'], ['FAILED', 'Failed'], ['RETRY_LATER', 'Retrying'],
      ['IRRELEVANT', 'Not relevant'], ['CLOSED', 'Closed'], ['AUTO_SENT', 'Auto-sent'],
    ])
    const sorts = within(screen.getByLabelText('Sort')).getAllByRole('option').map((o) => [o.getAttribute('value'), o.textContent])
    expect(sorts).toEqual([
      ['', 'Priority'], ['createdAt:desc', 'Newest first'], ['createdAt:asc', 'Oldest first'],
      ['slaDeadlineAt:asc', 'SLA deadline'], ['ticketType:asc', 'Type'], ['status:asc', 'Status'],
      ['senderEmail:asc', 'Sender'], ['aiConfidence:desc', 'Confidence'],
    ])
  })

  it.each([
    { initial: 'page=0&view=open', from: '/tickets', select: 'Assignee', pick: 'mine', to: '?assignee=mine&page=0', request: 'page=0&assignee=mine' },
    { initial: 'page=0&assignee=mine', from: '/tickets?assignee=mine', select: 'Assignee', pick: 'all', to: '?view=all&page=0', request: 'page=0&view=all' },
    { initial: 'page=0&status=CLOSED&assignee=mine', from: '/tickets?status=CLOSED&assignee=mine', select: 'Assignee', pick: 'all', to: '?status=CLOSED&page=0', request: 'page=0&status=CLOSED' },
    { initial: 'page=3&view=all', from: '/tickets?view=all&page=3', select: 'Assignee', pick: 'unassigned', to: '?page=0&assignee=unassigned', request: 'page=0&assignee=unassigned' },
    { initial: 'page=0&view=open', from: '/tickets', select: 'Status', pick: 'FAILED', to: '?status=FAILED&page=0', request: 'page=0&status=FAILED' },
    { initial: 'page=0&status=CLOSED', from: '/tickets?status=CLOSED', select: 'Status', pick: '', to: '?view=all&page=0', request: 'page=0&view=all' },
    { initial: 'page=0&status=CLOSED&assignee=mine', from: '/tickets?status=CLOSED&assignee=mine', select: 'Status', pick: '', to: '?assignee=mine&page=0', request: 'page=0&assignee=mine' },
    { initial: 'page=0&assignee=mine', from: '/tickets?assignee=mine', select: 'Status', pick: 'CLOSED', to: '?assignee=mine&status=CLOSED&page=0', request: 'page=0&status=CLOSED&assignee=mine' },
    { initial: 'page=2&view=open', from: '/tickets?page=2', select: 'Sort', pick: 'createdAt:asc', to: '?page=0&sort=createdAt&dir=asc', request: 'page=0&sort=createdAt&dir=asc&view=open' },
    { initial: 'page=0&sort=createdAt&dir=asc&view=open', from: '/tickets?sort=createdAt&dir=asc', select: 'Sort', pick: '', to: '?page=0', request: 'page=0&view=open' },
  ])('$from: $select → "$pick" goes to $to', async ({ initial, from, select, pick, to, request }) => {
    const user = userEvent.setup()
    routes[`GET /api/tickets?${initial}`] = () => json(page([ticket(1, 'Before the change')]))
    routes[`GET /api/tickets?${request}`] = () => json(page([ticket(2, 'After the change')]))

    renderAt(from)
    await screen.findByText('Before the change')
    await user.selectOptions(screen.getByLabelText(select), pick)

    expect(await screen.findByText('After the change')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/tickets')
    expect(window.location.search).toBe(to)
    expect(gets(`/api/tickets?${request}`)).toHaveLength(1)
  })
})

describe('TicketsListPage — paging', () => {
  it('pages forward and back from the server page, keeping the filters', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=1&status=CLOSED'] = () => json(page([ticket(1, 'Second page')], 1, 3, 120))
    routes['GET /api/tickets?page=2&status=CLOSED'] = () => json(page([ticket(2, 'Third page')], 2, 3, 120))
    routes['GET /api/tickets?page=0&status=CLOSED'] = () => json(page([ticket(3, 'First page')], 0, 3, 120))

    renderAt('/tickets?status=CLOSED&page=1')
    await screen.findByText('Second page')
    const pager = within(screen.getByRole('navigation', { name: 'Pages' }))
    expect(pager.getByText('Page 2 of 3 · 120 tickets')).toBeInTheDocument()
    expect(screen.getByText('120 tickets')).toBeInTheDocument()
    expect(pager.getByRole('button', { name: 'Previous' })).toBeEnabled()
    expect(pager.getByRole('button', { name: 'Next' })).toBeEnabled()

    await user.click(pager.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Third page')).toBeInTheDocument()
    expect(window.location.search).toBe('?status=CLOSED&page=2')
    expect(within(screen.getByRole('navigation', { name: 'Pages' })).getByRole('button', { name: 'Next' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText('Second page')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText('First page')).toBeInTheDocument()
    expect(window.location.search).toBe('?status=CLOSED&page=0')
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
  })

  it('pages on from the first page when the URL page is not a number', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'First page')], 0, 3, 120))
    routes['GET /api/tickets?page=1&view=open'] = () => json(page([ticket(2, 'Second page')], 1, 3, 120))

    renderAt('/tickets?page=abc')
    await screen.findByText('First page')
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Second page')).toBeInTheDocument()
    expect(window.location.search).toBe('?page=1')
  })

  it('hides the pager when everything fits on one page', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Only page')]))
    renderAt('/tickets')
    await screen.findByText('Only page')

    expect(screen.queryByRole('navigation', { name: 'Pages' })).not.toBeInTheDocument()
  })
})

describe('TicketsListPage — rows', () => {
  const OVERDUE = ticket(1, 'Overdue quote', {
    senderEmail: 'Jane Doe <jane@mail.acme-freight.co.uk>', urgency: 'HIGH', sentimentNegative: true, proactive: true,
    snippet: 'Hello, where is my quote?', slaBreached: true,
  })
  const MINE = ticket(2, 'My enquiry', { senderEmail: 'bob.smith@example.com', assigneeUserId: 'me', assigneeEmail: 'me@ops.com', ticketType: 'STATUS_UPDATE' })
  const CLOSED = ticket(3, 'Closed one', { status: 'CLOSED', assigneeUserId: 'u7', assigneeEmail: 'Ann Lee <ann@ops.com>' })
  const OTHERS = ticket(4, '', { status: 'AUTO_SENT', assigneeUserId: 'u8', ticketType: null })

  it('groups the server order into Overdue / Later / Done and renders each row', async () => {
    routes['GET /api/tickets?page=0&view=all'] = () => json(page([CLOSED, MINE, OVERDUE, OTHERS]))
    renderAt('/tickets?view=all')
    await screen.findByText('Overdue quote')

    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Overdue 1', 'Later 1', 'Done 2'])
    expect(within(screen.getByRole('region', { name: 'Done' })).getAllByRole('link').map((a) => a.textContent))
      .toEqual(['Closed one', '(no subject)'])

    const overdue = within(rowOf('Overdue quote'))
    expect(overdue.getByText('JD')).toBeInTheDocument()
    expect(overdue.getByText('Jane Doe')).toHaveAttribute('title', 'jane@mail.acme-freight.co.uk')
    expect(overdue.getByText('Acme Freight')).toBeInTheDocument()
    expect(overdue.getByText('Quote')).toBeInTheDocument()
    expect(overdue.getByText('Urgent · upset')).toBeInTheDocument()
    expect(overdue.getByText('Proactive')).toHaveAttribute('title', 'Outbound update started by SmartFreight')
    expect(overdue.getByText('Hello, where is my quote?')).toBeInTheDocument()
    expect(overdue.getByText('Overdue')).toHaveClass('sf-sla', 'is-red')
    expect(overdue.getByText('Unassigned · draft ready')).toBeInTheDocument()
    expect(overdue.getByRole('button', { name: 'Claim' })).toBeEnabled()
    expect(overdue.queryByRole('button', { name: 'Release' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Overdue quote' })).toHaveAttribute('href', '/tickets/1')

    const mine = within(rowOf('My enquiry'))
    expect(mine.getByText('Bob Smith')).toBeInTheDocument()
    expect(mine.getByText('Status update')).toBeInTheDocument()
    expect(mine.getByText('No SLA')).toHaveClass('sf-sla', 'is-muted')
    expect(mine.getByText('You · draft ready')).toHaveAttribute('title', 'me@ops.com')
    expect(mine.getByRole('button', { name: 'Release' })).toBeEnabled()
    expect(mine.queryByRole('button', { name: 'Claim' })).not.toBeInTheDocument()
    expect(mine.queryByText('Proactive')).not.toBeInTheDocument()

    const closed = within(rowOf('Closed one'))
    expect(closed.getByText('Ann Lee · closed')).toBeInTheDocument()
    expect(closed.queryByRole('button')).not.toBeInTheDocument()
    expect(rowOf('Closed one').querySelector('.sf-sla')).toHaveClass('is-muted')

    const others = within(rowOf('(no subject)'))
    expect(others.getByText('Assigned · auto-sent')).toBeInTheDocument()
    expect(others.getByText('Unclassified')).toBeInTheDocument()
    expect(others.queryByRole('button')).not.toBeInTheDocument()
  })

  it('hands the ticket page the displayed queue and the way back', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=0&view=all'] = () => json(page([CLOSED, MINE, OVERDUE, OTHERS]))
    renderAt('/tickets?view=all')

    await user.click(await screen.findByRole('link', { name: 'My enquiry' }))

    expect(window.location.pathname).toBe('/tickets/2')
    expect(JSON.parse(screen.getByTestId('ticket-state').textContent ?? '')).toEqual({
      queue: { ids: [1, 2, 3, 4], back: '/tickets?view=all' },
    })
  })
})

describe('TicketsListPage — actions', () => {
  it('claims with an empty body and releases with none, reloading after each', async () => {
    const user = userEvent.setup()
    let assignee: string | null = null
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Pallet enquiry', { assigneeUserId: assignee })]))
    routes['POST /api/tickets/1/assign'] = () => { assignee = 'me'; return new Response(null, { status: 204 }) }
    routes['POST /api/tickets/1/unassign'] = () => { assignee = null; return new Response(null, { status: 204 }) }

    renderAt('/tickets')
    await user.click(await screen.findByRole('button', { name: 'Claim' }))
    await user.click(await screen.findByRole('button', { name: 'Release' }))

    expect(await screen.findByRole('button', { name: 'Claim' })).toBeInTheDocument()
    expect(posts()).toEqual([
      { method: 'POST', url: '/api/tickets/1/assign', body: '{}' },
      { method: 'POST', url: '/api/tickets/1/unassign', body: null },
    ])
    expect(gets('/api/tickets?page=0&view=open')).toHaveLength(3)
  })

  it('disables the row button while the claim is in flight and shows a failed claim', async () => {
    const user = userEvent.setup()
    const claim = held(() => json({ message: 'Already claimed by Ann' }, 409))
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Pallet enquiry'), ticket(2, 'Other')]))
    routes['POST /api/tickets/1/assign'] = claim.route

    renderAt('/tickets')
    await screen.findByText('Pallet enquiry')
    await user.click(within(rowOf('Pallet enquiry')).getByRole('button', { name: 'Claim' }))

    expect(within(rowOf('Pallet enquiry')).getByRole('button', { name: 'Claim' })).toBeDisabled()
    expect(within(rowOf('Other')).getByRole('button', { name: 'Claim' })).toBeEnabled()
    await claim.release()

    expect(await screen.findByText('Already claimed by Ann')).toHaveClass('alert-danger')
    expect(within(rowOf('Pallet enquiry')).getByRole('button', { name: 'Claim' })).toBeEnabled()
    expect(gets('/api/tickets?page=0&view=open')).toHaveLength(1)
  })

  it('reprocesses stuck tickets, shows the server summary and reloads the list', async () => {
    const user = userEvent.setup()
    const reprocess = held(() => new Response('Re-queued 3 tickets', { status: 200, headers: { 'Content-Type': 'text/plain' } }))
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Pallet enquiry')]))
    routes['POST /api/admin/reprocess'] = reprocess.route

    renderAt('/tickets')
    await screen.findByText('Pallet enquiry')
    await user.click(screen.getByRole('button', { name: 'Reprocess stuck' }))

    expect(screen.getByRole('button', { name: 'Reprocessing…' })).toBeDisabled()
    await reprocess.release()

    const notice = await screen.findByText('Re-queued 3 tickets')
    expect(notice).toHaveClass('alert-info')
    expect(screen.getByRole('button', { name: 'Reprocess stuck' })).toBeEnabled()
    await waitFor(() => expect(gets('/api/tickets?page=0&view=open')).toHaveLength(2))

    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText('Re-queued 3 tickets')).not.toBeInTheDocument()
  })

  it('falls back to a generic notice when reprocess answers without a body', async () => {
    const user = userEvent.setup()
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Pallet enquiry')]))
    routes['POST /api/admin/reprocess'] = () => new Response(null, { status: 204 })

    renderAt('/tickets')
    await screen.findByText('Pallet enquiry')
    await user.click(screen.getByRole('button', { name: 'Reprocess stuck' }))

    expect(await screen.findByText('Reprocess queued.')).toBeInTheDocument()
  })

  it('shows a failed reprocess as an error and clears the previous notice', async () => {
    const user = userEvent.setup()
    let fail = false
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([ticket(1, 'Pallet enquiry')]))
    routes['POST /api/admin/reprocess'] = () => (fail ? failure('Broker down')() : new Response('Re-queued 1 ticket'))

    renderAt('/tickets')
    await screen.findByText('Pallet enquiry')
    await user.click(screen.getByRole('button', { name: 'Reprocess stuck' }))
    await screen.findByText('Re-queued 1 ticket')
    fail = true
    await user.click(screen.getByRole('button', { name: 'Reprocess stuck' }))

    expect(await screen.findByText('Broker down')).toHaveClass('alert-danger')
    expect(screen.queryByText('Re-queued 1 ticket')).not.toBeInTheDocument()
    expect(screen.getByText('Pallet enquiry')).toBeInTheDocument()
  })

  it('shows the live badge', async () => {
    routes['GET /api/tickets?page=0&view=open'] = () => json(page([]))
    renderAt('/tickets')
    await screen.findByText('Nothing here — the queue is clear.')

    expect(screen.getByText('● Live')).toHaveAttribute('title', 'Live updates connected')
  })
})
