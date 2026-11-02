import { Link } from 'react-router-dom'
import Icon from '../../components/Icon.tsx'
import type { TicketStats } from '../../types.ts'

// Inline SVG glyphs for the summary chips (no icon-font dependency, no CDN).
const CHIP_ICONS = {
  open: ['M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z', 'M22 12h-6l-2 3h-4l-2-3H2'],
  review: ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z'],
  breach: ['M12 8v4l2 2', 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z'],
  sent: ['M22 2 11 13', 'M22 2 15 22l-4-9-9-4z'],
}

// A summary chip — a link that drills the list down to the tickets it counts (?view=…).
function StatChip(props: {
  view: string; active: boolean; tone: string; icon: string[]; value: number | null; label: string
}) {
  return (
    <div className="col-6 col-md-3">
      <Link
        to={`/tickets?view=${props.view}`}
        className={`sf-chip${props.active ? ' is-active' : ''}`}
        title={`Show ${props.label.toLowerCase()} tickets`}
      >
        <span className={`sf-chip-ic ${props.tone}`}><Icon d={props.icon} size={18} /></span>
        <span className="sf-chip-body">
          <span className="sf-chip-value">{props.value ?? '—'}</span>
          <span className="sf-chip-label">{props.label}</span>
        </span>
      </Link>
    </div>
  )
}

/** The summary strip; counts show a dash until they arrive (a failed count never blocks the list). */
export default function SummaryChips({ view, stats }: { view: string; stats: TicketStats | null }) {
  return (
    <div className="row g-3 mb-3">
      <StatChip view="open" active={view === 'open'} tone="sf-ic-blue" icon={CHIP_ICONS.open} value={stats?.open ?? null} label="Open" />
      <StatChip view="awaiting" active={view === 'awaiting'} tone="sf-ic-amber" icon={CHIP_ICONS.review} value={stats?.awaitingReview ?? null} label="Awaiting review" />
      <StatChip view="breached" active={view === 'breached'} tone="sf-ic-red" icon={CHIP_ICONS.breach} value={stats?.slaBreached ?? null} label="SLA breached" />
      <StatChip view="autosent_today" active={view === 'autosent_today'} tone="sf-ic-teal" icon={CHIP_ICONS.sent} value={stats?.autoSentToday ?? null} label="Auto-sent today" />
    </div>
  )
}
