import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Tenant } from '../types.ts'
import TenantsPage from './TenantsPage.tsx'
import { type FetchRoute, bodyOf, json, stubFetch } from '../test/fetchStub.ts'

// The AI column: the SUPER_ADMIN switches a tenant between the local and the cloud LLM.

const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))

type Call = { method: string; url: string; body: string | null }

const AT = '2026-10-01T09:30:00Z'
const tenant = (llmProvider: Tenant['llmProvider']): Tenant =>
  ({ id: 7, name: 'Felixstowe Freight Ltd', userCount: 2, createdAt: AT, llmProvider })

let calls: Call[]
let routes: Record<string, FetchRoute>

beforeEach(() => {
  oidc.auth = { user: { access_token: 'test-token', profile: { sub: 'me' } }, signinRedirect: vi.fn() }
  routes = { 'GET /api/admin/tenants': () => json([tenant('local')]) }
  calls = stubFetch(routes, (method, url, init) => ({ method, url, body: bodyOf(init) }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const select = () => screen.findByRole('combobox', { name: 'AI for Felixstowe Freight Ltd' })
const posts = () => calls.filter((c) => c.method === 'POST')

describe('TenantsPage — AI provider', () => {
  it('shows each tenant on its provider', async () => {
    render(<TenantsPage />)

    expect(await select()).toHaveProperty('value', 'local')
  })

  it('switches to cloud only after the DPA confirmation, and shows the new state', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    routes['POST /api/admin/tenants/7/llm-provider'] = () => json([tenant('cloud')])
    render(<TenantsPage />)

    await userEvent.selectOptions(await select(), 'cloud')

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('DPA'))
    expect(posts()).toEqual([{ method: 'POST', url: '/api/admin/tenants/7/llm-provider', body: '{"provider":"cloud"}' }])
    expect(await screen.findByText("'Felixstowe Freight Ltd' now runs on the cloud LLM.")).toBeTruthy()
    expect(await select()).toHaveProperty('value', 'cloud')
  })

  it('sends nothing when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<TenantsPage />)

    await userEvent.selectOptions(await select(), 'cloud')

    expect(posts()).toEqual([])
    expect(await select()).toHaveProperty('value', 'local')
  })

  it('switches back to local without asking, and shows a refusal from the server', async () => {
    const confirm = vi.spyOn(window, 'confirm')
    routes['GET /api/admin/tenants'] = () => json([tenant('cloud')])
    routes['POST /api/admin/tenants/7/llm-provider'] = () =>
      json({ error: 'CONFLICT', message: 'The cloud LLM is not configured' }, 409)
    render(<TenantsPage />)

    await userEvent.selectOptions(await select(), 'local')

    expect(confirm).not.toHaveBeenCalled()
    expect(posts()).toHaveLength(1)
    expect(await screen.findByText(/The cloud LLM is not configured/)).toBeTruthy()
  })
})
