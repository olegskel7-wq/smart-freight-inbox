import { useOperators } from '../api/useOperators.ts'
import PageHeader from '../components/PageHeader.tsx'
import AiEndpointSection from './settings/AiEndpointSection.tsx'
import AutoSendSection from './settings/AutoSendSection.tsx'
import { useMailboxForms } from './settings/mailboxForm.ts'
import MailboxesSection from './settings/MailboxesSection.tsx'
import NotificationsSection from './settings/NotificationsSection.tsx'
import PlatformSmtpSection from './settings/PlatformSmtpSection.tsx'
import PromptsSection from './settings/PromptsSection.tsx'
import QuoteFollowUpSection from './settings/QuoteFollowUpSection.tsx'
import SmartRoutingSection from './settings/SmartRoutingSection.tsx'
import SmtpHowToCard from './settings/SmtpHowToCard.tsx'
import { type SectionProps, useSettings } from './settings/useSettings.ts'
import StatusUpdateSection from './settings/StatusUpdateSection.tsx'

// Each card saves its own section through /api/settings/*; a successful save returns the refreshed
// snapshot, which re-seeds every card's form (useSettings). The platform SMTP block is read-only
// (environment-sourced).
export default function SettingsPage() {
  // Operator list for the escalation-assignee dropdown. Best-effort: a non-admin can't
  // read /api/users (403) → the selector just falls back to "no operators".
  const operators = useOperators()
  const { data, form, error, msg, dismissMsg, patchForm, save, deleteAndReload } = useSettings()
  const mailboxForms = useMailboxForms()

  if (error) return <div className="alert alert-danger">{error}</div>
  if (!data || !form) return <div className="text-muted">Loading…</div>

  const section: SectionProps = { settings: data, form, onChange: patchForm, save }

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      {msg && (
        <div className={`alert alert-${msg.kind} alert-dismissible`}>
          {msg.text}
          <button type="button" className="btn-close" onClick={dismissMsg} />
        </div>
      )}

      <PageHeader
        title="Settings"
        subtitle="Mailboxes, AI endpoint, auto-send and prompts for this tenant"
      />

      <MailboxesSection settings={data} forms={mailboxForms} save={save} deleteAndReload={deleteAndReload} />
      <AiEndpointSection {...section} />
      <PlatformSmtpSection settings={data} />
      <NotificationsSection {...section} />
      <AutoSendSection {...section} />
      <StatusUpdateSection {...section} />
      <QuoteFollowUpSection {...section} />
      <SmartRoutingSection {...section} operators={operators} />
      <PromptsSection {...section} />
      <SmtpHowToCard />
    </div>
  )
}
