import { describe, expect, it } from 'vitest'
import { dateTime, idempotencyKey, toIso } from './format'

describe('format', () => {
  it('renders server UTC timestamps in Asia/Shanghai', () => {
    expect(dateTime('2026-01-02T02:30:00Z')).toContain('01/02')
    expect(dateTime('2026-01-02T02:30:00Z')).toContain('10:30')
  })

  it('does not crash the page when a historical timestamp is outside browser range', () => {
    expect(dateTime('-292275055-05-16T23:00:00Z')).toBe('—')
    expect(dateTime('not-a-date')).toBe('—')
  })

  it('converts a browser local input into an ISO timestamp', () => {
    expect(toIso('2026-01-02T10:30')).toMatch(/^2026-01-02T/)
    expect(toIso('')).toBeUndefined()
  })

  it('generates an idempotency UUID when HTTP does not expose crypto.randomUUID', () => {
    const source = {
      getRandomValues(array: Uint8Array): Uint8Array {
        array.set(Array.from({ length: 16 }, (_, index) => index))
        return array
      },
    }
    expect(idempotencyKey(source)).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
  })
})
