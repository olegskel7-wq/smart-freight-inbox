import type { Settings } from '../../types.ts'
import { SettingsCard } from './SettingsCard.tsx'
import type { SectionProps } from './useSettings.ts'

export default function PromptsSection({ form, onChange, save }: SectionProps) {
  return (
    <SettingsCard title="AI Prompts">
      <p className="text-muted small mb-3">
        Tune the prompts sent to the local LLM for each pipeline step. Changes take effect immediately —
        no restart needed. Use the placeholders below; they are filled in at request time:
      </p>
      <ul className="text-muted small mb-3">
        <li><code>{'{email}'}</code> — the customer's email body</li>
        <li><code>{'{itemContext}'}</code> — looked-up item data (drafting prompt only)</li>
        <li>
          <code>{'{rateContext}'}</code> — matched carrier rates with sell prices (drafting prompt only);
          without this placeholder matched rates are appended to the end of the prompt
        </li>
      </ul>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) => api.post<Settings>('/api/settings/prompts', {
              extractionPrompt: form.extractionPrompt,
              draftingPrompt: form.draftingPrompt,
            }),
            'AI prompts saved.',
          )
        }}
      >
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="extractionPrompt">Extraction prompt</label>
          <p className="text-muted small mb-2">Instructs the model to pull the key identifier out of the email.</p>
          <textarea className="form-control font-monospace small" id="extractionPrompt" rows={8}
                    value={form.extractionPrompt} onChange={(e) => onChange({ extractionPrompt: e.target.value })} />
        </div>
        <div className="mb-3">
          <label className="form-label fw-semibold" htmlFor="draftingPrompt">Drafting prompt</label>
          <p className="text-muted small mb-2">Instructs the model to write the reply draft using the looked-up data.</p>
          <textarea className="form-control font-monospace small" id="draftingPrompt" rows={12}
                    value={form.draftingPrompt} onChange={(e) => onChange({ draftingPrompt: e.target.value })} />
        </div>
        <button type="submit" className="btn btn-primary btn-sm">Save prompts</button>
      </form>
    </SettingsCard>
  )
}
