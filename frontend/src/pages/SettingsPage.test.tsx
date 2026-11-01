import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Settings } from '../types.ts'
import SettingsPage from './SettingsPage.tsx'

// Smoke tests that pin what the settings page loads, renders and sends today — the safety net for
// splitting it into section components (docs/refactoring.md, Stage 1.3). fetch is stubbed, so the
// real useApi runs: these assert the actual method, path, bearer token and JSON body.

// One stable object, like the real context value: a fresh one per render would rebuild useApi's
// client, re-run the page's load effect and wipe what the test has typed.
vi.mock('react-oidc-context', () => {
  const auth = { user: { access_token: 'test-token' }, signinRedirect: vi.fn() }
  return { useAuth: () => auth }
})

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
})

const posts = (url: string) => calls.filter((c) => c.method === 'POST' && c.url === url)

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
