import type { Settings, User } from '../../types.ts'
import { FeatureSwitch, SettingsCard } from './SettingsCard.tsx'
import { featureSaved, type SectionProps } from './useSettings.ts'

// `operators` feeds the escalation-assignee dropdown; it is empty for a non-admin (/api/users 403s).
export default function SmartRoutingSection({ settings, form, onChange, save, operators }: SectionProps & {
  operators: User[]
}) {
  return (
    <SettingsCard title="Smart Routing" on={settings.smartRoutingEnabled}>
      <p className="text-muted small mb-3">
        The AI classifier also rates each incoming email's <strong>urgency</strong> and{' '}
        <strong>tone</strong> in the same step (no extra AI call). Urgent or negative emails get a
        tighter SLA window and float to the top of the queue. Optionally, auto-assign such escalated
        emails to a chosen operator — leave it blank to keep assignment manual.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) =>
              api.post<Settings>('/api/settings/routing', {
                smartRoutingEnabled: form.smartRoutingEnabled,
                escalationAssignee: form.escalationAssignee,
              }),
            (s) => featureSaved('Smart-routing settings', s.smartRoutingEnabled),
          )
        }}
      >
        <FeatureSwitch id="smartRoutingEnabled" checked={form.smartRoutingEnabled}
                       onChange={(smartRoutingEnabled) => onChange({ smartRoutingEnabled })}>
          Detect urgency &amp; sentiment
        </FeatureSwitch>
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="escalationAssignee">Escalation assignee</label>
          <select className="form-select" style={{ maxWidth: 360 }} id="escalationAssignee"
                  value={form.escalationAssignee} onChange={(e) => onChange({ escalationAssignee: e.target.value })}>
            <option value="">— None (leave unassigned) —</option>
            {operators.map((op) => (
              <option key={op.id} value={op.id}>{op.email}</option>
            ))}
          </select>
          <div className="form-text">
            Urgent or negative-tone emails are auto-assigned to this operator while still unclaimed.
            A manually assigned ticket is never reassigned.
          </div>
        </div>
        <button type="submit" className="btn btn-primary btn-sm">Save smart-routing settings</button>
      </form>
    </SettingsCard>
  )
}
