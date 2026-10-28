import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyAgentView from './MyAgentView.vue'
import OAuthConsentView from './OAuthConsentView.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/api', () => ({
  api: apiMock,
  ApiError: class ApiError extends Error {},
}))
vi.mock('vue-router', async (original) => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ query: { request: 'request-1' }, params: {} }) }
})

describe('Agent authorization views', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ resource: 'https://101.43.22.42/mcp' }),
    }))
  })

  it('shows the public MCP endpoint, granted scopes and approval queue', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/agent/authorizations') return Promise.resolve([{
        id: 'grant-1', clientId: 'client-1', clientName: '员工 Agent', displayName: '测试员工',
        scopes: ['yingge.read', 'yingge.write'], status: 'ACTIVE', createdAt: '2026-07-22T00:00:00Z',
        expiresAt: '2026-08-21T00:00:00Z', lastUsedAt: '2026-07-22T01:00:00Z',
      }])
      if (path === '/api/v1/agent/approvals') return Promise.resolve([{
        id: 'approval-1', clientId: 'client-1', toolName: 'yingge_customer_archive',
        summary: '确认归档客户', status: 'PENDING', createdAt: '2026-07-22T00:00:00Z',
        expiresAt: '2026-07-22T00:05:00Z',
      }])
      return Promise.reject(new Error(`unexpected ${path}`))
    })
    const wrapper = mount(MyAgentView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('https://101.43.22.42/mcp')
    expect(wrapper.text()).toContain('员工 Agent')
    expect(wrapper.text()).toContain('yingge.read')
    expect(wrapper.text()).toContain('yingge_customer_archive')
    expect(wrapper.text()).not.toContain('access_token')
  })

  it('renders exact callback and only the scopes the current role may grant', async () => {
    apiMock.mockResolvedValue({
      requestId: 'request-1', clientId: 'client-1', clientName: '本地 Agent',
      redirectUri: 'http://127.0.0.1:43123/callback',
      requestedScopes: ['yingge.read', 'yingge.write', 'yingge.admin'],
      grantableScopes: ['yingge.read', 'yingge.write'], expiresAt: '2026-07-22T00:05:00Z',
    })
    const wrapper = mount(OAuthConsentView)
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/oauth/requests/request-1')
    expect(wrapper.text()).toContain('http://127.0.0.1:43123/callback')
    expect(wrapper.text()).toContain('yingge.read')
    expect(wrapper.text()).toContain('yingge.write')
    expect(wrapper.text()).toContain('以下 Scope 不会授予：yingge.admin')
  })

  it('generates a one-time QwenPaw JSON config with an authorization header', async () => {
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/agent/authorizations') return Promise.resolve([])
      if (path === '/api/v1/agent/approvals') return Promise.resolve([])
      if (path === '/api/v1/agent/personal-token' && init?.method === 'POST') return Promise.resolve({
        grantId: 'grant-personal', clientId: 'yingge_qwenpaw_personal', accessToken: 'one-time-token',
        scopes: ['yingge.read', 'yingge.write'], resource: 'http://101.43.22.42:38080/mcp',
        expiresAt: '2026-08-21T00:00:00Z',
      })
      return Promise.reject(new Error(`unexpected ${path}`))
    })
    const wrapper = mount(MyAgentView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    const generate = wrapper.findAll('button').find((button) => button.text().includes('生成问候连接配置'))
    expect(generate).toBeDefined()
    await generate!.trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/agent/personal-token', { method: 'POST' })
    const config = JSON.parse((wrapper.get('textarea[aria-label="问候 MCP JSON 配置"]').element as HTMLTextAreaElement).value)
    expect(config.mcpServers.yingge_mcp_personal.url).toBe('http://101.43.22.42:38080/mcp')
    expect(config.mcpServers.yingge_mcp_personal.headers.Authorization).toBe('Bearer one-time-token')
    expect(wrapper.text()).toContain('本页面刷新后不再显示')
  })
})
