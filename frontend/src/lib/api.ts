import type { ApiErrorBody } from '../types'

let csrfToken = ''

export class ApiError extends Error {
  constructor(public status: number, public body: ApiErrorBody) {
    super(body.message)
  }
}

export async function ensureCsrf(): Promise<string> {
  if (csrfToken) return csrfToken
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法获取安全令牌')
  const data = (await response.json()) as { token: string }
  csrfToken = data.token
  return csrfToken
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers.set('X-XSRF-TOKEN', await ensureCsrf())
  }
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (response.status === 204) return undefined as T
  const contentType = response.headers.get('content-type') ?? ''
  if (!response.ok) {
    const body = contentType.includes('json')
      ? ((await response.json()) as ApiErrorBody)
      : { code: 'HTTP_ERROR', message: await response.text() }
    throw new ApiError(response.status, body)
  }
  return (await response.json()) as T
}

export function jsonBody(value: unknown): BodyInit {
  return JSON.stringify(value)
}

export function resetCsrf(): void {
  csrfToken = ''
}

