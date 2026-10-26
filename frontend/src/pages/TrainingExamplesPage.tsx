import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import PageHeader from '../components/PageHeader.tsx'
import { formatDateTime } from '../util/format.ts'
import { typeLabel } from '../util/labels.ts'
import type { TrainingExamplesResponse } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// React port of training-examples.html (Phase 3.2). Captured operator corrections (the AI feedback
// loop): list + delete for dataset curation. The text previews are abbreviated server-side. Delete
// returns the refreshed snapshot, which re-renders the table. The old Thymeleaf page stays live
// until cutover.
export default function TrainingExamplesPage() {
  const api = useApi()

  const [data, setData] = useState<TrainingExamplesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setData(await api.get<TrainingExamplesResponse>('/api/training-examples'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load training examples')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const deleteExample = async (id: number) => {
    if (!window.confirm('Delete this training example?')) return
    setMsg(null)
    try {
      setData(await api.del<TrainingExamplesResponse>(`/api/training-examples/${id}`))
      setMsg({ kind: 'success', text: 'Training example deleted.' })
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
        title="AI training examples"
        subtitle="Operator corrections captured to improve future AI drafts"
      >
        {!data.featureEnabled && (
          <span className="badge text-bg-warning">
            feature.ai-feedback-loop is disabled — corrections are not being captured
          </span>
        )}
      </PageHeader>

      {/* Examples table */}
      <div className="card shadow-sm mb-4">
        <div className="card-header">
          <span className="fw-semibold">Captured corrections ({data.examples.length})</span>
        </div>
        <div className="card-body p-0">
          {data.examples.length === 0 ? (
            <p className="text-muted small p-3 mb-0">
              No corrections captured yet. When you edit an AI draft before sending, the change is
              stored here and used to improve future drafts.
            </p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">Type</th>
                  <th>Enquiry</th>
                  <th>AI draft</th>
                  <th>Operator sent</th>
                  <th>Captured</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.examples.map((e) => (
                  <tr key={e.id}>
                    <td className="ps-3">
                      <span className="badge text-bg-secondary">{typeLabel(e.ticketType)}</span>
                    </td>
                    <td className="small text-muted" style={{ maxWidth: 240 }}>{e.enquiryPreview}</td>
                    <td className="small" style={{ maxWidth: 260 }}>{e.aiDraftPreview}</td>
                    <td className="small" style={{ maxWidth: 260 }}>{e.operatorFinalPreview}</td>
                    <td className="small text-nowrap">{formatDateTime(e.createdAt)}</td>
                    <td className="text-end pe-3">
                      <button type="button" className="btn btn-outline-secondary btn-sm py-0"
                              onClick={() => void deleteExample(e.id)}>
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
          <p className="fw-semibold text-body mb-1">How the feedback loop works</p>
          Every time you edit an AI draft before sending, the change is captured as a training
          example — the customer's enquiry, the AI's original draft, and the reply you actually
          sent. The system finds the most similar past corrections and shows them to the AI when
          drafting new replies, so it gradually learns your style. Delete any example that should
          not be learned from. Trivial edits (a typo fix) are ignored.
        </div>
      </div>
    </div>
  )
}
