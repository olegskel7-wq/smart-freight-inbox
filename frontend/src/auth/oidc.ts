import { WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts'
import type { AuthProviderProps } from 'react-oidc-context'

// Keycloak OIDC settings for the SPA (Phase 3.2). Authorization-code + PKCE against the
// `smartfreight-spa` public client; the resulting access token carries tenant_id + roles for
// the /api resource server. The authority must be reachable from the BROWSER — in dev that is
// http://keycloak:8081 via the `127.0.0.1 keycloak` hosts entry (same issuer the backend uses,
// so token `iss` matches on both sides).
const authority =
  import.meta.env.VITE_KEYCLOAK_AUTHORITY ?? 'http://keycloak:8081/realms/smartfreight'
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
  // XSS mitigation (mandatory, Phase 3.2): tokens live in memory only — never localStorage or
  // sessionStorage. A page reload drops them; the SSO cookie at Keycloak then re-issues silently.
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true,
  // After the code exchange, strip ?code&state from the address bar.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname)
  },
}
