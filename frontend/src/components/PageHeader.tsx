import type { ReactNode } from 'react'

// Shared page header (Phase 3 UI refresh): an h4 title with an optional muted subtitle, and an
// optional right-aligned slot (used for the per-page feature-off badge). Keeps every page's
// heading consistent with the tickets dashboard.
export default function PageHeader({ title, subtitle, children }: {
  title: string
  subtitle?: string
  children?: ReactNode
}) {
  return (
    <div className="sf-pagehead">
      <div>
        <h1 className="h4 mb-0">{title}</h1>
        {subtitle && <p className="sf-pagesub">{subtitle}</p>}
      </div>
      {children && <div className="ms-auto align-self-center">{children}</div>}
    </div>
  )
}
