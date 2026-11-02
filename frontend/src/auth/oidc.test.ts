import type { User } from 'oidc-client-ts'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { oidcConfig, returnPathOf, signinState } from './oidc.ts'

// Sign-in brings the operator back to the page they were on: the path is sent through the OIDC state
// and restored after the code exchange — only ever as a path on this origin, so a crafted state can't
// send the browser to another site.

const go = (path: string) => window.history.replaceState({ idx: 0 }, '', path)

afterEach(() => {
  go('/')
  vi.restoreAllMocks()
})

describe('signinState', () => {
  it('carries the current path, query and hash', () => {
    go('/tickets/42?queue=1#draft')

    expect(signinState()).toEqual({ state: { returnTo: '/tickets/42?queue=1#draft' } })
  })
})

describe('returnPathOf', () => {
  it.each([
    ['/tickets/42', '/tickets/42'],
    ['/analytics?days=7', '/analytics?days=7'],
    ['/settings#mailboxes', '/settings#mailboxes'],
    ['/', '/'],
  ])('keeps the same-origin path %s', (returnTo, path) => {
    expect(returnPathOf({ returnTo })).toBe(path)
  })

  it.each([
    ['a protocol-relative URL', '//evil.example.com/tickets'],
    ['a backslash host', '/\\evil.example.com'],
    ['an absolute URL', 'https://evil.example.com/tickets'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a relative path', 'tickets'],
    ['an empty string', ''],
    ['a number', 42],
  ])('rejects %s', (_name, returnTo) => {
    expect(returnPathOf({ returnTo })).toBeNull()
  })

  it.each([undefined, null, 'just a string', {}])('reads no path from state %s', (state) => {
    expect(returnPathOf(state)).toBeNull()
  })
})

describe('onSigninCallback', () => {
  const callback = (state: unknown) => oidcConfig.onSigninCallback?.({ state } as User)

  it('replaces the callback URL with the page the operator came from and tells the router', () => {
    go('/tickets?code=abc&state=xyz')
    const popped = vi.fn()
    window.addEventListener('popstate', popped, { once: true })

    void callback({ returnTo: '/tickets/42?queue=1#draft' })

    expect(window.location.pathname + window.location.search + window.location.hash).toBe('/tickets/42?queue=1#draft')
    expect(window.history.state).toEqual({ idx: 0 })
    expect(popped).toHaveBeenCalledOnce()
  })

  it.each([
    ['no state', undefined],
    ['an off-site returnTo', { returnTo: '//evil.example.com/' }],
  ])('with %s strips only the code and state from the callback URL', (_name, state) => {
    go('/tickets?code=abc&state=xyz')

    void callback(state)

    expect(window.location.href).toBe(`${window.location.origin}/tickets`)
  })
})
