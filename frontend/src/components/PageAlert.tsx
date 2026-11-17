// The page-level message above a page's cards: the outcome of the last action, dismissible.
export type Msg = { kind: 'success' | 'danger' | 'warning'; text: string } | null

export function PageAlert({ msg, onClose }: { msg: Msg; onClose: () => void }) {
  if (!msg) return null
  return (
    <div className={`alert alert-${msg.kind} alert-dismissible`}>
      {msg.text}
      <button type="button" className="btn-close" onClick={onClose} />
    </div>
  )
}
