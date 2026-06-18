import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import Layout from './components/Layout.tsx'
import TicketsListPage from './pages/TicketsListPage.tsx'
import TicketDetailPage from './pages/TicketDetailPage.tsx'
import AnalyticsPage from './pages/AnalyticsPage.tsx'
import RatesPage from './pages/RatesPage.tsx'
import SettingsPage from './pages/SettingsPage.tsx'

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
        <Route path="tickets/:id" element={<TicketDetailPage />} />
        <Route path="analytics" element={<AnalyticsPage />} />
        <Route path="rates" element={<RatesPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/tickets" replace />} />
      </Route>
    </Routes>
  )
}
