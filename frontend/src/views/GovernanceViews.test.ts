import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationsView from './NotificationsView.vue'
import AdminGovernanceView from './AdminGovernanceView.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {},
}))

describe('market governance views', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders an auditable inbox and marks a notification read', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path.startsWith('/api/v1/notifications?')) return Promise.resolve({ unreadCount: 1, items: [{
        id: 'notice-1', eventType: 'CUSTOMER_POOL_RELEASED', title: '客户进入公共池',
        summary: 'A类 · 示例大学 · 处理期限已到', targetPath: '/customer-pools', read: false,
        createdAt: '2026-08-17T01:00:00Z', expiresAt: '2026-11-15T01:00:00Z',
      }] })
      if (path === '/api/v1/notifications/notice-1/read') return Promise.resolve(undefined)
      return Promise.resolve({})
    })
    const wrapper = mount(NotificationsView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('1 条未读')
    expect(wrapper.text()).toContain('A类 · 示例大学')
    await wrapper.get('.notification-item').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/notifications/notice-1/read', { method: 'POST' })
    expect(wrapper.text()).toContain('0 条未读')
  })

  it('creates multiple scoped webhook channels without exposing saved secrets', async () => {
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/admin/wecom-webhooks' && init?.method === 'POST') return Promise.resolve({})
      if (path === '/api/v1/admin/wecom-webhooks') return Promise.resolve([{
        id: 'hook-1', name: '成单通知群', eventTypes: ['BUSINESS_WON'], active: true,
        secretConfigured: true, version: 0, createdAt: '2026-08-17T01:00:00Z', updatedAt: '2026-08-17T01:00:00Z',
      }])
      if (path.startsWith('/api/v1/admin/wecom-webhooks/deliveries')) return Promise.resolve([{
        id: 'delivery-1', channelId: 'hook-1', channelName: '成单通知群', eventType: 'BUSINESS_WON',
        title: '成单已确认', status: 'FAILED', attemptCount: 3, lastErrorCode: 'NETWORK_ERROR',
        nextAttemptAt: '2026-08-17T01:15:00Z', createdAt: '2026-08-17T01:00:00Z',
      }])
      return Promise.resolve({})
    })
    const wrapper = mount(AdminGovernanceView)
    await flushPromises()

    expect(wrapper.text()).toContain('成单通知群')
    expect(wrapper.text()).toContain('密钥已安全保存')
    expect(wrapper.text()).not.toContain('abcdefghijklmnop')
    expect(wrapper.text()).toContain('NETWORK_ERROR')

    await wrapper.get('input[placeholder="例如：客户等级变更群"]').setValue('等级变更群')
    await wrapper.get('input[type="password"]').setValue('https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abcdefghijklmnop1234')
    const event = wrapper.findAll('.event-picker input').find((input) => (input.element as HTMLInputElement).value === 'CUSTOMER_QUALITY_CHANGED')
    await event!.setValue(true)
    await wrapper.get('.form-actions .primary-button').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/wecom-webhooks', expect.objectContaining({ method: 'POST' }))
  })
})
