/** The freshly created signing secret, shown once for the operator to copy — the server never returns it again. */
export default function SigningSecretAlert({ secret, onClose }: { secret: string; onClose: () => void }) {
  return (
    <div className="alert alert-info">
      <p className="fw-semibold mb-1">Signing secret (shown once)</p>
      <p className="small mb-2">
        Store this in your receiver and verify the <code>X-SmartFreight-Signature</code> header. It cannot be
        retrieved later — only rotated.
      </p>
      <div className="d-flex gap-2 align-items-center">
        <code className="bg-light border rounded p-2 flex-grow-1 text-break">{secret}</code>
        <button
          type="button"
          className="btn btn-outline-secondary btn-sm text-nowrap"
          onClick={() => void navigator.clipboard?.writeText(secret)}
        >
          Copy
        </button>
        <button type="button" className="btn-close" onClick={onClose} />
      </div>
    </div>
  )
}
