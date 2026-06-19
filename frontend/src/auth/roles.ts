import { useAuth } from 'react-oidc-context'

// Reads the tenant role tiers off the Keycloak access token (Phase 3.3). The raw `roles` claim
// carries only what Keycloak assigned, so the SUPER_ADMIN ⇒ ADMIN implication (applied server-side
// in KeycloakJwtRoleConverter) is mirrored here explicitly: a platform admin is also a tenant admin.
// The API enforces these roles regardless — this only drives nav visibility and client-side guards.
export function useRoles(): { isAdmin: boolean; isSuperAdmin: boolean } {
  const auth = useAuth()
  const profile = auth.user?.profile as { roles?: string[] } | undefined
  const roles = Array.isArray(profile?.roles) ? profile.roles : []
  const isSuperAdmin = roles.includes('SUPER_ADMIN')
  const isAdmin = isSuperAdmin || roles.includes('ADMIN')
  return { isAdmin, isSuperAdmin }
}
