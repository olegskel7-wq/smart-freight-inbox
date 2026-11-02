import { describe, expect, it } from 'vitest'
import { docTypeLabel, statusLabel, typeLabel } from './labels.ts'

// Values reach these from the URL (?status=…) as well as from the server, so a key that names an
// Object.prototype member must still get a string label, never the inherited function.
describe('labels', () => {
  it('maps known enum names', () => {
    expect(statusLabel('DRAFT_READY')).toBe('Draft ready')
    expect(typeLabel('RFQ')).toBe('Quote')
    expect(docTypeLabel('POD')).toBe('Proof of Delivery')
  })

  it.each(['constructor', 'toString', 'hasOwnProperty', 'valueOf'])('humanises "%s" instead of returning an Object member', (key) => {
    for (const label of [statusLabel(key), typeLabel(key), docTypeLabel(key)]) {
      expect(label).toBe(key.charAt(0).toUpperCase() + key.slice(1).toLowerCase())
    }
  })
})
