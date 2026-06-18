import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import type { User } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// React port of users.html (Phase 3.2). Operator management backed by Keycloak: list, add, reset
// password, delete (the API blocks deleting your own account). The Bootstrap modals are replaced
// with inline forms — an always-visible "Add operator" card and a per-row password field toggled
// open. Each mutation returns the refreshed list, which re-renders the table. The old Thymeleaf
// page stays live until cutover.
export default function UsersPage() {
  const api = useApi()

  const [users, setUsers] = useState<User[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // Add-operator form.
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  // Per-row password reset: the user id whose field is open + its value.
  const [pwForId, setPwForId] = useState<string | null>(null)
  const [pwValue, setPwValue] = useState('')

  const load = useCallback(async () => {
    setError(null)
    try {
      setUsers(await api.get<User[]>('/api/users'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load users')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const createUser = async (e: React.FormEvent) => {
    e.preventDefault()
    setMsg(null)
    try {
      setUsers(await api.post<User[]>('/api/users', { email, password, role: 'OPERATOR' }))
      setMsg({ kind: 'success', text: `User ${email.trim().toLowerCase()} created.` })
      setEmail('')
      setPassword('')
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Create failed' })
    }
  }

  const changePassword = async (id: string, e: React.FormEvent) => {
    e.preventDefault()
    setMsg(null)
    try {
      setUsers(await api.post<User[]>(`/api/users/${id}/password`, { newPassword: pwValue }))
      setMsg({ kind: 'success', text: 'Password updated.' })
      setPwForId(null)
      setPwValue('')
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Password change failed' })
    }
  }

  const deleteUser = async (id: string, userEmail: string) => {
    if (!window.confirm(`Delete user ${userEmail}?`)) return
    setMsg(null)
    try {
      setUsers(await api.del<User[]>(`/api/users/${id}`))
      setMsg({ kind: 'success', text: 'User deleted.' })
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Delete failed' })
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!users) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 860, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      <h5 className="mb-3">Operators</h5>

      {/* Add operator */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Add operator</div>
        <div className="card-body">
          <form onSubmit={createUser} className="row g-2 align-items-end">
            <div className="col-md-5">
              <label className="form-label small mb-1">Email</label>
              <input type="email" className="form-control form-control-sm" placeholder="operator@company.com"
                     value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div className="col-md-4">
              <label className="form-label small mb-1">
                Password <span className="text-muted">(min 8)</span>
              </label>
              <input type="password" className="form-control form-control-sm" minLength={8}
                     value={password} onChange={(e) => setPassword(e.target.value)} required />
            </div>
            <div className="col-md-3">
              <button type="submit" className="btn btn-primary btn-sm w-100">Create</button>
            </div>
          </form>
        </div>
      </div>

      {/* Operators table */}
      <div className="card shadow-sm mb-4">
        <div className="card-header">
          <span className="fw-semibold">Operators ({users.length})</span>
        </div>
        <div className="card-body p-0">
          {users.length === 0 ? (
            <p className="text-muted small p-3 mb-0">No users found.</p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">#</th>
                  <th>Email</th>
                  <th>Role</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {users.map((u, i) => (
                  <tr key={u.id}>
                    <td className="ps-3 text-muted small">{i + 1}</td>
                    <td>{u.email}</td>
                    <td>
                      <span className="badge text-bg-secondary">{u.role ?? 'OPERATOR'}</span>
                    </td>
                    <td className="text-end pe-3">
                      {pwForId === u.id ? (
                        <form onSubmit={(e) => void changePassword(u.id, e)}
                              className="d-inline-flex gap-1 align-items-center">
                          <input type="password" className="form-control form-control-sm" minLength={8}
                                 placeholder="New password" value={pwValue}
                                 onChange={(e) => setPwValue(e.target.value)} required autoFocus
                                 style={{ width: 160 }} />
                          <button type="submit" className="btn btn-primary btn-sm py-0">Save</button>
                          <button type="button" className="btn btn-outline-secondary btn-sm py-0"
                                  onClick={() => { setPwForId(null); setPwValue('') }}>
                            Cancel
                          </button>
                        </form>
                      ) : (
                        <div className="d-inline-flex gap-1">
                          <button type="button" className="btn btn-outline-secondary btn-sm py-0"
                                  onClick={() => { setPwForId(u.id); setPwValue('') }}>
                            Change password
                          </button>
                          <button type="button" className="btn btn-outline-danger btn-sm py-0"
                                  onClick={() => void deleteUser(u.id, u.email)}>
                            Delete
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
