import { useCallback, useEffect, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { useApi, ApiError } from '../api/useApi.ts'
import { useOperators } from '../api/useOperators.ts'
import { useRoles } from '../auth/roles.ts'
import type { Page, TicketDetail, TicketSummary } from '../types.ts'
import { formatDateTime, formatDateTimeShort, slaInfo, statusBadgeClass } from '../util/format.ts'
import {
  confidenceLabel, docTypeLabel, initialsOf, isDone, senderParts, statusLabel, toneLabel, typeLabel,
} from '../util/labels.ts'
import { groupTickets, queueFromState } from '../util/queue.ts'
import { useNow } from '../util/useNow.ts'
import type { TicketQueue } from '../util/queue.ts'

// Variant C (Phase 3.2 UI): a conversation layout — the customer email and the reply composer in a
// centred thread, ticket facts in a right-hand context rail. The composer's action bar is sticky so
// Send is always reachable; "Send and next" (Ctrl+Enter) walks the queue the list handed over.

const SPARK = 'M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z'

function Glyph({ d, size = 16 }: { d: string[]; size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {d.map((p) => <path key={p} d={p} />)}
    </svg>
  )
}

export default function TicketDetailPage() {
  const { id } = useParams<{ id: string }>()
  const ticketId = Number(id)
  const api = useApi()
  const auth = useAuth()
  const myId = auth.user?.profile.sub
  const { isAdmin } = useRoles()
  const navigate = useNavigate()
  const location = useLocation()

  const [ticket, setTicket] = useState<TicketDetail | null>(null)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // A tenant admin can assign to any teammate; non-admins can't read /api/users (403), so skip the fetch.
  const operators = useOperators(isAdmin)
  const [assignTarget, setAssignTarget] = useState('')
  const [docTarget, setDocTarget] = useState('')
  const now = useNow()

  // The queue this ticket was opened from. A deep link (no router state) falls back to the first
  // page of the Inbox, so Send and next still has somewhere to go.
  const [queue, setQueue] = useState<TicketQueue | null>(() => queueFromState(location.state))
  useEffect(() => {
    if (queue) return
    void api.get<Page<TicketSummary>>('/api/tickets?view=open')
      .then((p) => setQueue({
        ids: groupTickets(p.content, Date.now()).flatMap((g) => g.rows.map((r) => r.t.id)),
        back: '/tickets',
      }))
      .catch(() => setQueue({ ids: [], back: '/tickets' }))
  }, [api, queue])

  const index = queue ? queue.ids.indexOf(ticketId) : -1
  const prevId = index > 0 ? queue!.ids[index - 1] : null
  // After the current ticket; a ticket not in the queue (deep link) continues from the queue's start.
  const nextId = queue ? (index >= 0 ? queue.ids[index + 1] : queue.ids.find((x) => x !== ticketId)) ?? null : null
  const backTo = queue?.back ?? '/tickets'

  const go = useCallback((target: number | null) => {
    if (target == null) {
      navigate(backTo)
    } else {
      navigate(`/tickets/${target}`, { state: { queue } })
    }
  }, [navigate, backTo, queue])

  const load = useCallback(async () => {
    // Drop the previous ticket first: after prev/next the URL id has already changed, and a stale
    // draft must never be sendable against the new id (Ctrl+Enter) while the fetch is in flight.
    setTicket(null)
    setError(null)
    setNotice(null)
    try {
      const t = await api.get<TicketDetail>(`/api/tickets/${id}`)
      setTicket(t)
      setDraft(t.draftReply ?? '')
      setDocTarget('')
      setAssignTarget('')
    } catch (e) {
      setTicket(null)
      setError(e instanceof Error ? e.message : 'Failed to load ticket')
    }
  }, [api, id])

  useEffect(() => {
    void load()
  }, [load])

  // Runs one ticket mutation with the shared busy/error/notice handling.
  const act = async (fn: () => Promise<TicketDetail>, done: string, failed: string) => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      setTicket(await fn())
      setNotice(done)
    } catch (e) {
      setError(e instanceof Error ? e.message : failed)
    } finally {
      setBusy(false)
    }
  }

  const assign = (userId: string | null) =>
    act(() => api.post<TicketDetail>(`/api/tickets/${id}/assign`, userId ? { userId } : {}), 'Ticket assigned.', 'Assign failed')
  const unassign = () =>
    act(() => api.post<TicketDetail>(`/api/tickets/${id}/unassign`), 'Ticket released.', 'Release failed')
  const attach = (documentId: number | null) =>
    act(() => api.post<TicketDetail>(`/api/tickets/${id}/attach-document`, { documentId }),
      documentId ? 'Document attached.' : 'Attachment removed.', 'Attach failed')

  // CLOSED / AUTO_SENT are final; an IRRELEVANT ticket can still be answered by hand.
  const terminal = ticket?.status === 'CLOSED' || ticket?.status === 'AUTO_SENT'

  // save | close | send. Send and close move on: to the next ticket in the queue when asked
  // ("Send and next"), else back to the list.
  const submitDraft = async (action: 'save' | 'close' | 'send', advance = false) => {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.post<TicketDetail>(`/api/tickets/${id}/draft`, { action, draftReply: draft })
      if (action !== 'save') {
        go(advance ? nextId : null)
        return
      }
      setTicket(updated)
      setDraft(updated.draftReply ?? '')
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

  // Ctrl/Cmd+Enter in the reply box = Send and next. Scoped to the textarea on purpose: the same
  // keys in the assign/attach selects must never email the customer.
  const onReplyKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      if (!busy && draft.trim()) void submitDraft('send', true)
    }
  }

  const topBar = (
    <div className="sf-ticket-top">
      <Link to={backTo}>&larr; Back to list</Link>
      {ticket && <span className={`badge ${statusBadgeClass(ticket.status)}`}>{statusLabel(ticket.status)}</span>}
      <span className="flex-grow-1" />
      {queue && index >= 0 && <span>{index + 1} of {queue.ids.length}</span>}
      <button type="button" className="sf-nav-btn" aria-label="Previous ticket" title="Previous ticket"
        disabled={prevId == null} onClick={() => go(prevId)}>‹</button>
      <button type="button" className="sf-nav-btn" aria-label="Next ticket" title="Next ticket"
        disabled={nextId == null} onClick={() => go(nextId)}>›</button>
    </div>
  )

  if (error && !ticket) {
    return (
      <div className="sf-ws">
        {topBar}
        <div className="alert alert-danger mt-3">{error}</div>
      </div>
    )
  }
  if (!ticket) {
    return <div className="sf-ws">{topBar}<div className="text-muted mt-3">Loading…</div></div>
  }

  const sender = senderParts(ticket.senderEmail)
  const tone = toneLabel(ticket.urgency, ticket.sentimentNegative)
  const sla = isDone(ticket.status) ? null : slaInfo(ticket.slaDeadlineAt, ticket.slaBreached, now)
  const assignedToMe = ticket.assigneeUserId != null && ticket.assigneeUserId === myId
  const aiDrafted = ticket.aiOriginalDraft != null
  const isDocRequest = ticket.ticketType === 'DOCUMENT_REQUEST'

  return (
    <div className="sf-ws">
      {topBar}

      <div className="sf-ticket-grid">
        <div className="sf-thread">
          <h1 className="sf-thread-title">{ticket.subject || '(no subject)'}</h1>

          {error && <div className="alert alert-danger mb-0">{error}</div>}
          {notice && <div className="alert alert-success mb-0">{notice}</div>}

          <article className="sf-msg">
            <header className="sf-msg-head">
              <span className="sf-av" aria-hidden="true">{initialsOf(sender.name)}</span>
              <div className="sf-msg-from">
                <div className="fw-semibold">{ticket.proactive ? 'Proactive update' : sender.name}</div>
                <div className="sf-msg-meta">
                  {ticket.proactive ? `to ${sender.address}` : sender.address} · {formatDateTimeShort(ticket.createdAt)}
                </div>
              </div>
              <span className="flex-grow-1" />
              {tone && <span className="sf-tone">{tone}</span>}
            </header>
            <div className="sf-msg-body">{ticket.body || '(empty)'}</div>
            {ticket.attachmentText && (
              <details className="sf-msg-attach">
                <summary>Text read from the attachments</summary>
                <div className="sf-msg-body">{ticket.attachmentText}</div>
              </details>
            )}
          </article>

          {isDocRequest && (
            <section className="sf-docreq" aria-label="Document request">
              <div>
                Customer asked for: <strong>{docTypeLabel(ticket.requestedDocType)}</strong>
              </div>
              <div>
                Attached:{' '}
                {ticket.attachedDocument ? (
                  <>
                    <strong>{ticket.attachedDocument.filename}</strong>
                    {!terminal && (
                      <button type="button" className="btn btn-link btn-sm text-danger p-0 ms-2"
                        disabled={busy} onClick={() => void attach(null)}>
                        remove
                      </button>
                    )}
                  </>
                ) : (
                  <span className="text-muted">none</span>
                )}
              </div>
              {!terminal && ticket.availableDocuments.length > 0 && (
                <div className="d-flex align-items-center gap-2 flex-wrap">
                  <label htmlFor="attach-doc" className="visually-hidden">Document to attach</label>
                  <select id="attach-doc" className="form-select form-select-sm w-auto" value={docTarget}
                    onChange={(e) => setDocTarget(e.target.value)}>
                    <option value="">Choose a document…</option>
                    {ticket.availableDocuments.map((d) => (
                      <option key={d.id} value={d.id}>{docTypeLabel(d.docType)} — {d.filename}</option>
                    ))}
                  </select>
                  <button type="button" className="btn btn-outline-secondary btn-sm"
                    disabled={busy || !docTarget} onClick={() => void attach(Number(docTarget))}>
                    Attach
                  </button>
                </div>
              )}
            </section>
          )}

          <section className="sf-composer" aria-label="Your reply">
            <div className="sf-composer-head">
              <Glyph d={[SPARK]} />
              {ticket.status === 'AUTO_SENT' ? 'Sent automatically'
                : ticket.status === 'CLOSED' ? 'Closed'
                : aiDrafted ? 'Draft written for you — check it, edit if needed, send'
                  : 'Your reply'}
            </div>
            <label htmlFor="reply-draft" className="visually-hidden">Reply</label>
            <textarea
              id="reply-draft"
              className="sf-composer-text"
              value={draft}
              disabled={terminal || busy}
              placeholder={ticket.status === 'PROCESSING' || ticket.status === 'NEW' ? 'The AI draft is still being written…' : 'Write a reply…'}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={onReplyKeyDown}
            />
          </section>
          {!terminal && (
            <div className="sf-composer-bar">
              <button type="button" className="btn btn-link btn-sm text-secondary" disabled={busy}
                onClick={() => void submitDraft('close')}>
                Close without reply
              </button>
              <button type="button" className="btn btn-link btn-sm text-secondary" disabled={busy}
                onClick={() => void submitDraft('save')}>
                Save draft
              </button>
              <span className="flex-grow-1" />
              <span className="sf-kbd d-none d-md-inline">Ctrl+Enter</span>
              <button type="button" className="btn btn-outline-primary btn-sm" disabled={busy || !draft.trim()}
                onClick={() => void submitDraft('send')}>
                Send
              </button>
              <button type="button" className="btn btn-primary btn-sm" disabled={busy || !draft.trim()}
                title={nextId == null ? 'Send, then back to the list' : 'Send, then open the next ticket'}
                onClick={() => void submitDraft('send', true)}>
                Send and next
              </button>
            </div>
          )}
        </div>

        <aside className="sf-rail" aria-label="Ticket context">
          <div className="sf-rail-card">
            <div className="sf-rail-label">Ticket</div>
            <div>{typeLabel(ticket.ticketType)}{tone && <span className="sf-tone ms-2">{tone}</span>}</div>
            {sla && (
              <div className={`sf-sla is-${sla.tone}`} title={`SLA deadline ${formatDateTime(ticket.slaDeadlineAt)}`}>
                SLA · {sla.text}
              </div>
            )}
            <div className="text-muted small">Received {formatDateTime(ticket.createdAt)}</div>
            <div className="mt-2">
              {ticket.assigneeUserId
                ? (assignedToMe ? 'Assigned to you' : `Assigned to ${ticket.assigneeEmail ?? 'a teammate'}`)
                : 'Unassigned'}
              {' · '}
              {!ticket.assigneeUserId && (
                <button type="button" className="sf-linkbtn" disabled={busy} onClick={() => void assign(null)}>Claim</button>
              )}
              {ticket.assigneeUserId && (
                <button type="button" className="sf-linkbtn" disabled={busy} onClick={() => void unassign()}>Release</button>
              )}
              {ticket.assigneeUserId && !assignedToMe && (
                <> · <button type="button" className="sf-linkbtn" disabled={busy} onClick={() => void assign(null)}>Take it</button></>
              )}
            </div>
            {isAdmin && operators.length > 0 && (
              <div className="d-flex gap-2 mt-2">
                <label htmlFor="assign-to" className="visually-hidden">Assign to teammate</label>
                <select id="assign-to" className="form-select form-select-sm" value={assignTarget}
                  onChange={(e) => setAssignTarget(e.target.value)}>
                  <option value="">Assign to teammate…</option>
                  {operators.map((o) => (
                    <option key={o.id} value={o.id}>{o.email}</option>
                  ))}
                </select>
                <button type="button" className="btn btn-outline-secondary btn-sm" disabled={busy || !assignTarget}
                  onClick={() => void assign(assignTarget)}>
                  Assign
                </button>
              </div>
            )}
          </div>

          {/* TODO(grounding): the backend does not persist which sources grounded a draft (rate match,
              knowledge-base articles, few-shot corrections) — only the extracted identifier, the
              confidence score and an attached document are on the ticket. Showing those sources here
              needs a backend change (record them at draft time); until then show only what is real. */}
          <div className="sf-rail-card">
            <div className="sf-rail-label">The draft is based on</div>
            {aiDrafted || ticket.extractedIdentifier || ticket.attachedDocument ? (
              <ul className="sf-rail-list">
                {ticket.extractedIdentifier && (
                  <li>Reference <strong>{ticket.extractedIdentifier}</strong> found in the email ✓</li>
                )}
                {ticket.attachedDocument && (
                  <li>{docTypeLabel(ticket.attachedDocument.docType)} attached: {ticket.attachedDocument.filename} ✓</li>
                )}
                <li>Confidence {confidenceLabel(ticket.aiConfidence)}</li>
              </ul>
            ) : (
              <div className="text-muted">No AI draft for this ticket.</div>
            )}
          </div>

          <div className="sf-uk-note">
            <Glyph d={['M5 11h14v9H5z', 'M8 11V8a4 4 0 0 1 8 0v3']} />
            Drafted on your UK server
          </div>
        </aside>
      </div>
    </div>
  )
}
