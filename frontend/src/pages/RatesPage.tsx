import { useCallback, useEffect, useRef, useState } from 'react'
import { useApi } from '../api/useApi.ts'
import PageHeader from '../components/PageHeader.tsx'
import type { RatesResponse, RateUploadResponse } from '../types.ts'

type Msg = { kind: 'success' | 'danger'; text: string } | null

// React port of rates.html (Phase 3.2). Carrier rate sheets: CSV upload, table, per-row + clear-all
// delete. The sell price and expiry are computed server-side (the margin never reaches the client).
// Each mutation returns the refreshed snapshot, which re-renders the table. The old Thymeleaf page
// stays live until cutover.
export default function RatesPage() {
  const api = useApi()

  const [data, setData] = useState<RatesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)
  // Skipped-row messages from the last import, shown separately so good rows still land.
  const [importErrors, setImportErrors] = useState<string[]>([])
  const fileInput = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setData(await api.get<RatesResponse>('/api/rates'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load rates')
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  const upload = async (e: React.FormEvent) => {
    e.preventDefault()
    const file = fileInput.current?.files?.[0]
    if (!file) {
      setMsg({ kind: 'danger', text: 'Please choose a CSV file to upload.' })
      return
    }
    setMsg(null)
    setImportErrors([])
    const form = new FormData()
    form.append('file', file)
    try {
      const res = await api.upload<RateUploadResponse>('/api/rates/upload', form)
      setData(res.snapshot)
      setImportErrors(res.errors)
      setMsg({
        kind: 'success',
        text: `Imported ${res.total} rate(s): ${res.inserted} new, ${res.updated} updated.`,
      })
      if (fileInput.current) fileInput.current.value = ''
    } catch (err) {
      setMsg({ kind: 'danger', text: err instanceof Error ? err.message : 'Import failed' })
    }
  }

  const mutate = async (call: () => Promise<RatesResponse>, ok: string) => {
    setMsg(null)
    try {
      setData(await call())
      setMsg({ kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: e instanceof Error ? e.message : 'Action failed' })
    }
  }

  const deleteRate = (id: number) =>
    mutate(() => api.del<RatesResponse>(`/api/rates/${id}`), 'Rate deleted.')

  const clearAll = () => {
    if (!window.confirm('Delete ALL rates?')) return
    void mutate(() => api.del<RatesResponse>('/api/rates'), 'All rates removed.')
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  return (
    <div style={{ maxWidth: 960, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={() => setMsg(null)} />
        </div>
      )}

      {importErrors.length > 0 && (
        <div className="alert alert-warning alert-dismissible">
          <p className="fw-semibold mb-1">Some rows were skipped:</p>
          <ul className="mb-0 small">
            {importErrors.map((err, i) => (
              <li key={i}>{err}</li>
            ))}
          </ul>
          <button type="button" className="btn-close" onClick={() => setImportErrors([])} />
        </div>
      )}

      <PageHeader
        title="Carrier rate sheets"
        subtitle="Buy rates and margin per carrier and route — the AI quotes the sell price in drafts"
      >
        {!data.featureEnabled && (
          <span className="badge text-bg-warning">
            feature.rate-sheets is disabled — rates are not used in drafts
          </span>
        )}
      </PageHeader>

      {/* CSV upload */}
      <div className="card shadow-sm mb-4">
        <div className="card-header fw-semibold">Upload CSV</div>
        <div className="card-body">
          <p className="text-muted small mb-2">
            Header row required:{' '}
            <code>carrier,origin,destination,rate,currency,margin_percent,valid_until</code>.{' '}
            <code>currency</code> (default GBP), <code>margin_percent</code> (default 0) and{' '}
            <code>valid_until</code> (<code>YYYY-MM-DD</code>, empty = no expiry) are optional.
            Re-uploading updates existing carrier + route rows in place.
          </p>
          <pre className="bg-light border rounded p-2 small mb-3">{`carrier,origin,destination,rate,currency,margin_percent,valid_until
Maersk,Felixstowe,Rotterdam,950.00,GBP,12.5,2026-12-31
DFDS,Immingham,Gothenburg,1180.00,GBP,10,`}</pre>
          <form onSubmit={upload} className="d-flex gap-2">
            <input ref={fileInput} type="file" className="form-control form-control-sm"
                   name="file" accept=".csv,text/csv" required />
            <button type="submit" className="btn btn-primary btn-sm text-nowrap">Upload</button>
          </form>
        </div>
      </div>

      {/* Rates table */}
      <div className="card shadow-sm mb-4">
        <div className="card-header d-flex justify-content-between align-items-center">
          <span className="fw-semibold">Rates ({data.rates.length})</span>
          {data.rates.length > 0 && (
            <button type="button" className="btn btn-outline-danger btn-sm" onClick={clearAll}>
              Clear all
            </button>
          )}
        </div>
        <div className="card-body p-0">
          {data.rates.length === 0 ? (
            <p className="text-muted small p-3 mb-0">
              No rates yet. Upload a CSV to let the AI quote real prices in reply drafts.
            </p>
          ) : (
            <table className="table table-sm table-hover align-middle mb-0">
              <thead className="table-light">
                <tr>
                  <th className="ps-3">Carrier</th>
                  <th>Origin</th>
                  <th>Destination</th>
                  <th className="text-end">Buy rate</th>
                  <th className="text-end">Margin %</th>
                  <th className="text-end">Sell price</th>
                  <th>Valid until</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.rates.map((r) => (
                  <tr key={r.id}>
                    <td className="ps-3">{r.carrier}</td>
                    <td>{r.origin}</td>
                    <td>{r.destination}</td>
                    <td className="text-end">{r.rate} {r.currency}</td>
                    <td className="text-end">{r.marginPercent}</td>
                    <td className="text-end fw-semibold">{r.sellPrice} {r.currency}</td>
                    <td>
                      {r.validUntil ? (
                        <span className={r.expired ? 'text-danger' : ''}>{r.validUntil}</span>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="text-end pe-3">
                      <button type="button" className="btn btn-outline-secondary btn-sm py-0"
                              onClick={() => void deleteRate(r.id)}>
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
          <p className="fw-semibold text-body mb-1">How rates reach the AI draft</p>
          When an enquiry (including parsed attachments) mentions both the origin and the destination
          of a stored, non-expired rate, the sell price is injected into the drafting prompt — the model
          quotes a real price instead of a placeholder. The margin itself is never shown to the model
          or the customer. Expired rates (red date) are kept for reference but never quoted.
        </div>
      </div>
    </div>
  )
}
