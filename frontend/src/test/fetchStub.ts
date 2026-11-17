import { act } from '@testing-library/react'
import { vi } from 'vitest'

// A stubbed global fetch for page tests: the real useApi runs, so a test asserts the method, path,
// bearer token or body the page sent, and answers each request from a route table.

export type FetchRoute = () => Response | Promise<Response>

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

// A response the test releases by hand, to look at the page while the request is in flight.
export function held(response: () => Response) {
  let release = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { route: () => gate.then(response), release: () => act(async () => release()) }
}

// The bearer header and the string body a request carried, for a test's own record of it.
export const authOf = (init?: RequestInit) => new Headers(init?.headers).get('Authorization')
export const bodyOf = (init?: RequestInit) => (typeof init?.body === 'string' ? init.body : null)

// Stubs fetch to answer `${method} ${url}` from `routes` (read per request, so a test may add routes
// after the stub is in place) and 404 anything else. Every request is recorded, in the shape the test
// picks with `record`, in the returned list. Undo with vi.unstubAllGlobals().
export function stubFetch<C>(
  routes: Record<string, FetchRoute>,
  record: (method: string, url: string, init?: RequestInit) => C,
): C[] {
  const calls: C[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push(record(method, url, init))
    const route = routes[`${method} ${url}`]
    return route ? route() : json({ message: `unexpected ${method} ${url}` }, 404)
  }))
  return calls
}
