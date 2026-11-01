import type { Settings } from '../../types.ts'
import { SettingsCard } from './SettingsCard.tsx'

// Read-only: the platform fallback SMTP account comes from environment variables.
export default function PlatformSmtpSection({ settings }: { settings: Settings }) {
  return (
    <SettingsCard title="Outgoing Mail (SMTP) — platform fallback">
      <p className="text-muted small mb-3">
        Replies are sent through this platform SMTP account unless a mailbox above has its own SMTP
        configured, in which case that tenant sends from its own address. These platform values come
        from environment variables.
      </p>
      <dl className="row mb-0">
        <dt className="col-sm-4">SMTP host</dt>
        <dd className="col-sm-8"><code>{settings.smtpHost}</code></dd>
        <dt className="col-sm-4">SMTP port</dt>
        <dd className="col-sm-8"><code>{settings.smtpPort}</code></dd>
        <dt className="col-sm-4">SMTP user</dt>
        <dd className="col-sm-8"><code>{settings.smtpUser}</code></dd>
      </dl>
    </SettingsCard>
  )
}
