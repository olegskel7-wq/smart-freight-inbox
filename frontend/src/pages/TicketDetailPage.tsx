import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useApi, ApiError } from '../api/useApi.ts'
import { useRoles } from '../auth/roles.ts'
import type { TicketDetail, User } from '../types.ts'
import { formatConfidence, formatDateTime, statusBadgeClass } from '../util/format.ts'

export default function TicketDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useApi()
  const auth = useAuth()
  const myId = auth.user?.profile.sub
  const { isAdmin } = useRoles()
  const navigate = useNavigate()

  const [ticket, setTicket] = useState<TicketDetail | null>(null)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [operators, setOperators] = useState<User[]>([])
  const [assignTarget, setAssignTarget] = useState('')

  const load = useCallback(async () => {
    setError(null)
    try {
      const t = await api.get<TicketDetail>(`/api/tickets/${id}`)
      setTicket(t)
      setDraft(t.draftReply ?? '')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load ticket')
    }
  }, [api, id])

  useEffect(() => {
    void load()
  }, [load])

  // A tenant admin can assign to any teammate, so load the operator list for the picker (the API
  // 403s for non-admins anyway; gating the fetch avoids a noisy console error for operators).
  useEffect(() => {
    if (!isAdmin) return
    void api.get<User[]>('/api/users').then(setOperators).catch(() => setOperators([]))
  }, [api, isAdmin])

  const assign = async (userId: string | null) => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const body = userId ? { userId } : {}
      const updated = await api.post<TicketDetail>(`/api/tickets/${id}/assign`, body)
      setTicket(updated)
      setNotice('Ticket assigned.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Assign failed')
    } finally {
      setBusy(false)
    }
  }

  const unassign = async () => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.post<TicketDetail>(`/api/tickets/${id}/unassign`)
      setTicket(updated)
      setNotice('Ticket released.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Release failed')
    } finally {
      setBusy(false)
    }
  }

  const terminal = ticket?.status === 'CLOSED' || ticket?.status === 'AUTO_SENT'

  const submitDraft = async (action: 'save' | 'close' | 'send') => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.post<TicketDetail>(`/api/tickets/${id}/draft`, { action, draftReply: draft })
      setTicket(updated)
      setDraft(updated.draftReply ?? '')
      if (action === 'send' || action === 'close') {
        navigate('/tickets')
        return
      }
      setNotice('Draft saved.')
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        setError('Please wait a few seconds before sending again.')
      } else if (e instanceof ApiError && e.status === 502) {
        setError('Failed to send email — the message was not delivered.')
      } else {
        setError(e instanceof Error ? e.message : 'Action failed')
      }
    } finally {
      setBusy(false)
    }
  }

  const attach = async (documentId: number | null) => {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<TicketDetail>(`/api/tickets/${id}/attach-document`, { documentId })
      setTicket(updated)
      setNotice(documentId ? 'Document attached.' : 'Attachment removed.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Attach failed')
    } finally {
      setBusy(false)
    }
  }

  if (error && !ticket) {
    return (
      <div>
        <Link to="/tickets">&larr; Back to tickets</Link>
        <div className="alert alert-danger mt-3">{error}</div>
      </div>
    )
  }
  if (!ticket) {
    return <div className="text-muted">Loading…</div>
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <Link to="/tickets">&larr; Back to tickets</Link>
        <span className={`badge ${statusBadgeClass(ticket.status)}`}>{ticket.status}</span>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <h1 className="h5">{ticket.subject || '(no subject)'}</h1>

      <dl className="row small text-muted mb-3">
        <dt className="col-sm-2">From</dt><dd className="col-sm-10">{ticket.senderEmail ?? '—'}</dd>
        <dt className="col-sm-2">Type</dt><dd className="col-sm-10">{ticket.ticketType ?? '—'}</dd>
        <dt className="col-sm-2">Identifier</dt><dd className="col-sm-10">{ticket.extractedIdentifier ?? '—'}</dd>
        <dt className="col-sm-2">Confidence</dt><dd className="col-sm-10">{formatConfidence(ticket.aiConfidence)}</dd>
        <dt className="col-sm-2">SLA deadline</dt>
        <dd className="col-sm-10">
          {formatDateTime(ticket.slaDeadlineAt)}
          {ticket.slaBreached && <span className="badge bg-danger ms-2">Breached</span>}
        </dd>
        <dt className="col-sm-2">Received</dt><dd className="col-sm-10">{formatDateTime(ticket.createdAt)}</dd>
      </dl>

      <div className="card mb-3">
        <div className="card-header d-flex justify-content-between align-items-center">
          <span>Assignment</span>
          <span>
            {ticket.assigneeUserId ? (
              ticket.assigneeUserId === myId
                ? <span className="badge bg-info text-dark">Assigned to you</span>
                : <span className="badge bg-secondary">{ticket.assigneeEmail ?? 'Assigned'}</span>
            ) : (
              <span className="badge bg-light text-dark border">Unassigned</span>
            )}
          </span>
        </div>
        <div className="card-body d-flex flex-wrap align-items-center gap-2">
          {!ticket.assigneeUserId && (
            <button type="button" className="btn btn-outline-primary btn-sm" disabled={busy} onClick={() => void assign(null)}>
              Claim
            </button>
          )}
          {ticket.assigneeUserId && (
            <button type="button" className="btn btn-outline-secondary btn-sm" disabled={busy} onClick={() => void unassign()}>
              Release
            </button>
          )}
          {ticket.assigneeUserId && ticket.assigneeUserId !== myId && (
            <button type="button" className="btn btn-outline-primary btn-sm" disabled={busy} onClick={() => void assign(null)}>
              Claim for me
            </button>
          )}
          {isAdmin && operators.length > 0 && (
            <div className="d-flex align-items-center gap-2 ms-auto">
              <select
                className="form-select form-select-sm"
                style={{ width: 'auto' }}
                value={assignTarget}
                onChange={(e) => setAssignTarget(e.target.value)}
              >
                <option value="">Assign to teammate…</option>
                {operators.map((o) => (
                  <option key={o.id} value={o.id}>{o.email} ({o.role ?? 'OPERATOR'})</option>
                ))}
              </select>
              <button
                type="button"
                className="btn btn-outline-secondary btn-sm"
                disabled={busy || !assignTarget}
                onClick={() => { if (assignTarget) void assign(assignTarget) }}
              >
                Assign
              </button>
            </div>
          )}
        </div>
      </div>

      <div className="card mb-3">
        <div className="card-header">Email body</div>
        <div className="card-body">
          <pre className="mb-0" style={{ whiteSpace: 'pre-wrap' }}>{ticket.body || '(empty)'}</pre>
        </div>
      </div>

      {ticket.attachmentText && (
        <div className="card mb-3">
          <div className="card-header">Attachment text</div>
          <div className="card-body">
            <pre className="mb-0" style={{ whiteSpace: 'pre-wrap' }}>{ticket.attachmentText}</pre>
          </div>
        </div>
      )}

      {ticket.ticketType === 'DOCUMENT_REQUEST' && (
        <div className="card mb-3">
          <div className="card-header">
            Document request{ticket.requestedDocType ? ` — ${ticket.requestedDocType}` : ''}
          </div>
          <div className="card-body">
            <p className="mb-2">
              Attached:{' '}
              {ticket.attachedDocument ? (
                <>
                  <strong>{ticket.attachedDocument.filename}</strong>
                  {!terminal && (
                    <button
                      type="button"
                      className="btn btn-link btn-sm text-danger p-0 ms-2"
                      disabled={busy}
                      onClick={() => void attach(null)}
                    >
                      remove
                    </button>
                  )}
                </>
              ) : (
                <span className="text-muted">none</span>
              )}
            </p>
            {!terminal && ticket.availableDocuments.length > 0 && (
              <div className="d-flex align-items-center gap-2">
                <select
                  className="form-select form-select-sm"
                  style={{ width: 'auto' }}
                  defaultValue=""
                  id="attach-doc-select"
                >
                  <option value="" disabled>Choose a document…</option>
                  {ticket.availableDocuments.map((d) => (
                    <option key={d.id} value={d.id}>{d.docType} — {d.filename}</option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn btn-outline-secondary btn-sm"
                  disabled={busy}
                  onClick={() => {
                    const sel = document.getElementById('attach-doc-select') as HTMLSelectElement
                    if (sel?.value) void attach(Number(sel.value))
                  }}
                >
                  Attach
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      <div className="card mb-3">
        <div className="card-header">Reply draft</div>
        <div className="card-body">
          <textarea
            className="form-control"
            rows={10}
            value={draft}
            disabled={terminal || busy}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
      </div>

      {!terminal && (
        <div className="d-flex gap-2">
          <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void submitDraft('save')}>
            Save
          </button>
          <button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={() => void submitDraft('close')}>
            Close without sending
          </button>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void submitDraft('send')}>
            Send reply
          </button>
        </div>
      )}
    </div>
  )
}
