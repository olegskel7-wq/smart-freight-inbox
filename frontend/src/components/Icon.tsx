// Inline SVG stroke icon (Feather/Tabler-style 24×24 paths) — no icon font, no CDN. Without
// `size` the icon takes its dimensions from CSS (the sidebar sizes its icons that way).
export default function Icon({ d, size }: { d: string[]; size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor"
      strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {d.map((p) => <path key={p} d={p} />)}
    </svg>
  )
}
