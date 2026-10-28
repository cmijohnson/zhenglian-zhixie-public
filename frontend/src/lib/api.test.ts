import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, resetCsrf } from './api'

describe('api', () => {
  afterEach(() => {
    resetCsrf()
    vi.unstubAllGlobals()
  })

  it('fetches a CSRF token before a state-changing request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-test' }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/v1/customers', { method: 'POST', body: '{}' })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const request = fetchMock.mock.calls[1][1] as RequestInit
    expect(new Headers(request.headers).get('X-XSRF-TOKEN')).toBe('csrf-test')
    expect(request.credentials).toBe('same-origin')
  })

  it('preserves the structured server error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'FORBIDDEN', message: '没有权限', traceId: 'trace-1',
    }), { status: 403, headers: { 'Content-Type': 'application/json' } })))

    await expect(api('/api/v1/customers')).rejects.toMatchObject({
      status: 403,
      body: { code: 'FORBIDDEN', message: '没有权限', traceId: 'trace-1' },
    })
  })
})
