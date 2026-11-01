import type { Settings } from '../../types.ts'
import { SettingsCard } from './SettingsCard.tsx'
import type { SectionProps } from './useSettings.ts'

// Per-tenant Ollama endpoint override; blank = the platform instance.
export default function AiEndpointSection({ settings, form, onChange, save }: SectionProps) {
  const url = form.ollamaUrlOverride
  return (
    <SettingsCard title="AI Endpoint">
      <p className="text-muted small mb-3">
        Where this tenant's LLM calls go. Leave blank to use the platform instance (
        <code>{settings.platformOllamaUrl}</code>). Point it at your own Ollama server to keep prompts and
        drafts entirely on your infrastructure — each endpoint gets its own circuit breaker, so an
        outage on one never affects others.
      </p>
      <form
        className="row g-2"
        onSubmit={(e) => {
          e.preventDefault()
          void save(
            (api) => api.post<Settings>('/api/settings/ollama', { ollamaBaseUrl: url }),
            url.trim() ? `Ollama endpoint saved: ${url.trim()}`
                       : 'Ollama endpoint override cleared — using the platform instance.',
          )
        }}
      >
        <div className="col-sm-8">
          <input type="text" className="form-control form-control-sm" placeholder={settings.platformOllamaUrl}
                 value={url} onChange={(e) => onChange({ ollamaUrlOverride: e.target.value })} />
        </div>
        <div className="col-sm-4">
          <button type="submit" className="btn btn-primary btn-sm">Save endpoint</button>
        </div>
      </form>
    </SettingsCard>
  )
}
