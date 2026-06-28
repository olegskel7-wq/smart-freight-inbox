import type { ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useRoles } from '../auth/roles.ts'

// Shared chrome for the SPA. Phase 3 UI refresh: the old top navbar is now a left sidebar
// (reads as a product-grade SaaS dashboard). Role gating (Users/Settings = ADMIN, Tenants =
// SUPER_ADMIN) is unchanged — the API enforces it regardless, this only drives nav visibility.
// Icons are inline SVG (Feather/Tabler paths) so there is no icon-font dependency and no CDN.

function Icon({ d }: { d: string[] }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {d.map((p) => <path key={p} d={p} />)}
    </svg>
  )
}

// Icon path sets (24×24 stroke).
const I = {
  tickets: ['M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z', 'M22 12h-6l-2 3h-4l-2-3H2'],
  analytics: ['M12 20V10', 'M18 20V4', 'M6 20v-4'],
  rates: ['M20.59 13.41 13.42 20.58a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z', 'M7 7h.01'],
  documents: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M16 13H8', 'M16 17H8', 'M10 9H8'],
  learning: ['M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z', 'M22 3h-6a4 4 0 0 1-4 4v14a3 3 0 0 0 3-3h7z'],
  knowledge: ['M12 2a3 3 0 0 0-3 3v14a3 3 0 0 1 3-3 3 3 0 0 1 3 3V5a3 3 0 0 0-3-3z', 'M5 6a2 2 0 0 0-2 2v11a2 2 0 0 1 2-2h2', 'M19 6a2 2 0 0 1 2 2v11a2 2 0 0 0-2-2h-2'],
  users: ['M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M23 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  settings: ['M4 21v-7', 'M4 10V3', 'M12 21v-9', 'M12 8V3', 'M20 21v-5', 'M20 12V3', 'M1 14h6', 'M9 8h6', 'M17 16h6'],
  webhooks: ['M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M8.59 13.51l6.83 3.98', 'M15.41 6.51l-6.82 3.98'],
  tenants: ['M3 21h18', 'M5 21V7l8-4v18', 'M19 21V11l-6-3', 'M9 9v.01', 'M9 12v.01', 'M9 15v.01', 'M9 18v.01'],
}

function navClass({ isActive }: { isActive: boolean }) {
  return 'sf-navlink' + (isActive ? ' active' : '')
}

export default function Layout() {
  const auth = useAuth()
  const profile = auth.user?.profile as { preferred_username?: string } | undefined
  const username = profile?.preferred_username ?? 'operator'
  const { isAdmin, isSuperAdmin } = useRoles()
  const roleLabel = isSuperAdmin ? 'SUPER_ADMIN' : isAdmin ? 'ADMIN' : 'OPERATOR'
  const initials = username.slice(0, 2).toUpperCase()

  const link = (to: string, label: string, icon: ReactNode) => (
    <NavLink to={to} className={navClass}>
      {icon}<span>{label}</span>
    </NavLink>
  )

  return (
    <div className="sf-shell">
      <aside className="sf-aside">
        <div className="sf-brand">
          <span className="sf-brand-mark">SF</span>
          <span>SmartFreight</span>
        </div>

        <nav className="sf-nav">
          {link('/tickets', 'Tickets', <Icon d={I.tickets} />)}
          {link('/analytics', 'Analytics', <Icon d={I.analytics} />)}
          {link('/rates', 'Rates', <Icon d={I.rates} />)}
          {link('/documents', 'Documents', <Icon d={I.documents} />)}
          {link('/training-examples', 'Learning', <Icon d={I.learning} />)}
          {link('/knowledge', 'Knowledge', <Icon d={I.knowledge} />)}

          {isAdmin && <div className="sf-group">Admin</div>}
          {isAdmin && link('/users', 'Users', <Icon d={I.users} />)}
          {isAdmin && link('/settings', 'Settings', <Icon d={I.settings} />)}
          {isAdmin && link('/webhooks', 'Webhooks', <Icon d={I.webhooks} />)}
          {isSuperAdmin && link('/tenants', 'Tenants', <Icon d={I.tenants} />)}
        </nav>

        <div className="sf-user">
          <span className="sf-avatar">{initials}</span>
          <span className="sf-user-meta">
            <span className="sf-user-name">{username}</span>
            <span className="sf-user-role">{roleLabel}</span>
          </span>
          <button
            type="button"
            className="btn btn-sm btn-outline-light sf-logout"
            title="Log out"
            aria-label="Log out"
            onClick={() => void auth.signoutRedirect()}
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"
              strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <path d="M16 17l5-5-5-5" />
              <path d="M21 12H9" />
            </svg>
          </button>
        </div>
      </aside>

      <main className="sf-main">
        <div className="container-fluid py-4">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
