import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import { formatDateTime } from '../util/format.ts'
import type { Tenant } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// React port of admin-tenants.html (Phase 3.2). Platform admin (SUPER_ADMIN only — enforced by the
// /api/admin/tenants/** matcher) for tenant onboarding: list every client organisation with its
// operator count, and create a new tenant + its first operator in one call. The Bootstrap modal is
// replaced with an always-visible "Add tenant" card. Create returns the refreshed list, which
// re-renders the table. The old Thymeleaf page stays live until cutover.
export default function TenantsPage() {
  const api = useApi()

  const [tenants, setTenants] = useState<Tenant[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // Add-tenant form.
  const [name, setName] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminPassword, setAdminPassword] = useState('')

  const load = useCallback(async () => {
    setError(null)
    try {
      setTenants(await api.get<Tenant[]>('/api/admin/tenants'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load tenants')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const createTenant = async (e: React.FormEvent) => {
    e.preventDefault()
    setMsg(null)
    try {
      setTenants(await api.post<Tenant[]>('/api/admin/tenants', { name, adminEmail, adminPassword }))
      setMsg({
        kind: 'success',
        text: `Tenant '${name.trim()}' created with operator ${adminEmail.trim().toLowerCase()}.`,
      })
      setName('')
      setAdminEmail('')
      setAdminPassword('')
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Create failed' })
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!tenants) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 860, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      <h5 className="mb-3">Tenants</h5>

      {/* Add tenant */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Add tenant</div>
        <div className="card-body">
          <form onSubmit={createTenant} className="row g-2 align-items-end">
            <div className="col-md-4">
              <label className="form-label small mb-1">Organisation name</label>
              <input type="text" className="form-control form-control-sm" placeholder="Acme Logistics Ltd"
                     value={name} onChange={(e) => setName(e.target.value)} required />
            </div>
            <div className="col-md-4">
              <label className="form-label small mb-1">First operator email</label>
              <input type="email" className="form-control form-control-sm" placeholder="operator@acme.co.uk"
                     value={adminEmail} onChange={(e) => setAdminEmail(e.target.value)} required />
            </div>
            <div className="col-md-2">
              <label className="form-label small mb-1">
                Password <span className="text-muted">(min 8)</span>
              </label>
              <input type="password" className="form-control form-control-sm" minLength={8}
                     value={adminPassword} onChange={(e) => setAdminPassword(e.target.value)} required />
            </div>
            <div className="col-md-2">
              <button type="submit" className="btn btn-primary btn-sm w-100">Create</button>
            </div>
          </form>
        </div>
      </div>

      {/* Tenants table */}
      <div className="card shadow-sm mb-4">
        <div className="card-header">
          <span className="fw-semibold">Tenants ({tenants.length})</span>
        </div>
        <div className="card-body p-0">
          {tenants.length === 0 ? (
            <p className="text-muted small p-3 mb-0">No tenants found.</p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">#</th>
                  <th>Name</th>
                  <th>Users</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody>
                {tenants.map((t) => (
                  <tr key={t.id}>
                    <td className="ps-3 text-muted small">{t.id}</td>
                    <td>{t.name}</td>
                    <td><span className="badge text-bg-secondary">{t.userCount}</span></td>
                    <td className="small text-nowrap text-muted">{formatDateTime(t.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="card shadow-sm border-0 bg-light">
        <div className="card-body small text-muted">
          Creating a tenant also creates its first operator account. Settings, rates and users are
          managed from inside the tenant after that operator logs in. There is no tenant deletion by
          design — removing a tenant owns tickets, audit history and rates, which is a data-retention
          decision rather than a button.
        </div>
      </div>
    </div>
  )
}
