import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'

// Shared chrome for the SPA — replaces fragments/navbar.html. Pages already migrated to React
// are NavLinks (client-side routing under /app); pages still on Thymeleaf are plain anchors to
// their absolute server routes, so navigation keeps working across the half-migrated app.
export default function Layout() {
  const auth = useAuth()
  const profile = auth.user?.profile as { preferred_username?: string; roles?: string[] } | undefined
  const username = profile?.preferred_username ?? 'operator'
  const isSuperAdmin = Array.isArray(profile?.roles) && profile.roles.includes('SUPER_ADMIN')

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
            {/* Not yet migrated — full-page links to the Thymeleaf UI. */}
            <li className="nav-item"><a className="nav-link" href="/analytics">Analytics</a></li>
            <li className="nav-item"><a className="nav-link" href="/rates">Rates</a></li>
            <li className="nav-item"><a className="nav-link" href="/documents">Documents</a></li>
            <li className="nav-item"><a className="nav-link" href="/training-examples">Learning</a></li>
            <li className="nav-item"><a className="nav-link" href="/users">Users</a></li>
            <li className="nav-item"><a className="nav-link" href="/settings">Settings</a></li>
            {isSuperAdmin && (
              <li className="nav-item"><a className="nav-link" href="/admin/tenants">Tenants</a></li>
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
