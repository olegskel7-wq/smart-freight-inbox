import type { Settings } from '../../types.ts'
import { FeatureSwitch, SettingsCard } from './SettingsCard.tsx'
import type { SectionProps } from './useSettings.ts'

export default function NotificationsSection({ form, onChange, save }: SectionProps) {
  return (
    <SettingsCard title="Notifications">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) => api.post<Settings>('/api/settings/notifications', { draftEnabled: form.draftEnabled }),
            `Notifications ${form.draftEnabled ? 'enabled' : 'disabled'}.`,
          )
        }}
      >
        <FeatureSwitch id="draftEnabled" checked={form.draftEnabled}
                       onChange={(draftEnabled) => onChange({ draftEnabled })}>
          Send email notification to operator when AI draft is ready
        </FeatureSwitch>
        <p className="text-muted small">
          When enabled, a notification with the draft is sent to the operator's inbox after each AI
          processing. Disable if you prefer to check drafts directly in the UI.
        </p>
        <button type="submit" className="btn btn-primary btn-sm">Save</button>
      </form>
    </SettingsCard>
  )
}
