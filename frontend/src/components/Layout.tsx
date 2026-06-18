import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useRoles } from '../auth/roles.ts'

// Shared chrome for the SPA — replaces the old fragments/navbar.html. Every page is React now
// (the Thymeleaf UI was dropped at cutover), so all nav items are client-side NavLinks.
export default function Layout() {
  const auth = useAuth()
  const profile = auth.user?.profile as { preferred_username?: string } | undefined
  const username = profile?.preferred_username ?? 'operator'
  // Tenant-admin surfaces (Users/Settings) and the platform Tenants link are role-gated (Phase 3.3).
  const { isAdmin, isSuperAdmin } = useRoles()

  return (
    <>
      <nav className="navbar navbar-expand-lg navbar-dark bg-dark">
        <div className="container-fluid">
          <span className="navbar-brand">SmartFreight</span>
          <ul className="navbar-nav me-auto">
            <li className="nav-item">
              <NavLink
                to="/tickets"
                className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
              >
                Tickets
              </NavLink>
            </li>
            <li className="nav-item">
              <NavLink
                to="/analytics"
                className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
              >
                Analytics
              </NavLink>
            </li>
            <li className="nav-item">
              <NavLink
                to="/rates"
                className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
              >
                Rates
              </NavLink>
            </li>
            <li className="nav-item">
              <NavLink
                to="/documents"
                className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
              >
                Documents
              </NavLink>
            </li>
            <li className="nav-item">
              <NavLink
                to="/training-examples"
                className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
              >
                Learning
              </NavLink>
            </li>
            {/* Tenant-admin only — managing the team and tenant settings (the API enforces ADMIN). */}
            {isAdmin && (
              <li className="nav-item">
                <NavLink
                  to="/users"
                  className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
                >
                  Users
                </NavLink>
              </li>
            )}
            {isAdmin && (
              <li className="nav-item">
                <NavLink
                  to="/settings"
                  className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
                >
                  Settings
                </NavLink>
              </li>
            )}
            {/* Platform admin — only SUPER_ADMIN sees it (the API enforces the role regardless). */}
            {isSuperAdmin && (
              <li className="nav-item">
                <NavLink
                  to="/tenants"
                  className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
                >
                  Tenants
                </NavLink>
              </li>
            )}
          </ul>
          <span className="navbar-text text-light me-3">{username}</span>
          <button
            type="button"
            className="btn btn-outline-light btn-sm"
            onClick={() => void auth.signoutRedirect()}
          >
            Logout
          </button>
        </div>
      </nav>
      <main className="container-fluid py-4">
        <Outlet />
      </main>
    </>
  )
}
