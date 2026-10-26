import { Routes, Route, Navigate, useParams } from 'react-router-dom'
import type { ReactElement } from 'react'
import { useAuth } from 'react-oidc-context'
import { useRoles } from './auth/roles.ts'
import Layout from './components/Layout.tsx'
import TicketsListPage from './pages/TicketsListPage.tsx'
import TicketDetailPage from './pages/TicketDetailPage.tsx'
import AnalyticsPage from './pages/AnalyticsPage.tsx'
import RatesPage from './pages/RatesPage.tsx'
import DocumentsPage from './pages/DocumentsPage.tsx'
import TrainingExamplesPage from './pages/TrainingExamplesPage.tsx'
import KnowledgePage from './pages/KnowledgePage.tsx'
import UsersPage from './pages/UsersPage.tsx'
import TenantsPage from './pages/TenantsPage.tsx'
import SettingsPage from './pages/SettingsPage.tsx'
import WebhooksPage from './pages/WebhooksPage.tsx'

// Client-side guard for tenant-admin pages (Phase 3.3): an operator who deep-links to /users or
// /settings is bounced to /tickets. The API enforces the ADMIN role regardless — this just avoids
// rendering a page whose every call would 403.
function RequireAdmin({ children }: { children: ReactElement }) {
  const { isAdmin } = useRoles()
  return isAdmin ? children : <Navigate to="/tickets" replace />
}

// One ticket page instance per ticket: prev/next and Send-and-next remount it, so no per-ticket
// state (draft, pickers, notices) carries over to the next ticket (Phase 3.2 UI, variant C).
function TicketDetailKeyed() {
  const { id } = useParams()
  return <TicketDetailPage key={id} />
}

// Auth gate: while Keycloak is resolving, show a spinner; if not signed in, redirect to
// Keycloak (the SSO cookie usually makes this instant). Tokens are in memory, so a reload
// re-runs this and silently re-authenticates.
export default function App() {
  const auth = useAuth()

  if (auth.isLoading) {
    return <div className="container py-5 text-center text-muted">Loading…</div>
  }
  if (auth.error) {
    return (
      <div className="container py-5">
        <div className="alert alert-danger">Authentication error: {auth.error.message}</div>
      </div>
    )
  }
  if (!auth.isAuthenticated) {
    void auth.signinRedirect()
    return <div className="container py-5 text-center text-muted">Redirecting to sign in…</div>
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/tickets" replace />} />
        <Route path="tickets" element={<TicketsListPage />} />
        <Route path="tickets/:id" element={<TicketDetailKeyed />} />
        <Route path="analytics" element={<AnalyticsPage />} />
        <Route path="rates" element={<RatesPage />} />
        <Route path="documents" element={<DocumentsPage />} />
        <Route path="training-examples" element={<TrainingExamplesPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="users" element={<RequireAdmin><UsersPage /></RequireAdmin>} />
        <Route path="tenants" element={<TenantsPage />} />
        <Route path="settings" element={<RequireAdmin><SettingsPage /></RequireAdmin>} />
        <Route path="webhooks" element={<RequireAdmin><WebhooksPage /></RequireAdmin>} />
        <Route path="*" element={<Navigate to="/tickets" replace />} />
      </Route>
    </Routes>
  )
}
