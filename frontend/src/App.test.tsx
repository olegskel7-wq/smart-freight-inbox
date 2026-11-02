import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'

// The auth gate: Loading… while Keycloak resolves, the error when sign-in failed, and one redirect
// to Keycloak when signed out — started after "Redirecting to sign in…" is on screen, never while
// App renders (the redirect updates AuthProvider's state, which React refuses during a render).

const oidc = vi.hoisted(() => ({ auth: null as unknown }))
vi.mock('react-oidc-context', () => ({ useAuth: () => oidc.auth }))

type AuthState = { isLoading?: boolean; isAuthenticated?: boolean; error?: Error }

function authWith(state: AuthState, signinRedirect = vi.fn(async () => {})) {
  oidc.auth = { isLoading: false, isAuthenticated: false, error: undefined, ...state, signinRedirect }
  return signinRedirect
}

const renderApp = () => render(<MemoryRouter initialEntries={['/tickets']}><App /></MemoryRouter>)

afterEach(() => {
  vi.restoreAllMocks()
})

describe('App — auth gate', () => {
  it('starts the Keycloak redirect after "Redirecting to sign in…" is rendered, not during the render', () => {
    const onScreen: boolean[] = []
    authWith({}, vi.fn(async () => {
      onScreen.push(document.body.textContent?.includes('Redirecting to sign in…') ?? false)
    }))

    renderApp()

    expect(screen.getByText('Redirecting to sign in…')).toBeInTheDocument()
    expect(onScreen).toEqual([true])
  })

  it('asks Keycloak to bring the operator back to the page they opened', () => {
    window.history.replaceState(null, '', '/analytics?days=7')
    const signinRedirect = authWith({})

    renderApp()

    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/analytics?days=7' } })
    window.history.replaceState(null, '', '/')
  })

  it('starts the redirect once under StrictMode, which runs the effect twice', () => {
    const signinRedirect = authWith({})

    render(<StrictMode><MemoryRouter initialEntries={['/tickets']}><App /></MemoryRouter></StrictMode>)

    expect(signinRedirect).toHaveBeenCalledOnce()
  })

  it('starts a new redirect when signed out again after the error is cleared', () => {
    const signinRedirect = authWith({})
    const { rerender } = renderApp()
    const again = () => rerender(<MemoryRouter initialEntries={['/tickets']}><App /></MemoryRouter>)

    authWith({ error: new Error('Network error') }, signinRedirect)
    again()
    expect(screen.getByText('Authentication error: Network error')).toBeInTheDocument()
    authWith({}, signinRedirect)
    again()

    expect(signinRedirect).toHaveBeenCalledTimes(2)
  })

  it.each([
    { name: 'while Keycloak is resolving', state: { isLoading: true }, shows: 'Loading…' },
    { name: 'after a sign-in error', state: { error: new Error('Invalid state') }, shows: 'Authentication error: Invalid state' },
  ])('does not redirect $name', ({ state, shows }) => {
    const signinRedirect = authWith(state)

    renderApp()

    expect(screen.getByText(shows)).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
  })
})
