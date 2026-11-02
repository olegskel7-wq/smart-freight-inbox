import { useState } from 'react'

/** What the add form posts to `/api/webhooks`; blank optional fields go as null. */
export type NewWebhook = {
  url: string
  eventTypes: string[]
  description: string | null
  secret: string | null
}

/**
 * The "Add webhook" card: endpoint URL, all events or a picked set, an optional description and
 * signing secret. The form clears itself only when `onCreate` reports the webhook was created, so a
 * rejected create keeps what the operator typed.
 */
export default function WebhookForm({ availableEventTypes, secretConfigured, onCreate }: {
  availableEventTypes: string[]
  /** No signing key on the server → nothing can be created. */
  secretConfigured: boolean
  onCreate: (webhook: NewWebhook) => Promise<boolean>
}) {
  const [url, setUrl] = useState('')
  const [description, setDescription] = useState('')
  const [secret, setSecret] = useState('')
  const [allEvents, setAllEvents] = useState(true)
  const [selectedTypes, setSelectedTypes] = useState<string[]>([])
  // A create is in flight: a second submit would make a second webhook whose one-time secret the
  // operator never sees, since the first answer's secret is replaced by the second's.
  const [submitting, setSubmitting] = useState(false)

  const toggleType = (type: string) => {
    setSelectedTypes((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type],
    )
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    const created = await onCreate({
      url,
      eventTypes: allEvents ? ['*'] : selectedTypes,
      description: description || null,
      secret: secret || null,
    }).finally(() => setSubmitting(false))
    if (!created) return
    setUrl('')
    setDescription('')
    setSecret('')
    setAllEvents(true)
    setSelectedTypes([])
  }

  return (
    <div className="card shadow-sm mb-4">
      <div className="card-header fw-semibold">Add webhook</div>
      <div className="card-body">
        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label small fw-semibold">Endpoint URL (HTTPS)</label>
            <input
              type="url"
              className="form-control form-control-sm"
              placeholder="https://hooks.zapier.com/hooks/catch/…"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              required
            />
          </div>

          <div className="mb-3">
            <label className="form-label small fw-semibold">Events</label>
            <div className="form-check">
              <input
                className="form-check-input"
                type="checkbox"
                id="wh-all"
                checked={allEvents}
                onChange={(e) => setAllEvents(e.target.checked)}
              />
              <label className="form-check-label" htmlFor="wh-all">
                All events
              </label>
            </div>
            {!allEvents &&
              availableEventTypes.map((t) => (
                <div className="form-check" key={t}>
                  <input
                    className="form-check-input"
                    type="checkbox"
                    id={`wh-${t}`}
                    checked={selectedTypes.includes(t)}
                    onChange={() => toggleType(t)}
                  />
                  <label className="form-check-label" htmlFor={`wh-${t}`}>
                    <code>{t}</code>
                  </label>
                </div>
              ))}
          </div>

          <div className="mb-3">
            <label className="form-label small fw-semibold">Description (optional)</label>
            <input
              type="text"
              className="form-control form-control-sm"
              placeholder="e.g. HubSpot via Zapier"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div className="mb-3">
            <label className="form-label small fw-semibold">Signing secret (optional)</label>
            <input
              type="text"
              className="form-control form-control-sm"
              placeholder="Leave blank to generate a strong secret"
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
            />
          </div>

          <button type="submit" className="btn btn-primary btn-sm" disabled={!secretConfigured || submitting}>
            Create webhook
          </button>
        </form>
      </div>
    </div>
  )
}
