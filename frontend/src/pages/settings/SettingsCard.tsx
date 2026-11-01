import type { ReactNode } from 'react'

// One settings section. Pass `on` for a feature card: the header then shows an ON/OFF badge for the
// SAVED state (the snapshot), not the unsaved switch below it.
export function SettingsCard({ title, on, children }: { title: string; on?: boolean; children: ReactNode }) {
  return (
    <div className="card shadow-sm mb-4">
      {on === undefined ? (
        <div className="card-header fw-semibold">{title}</div>
      ) : (
        <div className="card-header fw-semibold d-flex align-items-center gap-2">
          {title}
          <span className={`badge small ${on ? 'bg-success' : 'bg-secondary'}`}>{on ? 'ON' : 'OFF'}</span>
        </div>
      )}
      <div className="card-body">{children}</div>
    </div>
  )
}

// The on/off switch at the top of a feature card's form.
export function FeatureSwitch({ id, checked, onChange, children }: {
  id: string
  checked: boolean
  onChange: (checked: boolean) => void
  children: ReactNode
}) {
  return (
    <div className="form-check form-switch mb-3">
      <input className="form-check-input" type="checkbox" role="switch" id={id}
             checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <label className="form-check-label" htmlFor={id}>
        {children}
      </label>
    </div>
  )
}
