import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import PageHeader from '../components/PageHeader.tsx'
import { formatDateTime } from '../util/format.ts'
import type { KnowledgeResponse } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// Knowledge-base management page (Phase 3.9 — KB deflection). Operators store free-text knowledge
// (FAQs / policies / quoting rules); the pipeline retrieves the most relevant documents at draft time
// and grounds the reply with them. An always-visible card adds a new document; the same card edits an
// existing one when a row's Edit is clicked. Every mutation returns the refreshed snapshot, which
// re-renders the table. Mirrors the /rates + /training-examples content pages.
export default function KnowledgePage() {
  const api = useApi()

  const [data, setData] = useState<KnowledgeResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  const [editingId, setEditingId] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      setData(await api.get<KnowledgeResponse>('/api/knowledge'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load knowledge base')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const resetForm = () => {
    setEditingId(null)
    setTitle('')
    setContent('')
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setMsg(null)
    setSaving(true)
    try {
      const snapshot =
        editingId == null
          ? await api.post<KnowledgeResponse>('/api/knowledge', { title, content })
          : await api.put<KnowledgeResponse>(`/api/knowledge/${editingId}`, { title, content })
      setData(snapshot)
      setMsg({ kind: 'success', text: editingId == null ? 'Knowledge document added.' : 'Knowledge document updated.' })
      resetForm()
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Save failed' })
    } finally {
      setSaving(false)
    }
  }

  const startEdit = (id: number) => {
    const doc = data?.documents.find((d) => d.id === id)
    if (!doc) return
    setEditingId(id)
    setTitle(doc.title)
    setContent(doc.content)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const deleteDoc = async (id: number) => {
    if (!window.confirm('Delete this knowledge document?')) return
    setMsg(null)
    try {
      setData(await api.del<KnowledgeResponse>(`/api/knowledge/${id}`))
      if (editingId === id) resetForm()
      setMsg({ kind: 'success', text: 'Knowledge document deleted.' })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Delete failed' })
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      <PageHeader
        title="Knowledge base"
        subtitle="FAQs, policies and rules the AI uses to ground its replies"
      >
        {!data.featureEnabled && (
          <span className="badge text-bg-warning">
            feature.knowledge-base is disabled — knowledge is not used in drafts
          </span>
        )}
      </PageHeader>

      {/* Add / edit card — always visible */}
      <div className="card shadow-sm mb-4">
        <div className="card-header">
          <span className="fw-semibold">{editingId == null ? 'Add knowledge' : 'Edit knowledge'}</span>
        </div>
        <div className="card-body">
          <form onSubmit={(e) => void submit(e)}>
            <div className="mb-3">
              <label className="form-label small fw-semibold">Title</label>
              <input
                type="text"
                className="form-control"
                placeholder="e.g. Office opening hours"
                value={title}
                maxLength={255}
                onChange={(e) => setTitle(e.target.value)}
                required
              />
            </div>
            <div className="mb-3">
              <label className="form-label small fw-semibold">Content</label>
              <textarea
                className="form-control"
                rows={5}
                placeholder="The answer, policy or rule the AI should reference when relevant."
                value={content}
                onChange={(e) => setContent(e.target.value)}
                required
              />
            </div>
            <div className="d-flex gap-2">
              <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
                {editingId == null ? 'Add document' : 'Save changes'}
              </button>
              {editingId != null && (
                <button type="button" className="btn btn-outline-secondary btn-sm" onClick={resetForm}>
                  Cancel
                </button>
              )}
            </div>
          </form>
        </div>
      </div>

      {/* Documents table */}
      <div className="card shadow-sm mb-4">
        <div className="card-header">
          <span className="fw-semibold">Documents ({data.documents.length})</span>
        </div>
        <div className="card-body p-0">
          {data.documents.length === 0 ? (
            <p className="text-muted small p-3 mb-0">
              No knowledge documents yet. Add FAQs, policies or quoting rules above, and the AI will use
              them to answer typical "how / where / when" enquiries.
            </p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">Title</th>
                  <th>Content</th>
                  <th>Updated</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.documents.map((d) => (
                  <tr key={d.id}>
                    <td className="ps-3 fw-semibold" style={{ maxWidth: 220 }}>{d.title}</td>
                    <td className="small text-muted text-truncate" style={{ maxWidth: 420 }}>{d.content}</td>
                    <td className="small text-nowrap">{formatDateTime(d.updatedAt)}</td>
                    <td className="text-end pe-3 text-nowrap">
                      <button type="button" className="btn btn-outline-secondary btn-sm py-0 me-1"
                              onClick={() => startEdit(d.id)}>
                        Edit
                      </button>
                      <button type="button" className="btn btn-outline-secondary btn-sm py-0"
                              onClick={() => void deleteDoc(d.id)}>
                        ✕
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="card shadow-sm border-0 bg-light">
        <div className="card-body small text-muted">
          <p className="fw-semibold text-body mb-1">How the knowledge base reaches replies</p>
          Each document is embedded locally (the text never leaves the server). When a new enquiry comes
          in, the system finds the most relevant documents and shows them to the AI while it drafts, so
          it can answer questions that have no shipment or rate data behind them — opening hours,
          customs policy, how to book, and so on. Only documents close enough to the question are used,
          so off-topic entries are ignored.
        </div>
      </div>
    </div>
  )
}
