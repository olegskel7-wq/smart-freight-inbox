import { useCallback, useEffect, useRef, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import { formatDateTime } from '../util/format.ts'
import type { DocumentsResponse } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

function formatSize(bytes: number): string {
  return `${(bytes / 1024).toFixed(1)} KB`
}

// React port of documents.html (Phase 3.2). Per-tenant document store: upload a file against a
// shipment identifier + type, download (bearer-authenticated blob), delete. Each mutation returns the
// refreshed snapshot, which re-renders the table. The old Thymeleaf page stays live until cutover.
export default function DocumentsPage() {
  const api = useApi()

  const [data, setData] = useState<DocumentsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // Upload form state.
  const [identifier, setIdentifier] = useState('')
  const [docType, setDocType] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const res = await api.get<DocumentsResponse>('/api/documents')
      setData(res)
      // Default the type dropdown to the first option once the catalogue is known.
      setDocType((prev) => prev || res.docTypes[0]?.value || '')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load documents')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const upload = async (e: React.FormEvent) => {
    e.preventDefault()
    const file = fileInput.current?.files?.[0]
    if (!file) {
      setMsg({ kind: 'danger', text: 'Please choose a file to upload.' })
      return
    }
    setMsg(null)
    const form = new FormData()
    form.append('file', file)
    form.append('identifier', identifier)
    form.append('docType', docType)
    try {
      const res = await api.upload<DocumentsResponse>('/api/documents/upload', form)
      setData(res)
      const label = res.docTypes.find((t) => t.value === docType)?.label ?? docType
      setMsg({ kind: 'success', text: `Stored ${label} for ${identifier.trim()}.` })
      setIdentifier('')
      if (fileInput.current) fileInput.current.value = ''
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Upload failed' })
    }
  }

  const download = async (id: number, fallbackName: string) => {
    try {
      const { blob, filename } = await api.download(`/api/documents/${id}/download`)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename || fallbackName
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Download failed' })
    }
  }

  const deleteDoc = async (id: number) => {
    if (!window.confirm('Delete this document?')) return
    setMsg(null)
    try {
      setData(await api.del<DocumentsResponse>(`/api/documents/${id}`))
      setMsg({ kind: 'success', text: 'Document deleted.' })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Delete failed' })
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  // enum name → human label for the table (DocumentRef carries the enum name only).
  const labelOf = (value: string | null) =>
    data.docTypes.find((t) => t.value === value)?.label ?? value ?? '—'

  return (
    <div style={{ maxWidth: 960, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      <div className="d-flex justify-content-between align-items-center mb-4">
        <h5 className="mb-0">Document Store</h5>
        {!data.featureEnabled && (
          <span className="badge text-bg-warning">
            feature.document-request is disabled — document enquiries use the generic reply
          </span>
        )}
      </div>

      {/* Upload */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Upload document</div>
        <div className="card-body">
          <p className="text-muted small mb-2">
            Store a document against the shipment identifier it relates to (e.g. a container number).
            When a customer asks for that document, the AI attaches it to the reply automatically.
          </p>
          <form onSubmit={upload} className="row g-2 align-items-end">
            <div className="col-md-4">
              <label className="form-label small mb-1">Shipment identifier</label>
              <input type="text" className="form-control form-control-sm" placeholder="MSCU1234567"
                     value={identifier} onChange={(e) => setIdentifier(e.target.value)} required />
            </div>
            <div className="col-md-3">
              <label className="form-label small mb-1">Type</label>
              <select className="form-select form-select-sm" value={docType}
                      onChange={(e) => setDocType(e.target.value)} required>
                {data.docTypes.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div className="col-md-3">
              <label className="form-label small mb-1">File</label>
              <input ref={fileInput} type="file" className="form-control form-control-sm" required />
            </div>
            <div className="col-md-2">
              <button type="submit" className="btn btn-primary btn-sm w-100">Upload</button>
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
              No documents yet. Upload a file to let the AI fulfil document requests automatically.
            </p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">Identifier</th>
                  <th>Type</th>
                  <th>File</th>
                  <th className="text-end">Size</th>
                  <th>Uploaded</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.documents.map((d) => (
                  <tr key={d.id}>
                    <td className="ps-3">{d.identifier}</td>
                    <td>{labelOf(d.docType)}</td>
                    <td>
                      <button type="button" className="btn btn-link btn-sm p-0 align-baseline"
                              onClick={() => void download(d.id, d.filename)}>
                        {d.filename}
                      </button>
                    </td>
                    <td className="text-end">{formatSize(d.fileSize)}</td>
                    <td>{formatDateTime(d.uploadedAt)}</td>
                    <td className="text-end pe-3">
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
          <p className="fw-semibold text-body mb-1">How documents reach the customer</p>
          When an enquiry is classified as a document request, the AI determines which document is
          wanted and looks it up here by the extracted shipment identifier. If a matching document is
          on file it is attached to the reply draft; otherwise the ticket is flagged for the operator,
          who can attach an available document from the ticket page before sending.
        </div>
      </div>
    </div>
  )
}
