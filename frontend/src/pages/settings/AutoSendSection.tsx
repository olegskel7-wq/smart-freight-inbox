import type { Settings } from '../../types.ts'
import { FeatureSwitch, SettingsCard } from './SettingsCard.tsx'
import { featureSaved, type SectionProps } from './useSettings.ts'

export default function AutoSendSection({ settings, form, onChange, save }: SectionProps) {
  const types = form.autoSendTypes
  const toggleType = (t: string) =>
    onChange({ autoSendTypes: types.includes(t) ? types.filter((x) => x !== t) : [...types, t] })

  return (
    <SettingsCard title="Auto-send" on={settings.autoSendEnabled}>
      <p className="text-muted small mb-3">
        When the pipeline is confident enough in a draft, send it <strong>without operator review</strong>.
        Confidence is computed from verifiable facts (type classified, identifier found in the backend,
        rate matched) and is shown on every ticket — watch the scores for a while before switching this on.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) =>
              api.post<Settings>('/api/settings/autosend', {
                enabled: form.autoSendEnabled,
                threshold: form.autoSendThreshold,
                types,
              }),
            (s) =>
              s.autoSendEnabled && s.autoSendTypes.length === 0
                ? { kind: 'warning', text: 'Auto-send is on but no ticket types are allowed — nothing will be sent automatically.' }
                : featureSaved('Auto-send settings', (saved) => saved.autoSendEnabled)(s),
          )
        }}
      >
        <FeatureSwitch id="autoSendEnabled" checked={form.autoSendEnabled}
                       onChange={(autoSendEnabled) => onChange({ autoSendEnabled })}>
          Enable automatic sending (no human in the loop for matching tickets)
        </FeatureSwitch>
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="autoSendThreshold">Confidence threshold</label>
          <input type="number" className="form-control" style={{ maxWidth: 160 }} id="autoSendThreshold"
                 min={0} max={1} step={0.05}
                 value={form.autoSendThreshold} onChange={(e) => onChange({ autoSendThreshold: e.target.value })} />
          <div className="form-text">
            0.0–1.0. A draft is auto-sent only when its confidence is at or above this value. Default 0.8 =
            identifier found in the backend AND a specific type assigned.
          </div>
        </div>
        <div className="mb-3">
          <span className="form-label fw-semibold d-block">Allowed ticket types</span>
          {settings.allTicketTypes.map((t) => (
            <div className="form-check form-check-inline" key={t}>
              <input className="form-check-input" type="checkbox" id={`autoType_${t}`}
                     checked={types.includes(t)} onChange={() => toggleType(t)} />
              <label className="form-check-label small" htmlFor={`autoType_${t}`}>{t}</label>
            </div>
          ))}
          <div className="form-text">
            Only these types may be auto-sent. STATUS_UPDATE is the safe default — factual answers backed
            by TMS data. Think twice before adding RFQ: that sends prices without a human check.
          </div>
        </div>
        <button type="submit" className="btn btn-primary btn-sm">Save auto-send settings</button>
      </form>
    </SettingsCard>
  )
}
