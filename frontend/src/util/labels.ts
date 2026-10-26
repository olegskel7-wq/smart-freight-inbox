// Human labels for backend enum names and ticket fields (Phase 3.2 UI, variant C). One place, so an
// operator never sees `STATUS_UPDATE` / `DRAFT_READY` and every page words things the same way.
// Unknown values fall back to a de-shouted version of the enum name instead of being hidden.

const TYPE_LABELS: Record<string, string> = {
  RFQ: 'Quote',
  STATUS_UPDATE: 'Status update',
  BOOKING_CONFIRM: 'Booking',
  INVOICE_QUERY: 'Invoice',
  DOCUMENT_REQUEST: 'Document',
  GENERAL: 'General',
}

const STATUS_LABELS: Record<string, string> = {
  RECEIVED: 'Received',
  NEW: 'Queued',
  PROCESSING: 'Drafting',
  DRAFT_READY: 'Draft ready',
  FAILED: 'Failed',
  RETRY_LATER: 'Retrying',
  IRRELEVANT: 'Not relevant',
  CLOSED: 'Closed',
  AUTO_SENT: 'Auto-sent',
}

// Mirrors com.smartfreight.inbox.entity.enums.DocumentType labels.
const DOC_TYPE_LABELS: Record<string, string> = {
  POD: 'Proof of Delivery',
  BILL_OF_LADING: 'Bill of Lading',
  CUSTOMS: 'Customs Document',
  INVOICE_COPY: 'Invoice Copy',
  PACKING_LIST: 'Packing List',
  CMR: 'CMR Note',
  OTHER: 'Document',
}

/** "SOME_ENUM" → "Some enum". */
function humanise(value: string): string {
  const s = value.replace(/_/g, ' ').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}

export function typeLabel(type: string | null): string {
  if (!type) return 'Unclassified'
  return TYPE_LABELS[type] ?? humanise(type)
}

export function statusLabel(status: string | null): string {
  if (!status) return '—'
  return STATUS_LABELS[status] ?? humanise(status)
}

export function docTypeLabel(docType: string | null): string {
  if (!docType) return 'Document'
  return DOC_TYPE_LABELS[docType] ?? humanise(docType)
}

/** Statuses after which the ticket needs no more work (no SLA, no reply). */
export function isDone(status: string | null): boolean {
  return status === 'CLOSED' || status === 'AUTO_SENT' || status === 'IRRELEVANT'
}

/**
 * The single escalation chip that replaces the stacked Urgent/Angry badges (Phase 3.10 signals):
 * "Urgent · upset", "Urgent", "Upset", or null when neither applies.
 */
export function toneLabel(urgency: string | null, sentimentNegative: boolean): string | null {
  const urgent = urgency === 'HIGH'
  if (urgent && sentimentNegative) return 'Urgent · upset'
  if (urgent) return 'Urgent'
  if (sentimentNegative) return 'Upset'
  return null
}

/** Confidence 0..1 → "90% · high". The heuristic is the Phase 1.6 auto-send score. */
export function confidenceLabel(value: number | null): string {
  if (value == null) return '—'
  const band = value >= 0.8 ? 'high' : value >= 0.5 ? 'medium' : 'low'
  return `${Math.round(value * 100)}% · ${band}`
}

const GENERIC_HOST_LABELS = new Set(['mail', 'email', 'mx', 'smtp', 'eu', 'uk', 'us', 'www', 'ops'])

const NAME_IN_ADDRESS =/^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$/

function titleCase(words: string): string {
  return words
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ')
}

/**
 * Splits a sender address into a display name and organisation for the mail-style list:
 * "tom.fisher@harbourline.example.com" → { name: "Tom Fisher", org: "Harbourline" }. A
 * "Name <addr>" form keeps its display name. Best effort — the full address stays in a tooltip.
 */
export function senderParts(sender: string | null): { name: string; org: string; address: string } {
  if (!sender) return { name: 'Unknown sender', org: '', address: '' }
  const m = NAME_IN_ADDRESS.exec(sender)
  const address = (m ? m[2] : sender).trim()
  const [local = '', domain = ''] = address.split('@')
  const name = (m && m[1].trim()) || titleCase(local.replace(/[._+-]+/g, ' ')) || address
  // Skip host-ish leading labels ("mail.acme.co.uk", "eu.dhl.com") so the org is the company.
  const labels = domain.split('.')
  const orgLabel = labels.find((l, i) => i < labels.length - 1 && !GENERIC_HOST_LABELS.has(l.toLowerCase())) ?? labels[0]
  const org = orgLabel ? titleCase(orgLabel.replace(/[-_]+/g, ' ')) : ''
  return { name, org, address }
}

/** Two-letter avatar initials from a display name ("Tom Fisher" → "TF", "Accounts" → "AC"). */
export function initialsOf(name: string): string {
  const words = name.split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[words.length - 1][0]).toUpperCase()
}
