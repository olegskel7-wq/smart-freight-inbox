// Small shared formatting helpers for the ticket pages.

export function formatDateTime(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString()
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
