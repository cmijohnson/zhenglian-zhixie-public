export function dateTime(value?: string): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(parsed)
}

export function localInput(iso?: string): string {
  const value = iso ? new Date(iso) : new Date()
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).formatToParts(value)
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`
}

export function toIso(local?: string): string | undefined {
  return local ? new Date(local).toISOString() : undefined
}

type RandomSource = {
  randomUUID?: () => string
  getRandomValues(array: Uint8Array): Uint8Array
}

export function idempotencyKey(source?: RandomSource): string {
  const random: RandomSource | undefined = source ?? globalThis.crypto
  if (!random || typeof random.getRandomValues !== 'function') {
    throw new Error('浏览器无法生成安全的请求标识，请刷新页面后重试')
  }
  if (typeof random.randomUUID === 'function') return random.randomUUID()

  const bytes = random.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
