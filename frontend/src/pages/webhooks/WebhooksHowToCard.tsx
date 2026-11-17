// Static help: the envelope SmartFreight POSTs, how to verify its signature, and delivery semantics.
export default function WebhooksHowToCard() {
  return (
    <div className="card shadow-sm border-0 bg-light">
      <div className="card-body small text-muted">
        <p className="fw-semibold text-body mb-1">How webhooks work</p>
        When a subscribed event fires, SmartFreight POSTs a JSON envelope (<code>id</code>, <code>type</code>,{' '}
        <code>occurred_at</code>, <code>data</code>) to your URL with an{' '}
        <code>X-SmartFreight-Signature</code> header (HMAC-SHA256 of <code>timestamp.body</code>). Verify it with
        your signing secret. Delivery is at-least-once and retried with backoff — dedupe on the{' '}
        <code>X-SmartFreight-Delivery</code> id.
      </div>
    </div>
  )
}
