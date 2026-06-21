import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  Tooltip,
} from 'chart.js'
import { Bar } from 'react-chartjs-2'
import { useApi } from '../api/useApi.ts'
import type { AnalyticsResponse, InboxAnswer } from '../types.ts'
import { statusBadgeClass } from '../util/format.ts'

// Chart.js is tree-shaken — register only the pieces the two bar charts use. Served from our own
// bundle, never a CDN: the product promise is that data never leaves the server.
ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip, Legend)

// Bucket i covers [i/10, (i+1)/10); 1.0 folds into the top bucket — parity with the template.
const CONFIDENCE_LABELS = [
  '0–.1', '.1–.2', '.2–.3', '.3–.4', '.4–.5',
  '.5–.6', '.6–.7', '.7–.8', '.8–.9', '.9–1',
]

const pct = (v: number) => v.toFixed(1) + '%'

export default function AnalyticsPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const days = Number(params.get('days') ?? '30')

  const [data, setData] = useState<AnalyticsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<InboxAnswer | null>(null)
  const [asking, setAsking] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      const result = await api.get<AnalyticsResponse>(`/api/analytics?days=${days}`)
      setData(result)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load analytics')
    }
  }, [api, days])

  useEffect(() => {
    void load()
  }, [load])

  const ask = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!question.trim()) return
    setAsking(true)
    try {
      setAnswer(await api.post<InboxAnswer>('/api/analytics/ask', { question }))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to ask')
    } finally {
      setAsking(false)
    }
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data) return <div className="text-muted">Loading…</div>

  const s = data.summary

  return (
    <div style={{ maxWidth: 960, margin: '0 auto' }}>
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h1 className="h5 mb-0">Inbox Analytics</h1>
        <div className="btn-group" role="group" aria-label="Reporting period">
          {data.allowedPeriods.map((p) => (
            <button
              key={p}
              type="button"
              className={`btn btn-sm ${p === data.days ? 'btn-primary' : 'btn-outline-primary'}`}
              onClick={() => setParams({ days: String(p) })}
            >
              {p} days
            </button>
          ))}
        </div>
      </div>

      {!data.featureEnabled && (
        <div className="alert alert-warning">
          <span className="fw-semibold">feature.analytics is disabled</span> — the dashboard is not
          available on this plan.
        </div>
      )}

      {data.featureEnabled && s && (
        <>
          {/* Ask your inbox (Phase 2.8) — metric-router over the figures below. */}
          {data.queryEnabled && (
            <div className="card shadow-sm mb-4">
              <div className="card-body">
                <label htmlFor="q" className="fw-semibold mb-2 d-block">Ask your inbox</label>
                <form onSubmit={ask} className="d-flex gap-2">
                  <input
                    type="text"
                    className="form-control"
                    id="q"
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    placeholder="e.g. How many RFQs did we get in the last 30 days?"
                    autoComplete="off"
                  />
                  <button type="submit" className="btn btn-primary" disabled={asking}>
                    {asking ? '…' : 'Ask'}
                  </button>
                  <button
                    type="button"
                    className="btn btn-outline-secondary"
                    onClick={() => { setQuestion(''); setAnswer(null) }}
                  >
                    Clear
                  </button>
                </form>
                {answer && (
                  <div className="mt-3">
                    <div className={`alert mb-0 ${answer.matched ? 'alert-primary' : 'alert-secondary'}`}>
                      <div style={{ whiteSpace: 'pre-line' }}>{answer.answer}</div>
                    </div>
                    {answer.matched && (
                      <div className="small text-muted mt-1">
                        Answered from <span className="fw-semibold">{answer.metric}</span> over{' '}
                        {answer.periodDays} days.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* KPI cards */}
          <div className="row g-3 mb-4">
            <Kpi label="Emails received" value={s.totalReceived}>
              {s.relevant} relevant · {s.irrelevant} junk ({pct(s.irrelevantPct)})
            </Kpi>
            <Kpi label="Replies sent" value={s.totalSends}>
              {s.manualSends} by operator · {s.autoSends} auto
            </Kpi>
            <Kpi
              label="Automation rate"
              value={pct(s.autoSendPct)}
              valueClass={s.autoSends > 0 ? 'text-success' : undefined}
            >
              replies sent without an operator
            </Kpi>
            <Kpi label="Avg response time" value={s.avgResponseFormatted}>
              receipt to first reply
            </Kpi>
            <Kpi
              label="SLA breaches"
              value={s.slaBreached}
              valueClass={s.slaBreached > 0 ? 'text-danger' : undefined}
            >
              {pct(s.slaBreachedPct)} of relevant tickets
            </Kpi>
            <Kpi
              label="AI draft acceptance"
              value={s.aiAcceptanceFormatted}
              valueClass={s.aiDraftsSent > 0 && s.aiAcceptancePct >= 50 ? 'text-success' : undefined}
            >
              {s.aiDraftsAccepted} sent as drafted · {s.aiDraftsEdited} edited
            </Kpi>
            <Kpi
              label="Quote → booking"
              value={s.quoteConversionFormatted}
              valueClass={s.quotesSent > 0 && s.quoteConversionPct >= 50 ? 'text-success' : undefined}
            >
              {s.quotesConverted} of {s.quotesSent} quotes booked
            </Kpi>
          </div>

          {/* Volume by day */}
          <div className="card shadow-sm mb-4">
            <div className="card-header fw-semibold">Email volume by day</div>
            <div className="card-body">
              {s.totalReceived === 0 ? (
                <p className="text-muted small mb-0">No tickets received in this period.</p>
              ) : (
                <div style={{ height: 260 }}>
                  <Bar
                    data={{
                      labels: s.volumeLabels,
                      datasets: [
                        { label: 'Relevant', data: s.volumeRelevant, backgroundColor: '#0d6efd' },
                        { label: 'Junk', data: s.volumeJunk, backgroundColor: '#adb5bd' },
                      ],
                    }}
                    options={{
                      responsive: true,
                      maintainAspectRatio: false,
                      scales: {
                        x: { stacked: true, ticks: { maxRotation: 0, autoSkip: true } },
                        y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } },
                      },
                      plugins: { legend: { position: 'bottom' } },
                    }}
                  />
                </div>
              )}
            </div>
          </div>

          <div className="row g-3 mb-4">
            {/* Tickets by type */}
            <div className="col-md-6">
              <div className="card shadow-sm h-100">
                <div className="card-header fw-semibold">Relevant tickets by type</div>
                <div className="card-body p-0">
                  {s.relevant === 0 ? (
                    <p className="text-muted small p-3 mb-0">No relevant tickets in this period.</p>
                  ) : (
                    <table className="table table-sm table-hover align-middle mb-0">
                      <tbody>
                        {s.byType.map((t) => (
                          <tr key={t.type}>
                            <td className="ps-3" style={{ width: 170 }}>
                              <span className="badge bg-primary">{t.type}</span>
                            </td>
                            <td className="text-end pe-3 fw-semibold" style={{ width: 70 }}>{t.count}</td>
                            <td><Bars pct={t.count > 0 ? t.pct : 0} /></td>
                          </tr>
                        ))}
                        {s.untyped > 0 && (
                          <tr>
                            <td className="ps-3">
                              <span className="badge bg-light text-dark border">Unclassified</span>
                            </td>
                            <td className="text-end pe-3 fw-semibold">{s.untyped}</td>
                            <td><Bars pct={s.untypedPct} /></td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            </div>

            {/* Confidence histogram */}
            <div className="col-md-6">
              <div className="card shadow-sm h-100">
                <div className="card-header fw-semibold">AI confidence distribution</div>
                <div className="card-body">
                  {s.confidenceCount === 0 ? (
                    <p className="text-muted small mb-0">
                      No drafts with a stored confidence in this period.
                    </p>
                  ) : (
                    <>
                      <div style={{ height: 220 }}>
                        <Bar
                          data={{
                            labels: CONFIDENCE_LABELS,
                            datasets: [{ label: 'Drafts', data: s.confidenceBins, backgroundColor: '#198754' }],
                          }}
                          options={{
                            responsive: true,
                            maintainAspectRatio: false,
                            scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
                            plugins: { legend: { display: false } },
                          }}
                        />
                      </div>
                      <p className="small text-muted mb-0 mt-2">
                        {s.confidenceCount} drafts scored — read against the auto-send threshold on
                        Settings.
                      </p>
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Status breakdown */}
          <div className="card shadow-sm mb-4">
            <div className="card-header fw-semibold">Tickets by status</div>
            <div className="card-body p-0">
              {s.totalReceived === 0 ? (
                <p className="text-muted small p-3 mb-0">No tickets received in this period.</p>
              ) : (
                <table className="table table-sm table-hover align-middle mb-0">
                  <tbody>
                    {s.byStatus.map((st) => (
                      <tr key={st.status}>
                        <td className="ps-3" style={{ width: 200 }}>
                          <span className={`badge ${statusBadgeClass(st.status)}`}>{st.status}</span>
                        </td>
                        <td className="text-end pe-3 fw-semibold" style={{ width: 100 }}>{st.count}</td>
                        <td><Bars pct={st.count > 0 ? st.pct : 0} /></td>
                        <td className="text-end pe-3" style={{ width: 80 }}>
                          {st.count > 0 && (
                            <Link
                              to={`/tickets?status=${st.status}`}
                              className="btn btn-sm btn-outline-secondary py-0"
                            >
                              View
                            </Link>
                          )}
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
              <p className="fw-semibold text-body mb-1">How these numbers are counted</p>
              Ticket cards count emails <em>received</em> in the selected period; reply cards
              (including average response time) count send events <em>recorded</em> in the period (a
              reply sent today on an older ticket counts as today's work). Response time runs from
              email receipt to the first reply on the ticket. SLA breaches are permanent — a late
              reply stays a breach in the history. Quote → booking is the share of RFQ quotes sent
              in the period that were later followed by a booking from the same customer (a quote
              sent recently may not have converted yet).
            </div>
          </div>
        </>
      )}
    </div>
  )
}

// A KPI card cell. `value` is rendered big; children are the small caption underneath.
function Kpi(props: {
  label: string
  value: string | number
  valueClass?: string
  children: React.ReactNode
}) {
  return (
    <div className="col-6 col-md">
      <div className="card shadow-sm h-100">
        <div className="card-body">
          <div className="text-muted small">{props.label}</div>
          <div className={`fs-2 fw-bold ${props.valueClass ?? ''}`}>{props.value}</div>
          <div className="small text-muted">{props.children}</div>
        </div>
      </div>
    </div>
  )
}

// The thin progress bar used in the type/status breakdown tables.
function Bars({ pct }: { pct: number }) {
  if (pct <= 0) return null
  return (
    <div className="progress" style={{ height: 8 }}>
      <div className="progress-bar bg-secondary" style={{ width: `${pct}%` }} />
    </div>
  )
}
