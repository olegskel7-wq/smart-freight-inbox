// Small shared formatting helpers for the ticket pages.

export function formatDateTime(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString()
}

/** Compact date-time for dense list columns ("6 Jul 14:30"); year shown only when not current. */
export function formatDateTimeShort(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
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

export function formatConfidence(value: number | null): string {
  return value == null ? '—' : value.toFixed(2)
}
