import type { Settings } from '../../types.ts'
import { FeatureSwitch, SettingsCard } from './SettingsCard.tsx'
import { featureSaved, type SectionProps } from './useSettings.ts'

export default function QuoteFollowUpSection({ settings, form, onChange, save }: SectionProps) {
  return (
    <SettingsCard title="Quote Follow-up Automation" on={settings.quoteFollowUpEnabled}>
      <p className="text-muted small mb-3">
        Chase silent quotes automatically. When a reply to an RFQ is sent, a one-shot follow-up is
        scheduled; if the customer has not been in touch after the delay below, a gentle "just following
        up on our quote" nudge is composed. Delivery reuses the <strong>auto-send</strong> gate above: RFQ
        is not auto-sent by default, so each nudge appears as a draft for review unless you allow-list RFQ
        for auto-send.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) =>
              api.post<Settings>('/api/settings/quotefollowup', {
                enabled: form.quoteFollowUpEnabled,
                delayHours: form.quoteFollowUpDelayHours,
              }),
            featureSaved('Quote follow-up automation', (s) => s.quoteFollowUpEnabled),
          )
        }}
      >
        <FeatureSwitch id="quoteFollowUpEnabled" checked={form.quoteFollowUpEnabled}
                       onChange={(quoteFollowUpEnabled) => onChange({ quoteFollowUpEnabled })}>
          Enable quote follow-ups
        </FeatureSwitch>
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="quoteFollowUpDelayHours">Delay (hours)</label>
          <input type="number" min={0} step={1} className="form-control" style={{ maxWidth: 200 }}
                 id="quoteFollowUpDelayHours"
                 value={form.quoteFollowUpDelayHours}
                 onChange={(e) => onChange({ quoteFollowUpDelayHours: e.target.value })} />
          <div className="form-text">
            Hours of customer silence after the quote was sent before a single nudge is sent (e.g.{' '}
            <code>48</code>). A reply from the customer cancels the nudge.
          </div>
        </div>
        <button type="submit" className="btn btn-primary btn-sm">Save quote follow-up settings</button>
      </form>
    </SettingsCard>
  )
}
