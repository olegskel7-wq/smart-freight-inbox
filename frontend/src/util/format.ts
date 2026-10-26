// Small shared formatting helpers for the ticket pages.

const HAS_ZONE = /(Z|[+-]\d{2}:?\d{2})$/

/**
 * Parses a backend timestamp. The DTOs carry `LocalDateTime` (no zone) written by a JVM running in
 * UTC (the backend container), while `new Date("2026-06-10T10:00")` would read it as browser-local —
 * an hour early during BST, which made relative SLAs ("Overdue 30m") wrong. Zone-less values are
 * therefore read as UTC; values that already carry a zone are left alone.
 */
export function parseServerTime(value: string): Date {
  return new Date(value.includes('T') && !HAS_ZONE.test(value) ? `${value}Z` : value)
}

export function formatDateTime(value: string | null): string {
  if (!value) return '—'
  const d = parseServerTime(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString()
}

/** Compact date-time for dense list columns ("6 Jul 14:30"); year shown only when not current. */
export function formatDateTimeShort(value: string | null): string {
  if (!value) return '—'
  const d = parseServerTime(value)
  if (Number.isNaN(d.getTime())) return value
  const sameYear = d.getFullYear() === new Date().getFullYear()
  const date = d.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    ...(sameYear ? {} : { year: '2-digit' }),
  })
  const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  return `${date} ${time}`
}

/** Bootstrap badge class per ticket status — parity with the Thymeleaf table colours. */
export function statusBadgeClass(status: string | null): string {
  switch (status) {
    case 'DRAFT_READY':
      return 'bg-primary'
    case 'CLOSED':
    case 'AUTO_SENT':
      return 'bg-success'
    case 'FAILED':
      return 'bg-danger'
    case 'RETRY_LATER':
    case 'PROCESSING':
      return 'bg-warning text-dark'
    case 'IRRELEVANT':
      return 'bg-secondary'
    default:
      return 'bg-light text-dark'
  }
}

/** Compact duration for SLA text: "21d", "3h", "1h 20m", "45m" (minutes only below 10 h). */
export function formatDuration(ms: number): string {
  const minutes = Math.max(1, Math.round(Math.abs(ms) / 60_000))
  if (minutes >= 24 * 60) return `${Math.floor(minutes / (24 * 60))}d`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m}m`
  return h >= 10 || m === 0 ? `${h}h` : `${h}h ${m}m`
}

export type SlaBucket = 'overdue' | 'today' | 'later'

export interface SlaInfo {
  bucket: SlaBucket
  /** "Overdue 21d", "Due in 1h 20m", "Due tomorrow", "No SLA". */
  text: string
  tone: 'red' | 'amber' | 'muted'
}

/**
 * Relative SLA for an open ticket (variant C list grouping). Overdue = flagged breached by the
 * SlaBreachScheduler OR already past its deadline (the scheduler runs once a minute). "Due today"
 * ends at local midnight; anything later, or with no deadline, is "Later".
 */
export function slaInfo(deadline: string | null, breached: boolean, now: number): SlaInfo {
  const due = deadline ? parseServerTime(deadline).getTime() : NaN
  if (Number.isNaN(due)) {
    return breached
      ? { bucket: 'overdue', text: 'Overdue', tone: 'red' }
      : { bucket: 'later', text: 'No SLA', tone: 'muted' }
  }
  if (breached || due <= now) {
    return { bucket: 'overdue', text: `Overdue ${formatDuration(now - due)}`, tone: 'red' }
  }
  const endOfToday = new Date(now)
  endOfToday.setHours(24, 0, 0, 0)
  if (due < endOfToday.getTime()) {
    const soon = due - now < 2 * 60 * 60_000
    return { bucket: 'today', text: `Due in ${formatDuration(due - now)}`, tone: soon ? 'amber' : 'muted' }
  }
  const endOfTomorrow = new Date(endOfToday)
  endOfTomorrow.setDate(endOfTomorrow.getDate() + 1)
  const text = due < endOfTomorrow.getTime() ? 'Due tomorrow' : `Due in ${formatDuration(due - now)}`
  return { bucket: 'later', text, tone: 'muted' }
}
