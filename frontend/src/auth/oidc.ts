import { WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts'
import type { AuthProviderProps } from 'react-oidc-context'

// Keycloak OIDC settings for the SPA (Phase 3.2). Authorization-code + PKCE against the
// `smartfreight-spa` public client; the resulting access token carries tenant_id + roles for
// the /api resource server. Keycloak is fronted by nginx on the SAME TLS origin as the SPA, so
// the authority is https://localhost/realms/smartfreight — same scheme/host as the page, no
// mixed-content block, one cert to trust, no `127.0.0.1 keycloak` hosts entry. The backend pins
// the matching issuer (token `iss` is this URL). Override with VITE_KEYCLOAK_AUTHORITY if needed.
const authority =
  import.meta.env.VITE_KEYCLOAK_AUTHORITY ?? 'https://localhost/realms/smartfreight'
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
    // Keep the router's own history state (key/idx/usr); only the one-time code/state params go.
    window.history.replaceState(window.history.state, document.title, window.location.pathname)
  },
}
