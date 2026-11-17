import { WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts'
import type { AuthProviderProps } from 'react-oidc-context'

// Keycloak OIDC settings for the SPA. Authorization-code + PKCE against the
// `smartfreight-spa` public client; the resulting access token carries tenant_id + roles for
// the /api resource server. Keycloak is fronted by nginx on the SAME TLS origin as the SPA, so
// the authority is this page's own origin + /realms/smartfreight — no mixed-content block, one cert
// to trust, and (Gate C3) nothing host-specific baked into the bundle: the same build serves
// https://localhost and the real domain, whose name lives only in PUBLIC_HOST on the server. The
// backend pins the matching issuer (token `iss` is this URL). Exception: the Vite dev server
// (npm run dev, http://localhost:5173) proxies only /api and /ws, so it talks to the local stack's
// Keycloak. VITE_KEYCLOAK_AUTHORITY overrides both.
export function authorityFor(origin: string, viteDevServer: boolean): string {
  return viteDevServer ? 'https://localhost/realms/smartfreight' : `${origin}/realms/smartfreight`
}

const authority =
  import.meta.env.VITE_KEYCLOAK_AUTHORITY ?? authorityFor(window.location.origin, import.meta.env.DEV)
const clientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'smartfreight-spa'

export const oidcConfig: AuthProviderProps = {
  authority,
  client_id: clientId,
  // Land the OIDC callback on /tickets, the SPA's home route (served from the root since the
  // cutover). The static chain permitAll()s it and SpaWebConfig hands back index.html, so React
  // Router resolves the route after the code exchange.
  redirect_uri: `${window.location.origin}/tickets`,
  post_logout_redirect_uri: `${window.location.origin}/tickets`,
  response_type: 'code',
  scope: 'openid profile email',
  // XSS mitigation (mandatory): tokens live in memory only — never localStorage or
  // sessionStorage. A page reload drops them; the SSO cookie at Keycloak then re-issues silently.
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true,
  // After the code exchange, go back to the page the sign-in started from (see signinState), or else
  // just strip ?code&state from the address bar.
  onSigninCallback: (user) => {
    const path = returnPathOf(user?.state) ?? window.location.pathname
    // Keep the router's own history state (key/idx/usr); only the address changes.
    window.history.replaceState(window.history.state, document.title, path)
    // BrowserRouter follows only the history it changed itself, or a popstate.
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  },
}

/**
 * The signinRedirect arguments that bring the operator back to the page they are on now — every
 * reload signs in again (tokens are in memory) and redirect_uri is always /tickets.
 */
export function signinState() {
  const { pathname, search, hash } = window.location
  return { state: { returnTo: pathname + search + hash } }
}

/** The path to return to from a sign-in state — only ever a path on this origin, else null. */
export function returnPathOf(state: unknown): string | null {
  const returnTo = (state as { returnTo?: unknown } | null | undefined)?.returnTo
  if (typeof returnTo !== 'string' || !returnTo.startsWith('/')) return null
  // "//host" and "/\host" parse as another host; resolving against this origin catches both. Dot
  // segments ("/.//host") can still normalise to a "//" path, which replaceState would read as a host.
  const url = new URL(returnTo, window.location.origin)
  if (url.origin !== window.location.origin || url.pathname.startsWith('//')) return null
  return url.pathname + url.search + url.hash
}
