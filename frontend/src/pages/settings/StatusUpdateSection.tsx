import type { Settings } from '../../types.ts'
import { FeatureSwitch, SettingsCard } from './SettingsCard.tsx'
import { featureSaved, type SectionProps } from './useSettings.ts'

export default function StatusUpdateSection({ settings, form, onChange, save }: SectionProps) {
  return (
    <SettingsCard title="Status Update Automation" on={settings.statusUpdateEnabled}>
      <p className="text-muted small mb-3">
        Proactively email customers when their shipment reaches a milestone — no need for them to ask. A
        subscription is created automatically whenever a customer enquires about a shipment; updates are
        sent until it is delivered. Delivery reuses the <strong>auto-send</strong> gate above: turn
        auto-send on for STATUS_UPDATE to send these automatically, otherwise they appear as drafts for review.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) =>
              api.post<Settings>('/api/settings/statusupdate', {
                enabled: form.statusUpdateEnabled,
                milestones: form.statusUpdateMilestones,
              }),
            featureSaved('Status-update automation', (s) => s.statusUpdateEnabled),
          )
        }}
      >
        <FeatureSwitch id="statusUpdateEnabled" checked={form.statusUpdateEnabled}
                       onChange={(statusUpdateEnabled) => onChange({ statusUpdateEnabled })}>
          Enable proactive status updates
        </FeatureSwitch>
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="statusUpdateMilestones">Milestone statuses</label>
          <input type="text" className="form-control" style={{ maxWidth: 360 }} id="statusUpdateMilestones"
                 value={form.statusUpdateMilestones}
                 onChange={(e) => onChange({ statusUpdateMilestones: e.target.value })} />
          <div className="form-text">
            Comma-separated backend status names that trigger a notification (e.g.{' '}
            <code>AT_PORT,RELEASED,DELIVERED</code>). Intermediate statuses stay silent so customers are
            not flooded.
          </div>
        </div>
        <button type="submit" className="btn btn-primary btn-sm">Save status-update settings</button>
      </form>
    </SettingsCard>
  )
}
