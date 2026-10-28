import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminFeishuIntegrationView from './AdminFeishuIntegrationView.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {},
}))

const configuration = {
  id: 'integration-1', appId: 'cli_example', secretConfigured: true,
  baseAppToken: 'bascnExample123', tableId: 'tblExample123', tableName: '英格云客户档案',
  active: true, oauthEnabled: true, inboundSyncEnabled: true, schemaState: 'READY', eventState: 'CONNECTED',
  riskAcknowledgedAt: '2026-09-01T00:00:00Z',
  lastTestedAt: '2026-09-01T00:01:00Z', lastSuccessAt: '2026-09-01T01:00:00Z',
  pendingCount: 4, permanentFailureCount: 0,
  version: 2, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T01:00:00Z',
}

describe('AdminFeishuIntegrationView', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/feishu-integration') return Promise.resolve(configuration)
      if (path === '/api/v1/admin/feishu-integration/schema') return Promise.resolve({
        provisioned: true, state: 'READY', checkedAt: '2026-09-01T01:01:00Z', fields: [
          { key: 'customerId', expectedName: '英格云客户ID', remoteName: '英格云客户ID', expectedType: 1, remoteType: 1, present: true, compatible: true },
        ],
      })
      if (path.startsWith('/api/v1/admin/feishu-integration/runs')) return Promise.resolve([])
      if (path.startsWith('/api/v1/admin/feishu-integration/failures')) return Promise.resolve([])
      if (path === '/api/v1/admin/feishu-integration/events/status') return Promise.resolve({
        connectionState: 'CONNECTED', pendingCount: 0, appliedCount: 2, conflictCount: 0, rejectedCount: 0, failedCount: 0,
      })
      if (path.startsWith('/api/v1/admin/feishu-integration/events?')) return Promise.resolve([])
      if (path === '/api/v1/admin/feishu-bindings') return Promise.resolve([])
      if (path === '/api/v1/admin/feishu-deal-bot') return Promise.resolve({
        enabled: false, submissionChatId: '', submissionChatName: '', broadcastChatId: '', broadcastChatName: '',
        botOpenId: '', botName: '', defaultBonusCents: 0,
        broadcastTitle: '🎉 英格科技成单播报 🎉',
        leaderboardTitle: '🌹英格科技我为你操碎了心🌹',
        leaderboardFooter: '👀看看又是哪个小笨蛋饿死了👀',
        privacyAcknowledgedAt: null, lastTestedAt: null, version: 0,
      })
      if (path === '/api/v1/admin/feishu-deal-bot/chats') return Promise.resolve([
        { chatId: 'oc_submit', name: '成单提交', ownerId: 'ou_owner' },
        { chatId: 'oc_broadcast', name: '全员广播', ownerId: 'ou_owner' },
      ])
      if (path.startsWith('/api/v1/admin/deal-confirmations/legacy')) return Promise.resolve({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
      if (path.startsWith('/api/v1/admin/deal-confirmations?')) return Promise.resolve({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
      if (path === '/api/v1/admin/deal-leaderboard') return Promise.resolve({
        from: '2026-09-01', to: '2026-09-30', historicalDealCount: 0,
        historicalBonusCents: 0, entries: [], previewText: '暂无成单',
      })
      if (path.startsWith('/api/v1/admin/feishu-message-failures')) return Promise.resolve([])
      if (path.endsWith('/initial-sync/preview')) return Promise.resolve({
        totalCustomers: 12, privateCustomers: 3, archivedCustomers: 2,
        sensitiveFields: ['手机号', '微信号', 'QQ号'], warning: '权限风险',
      })
      return Promise.resolve({})
    })
  })

  it('shows saved integration without exposing app secret', async () => {
    const wrapper = mount(AdminFeishuIntegrationView)
    await flushPromises()

    expect(wrapper.text()).toContain('飞书多维表格')
    expect(wrapper.text()).toContain('同步中')
    expect(wrapper.text()).toContain('双向协同事件')
    expect(wrapper.text()).toContain('飞书账号绑定')
    expect(wrapper.text()).toContain('成单机器人与全员广播')
    expect(wrapper.text()).toContain('本月开单榜与奖金台账')
    expect(wrapper.text()).toContain('授权 Base 所有者订阅事件')
    expect(wrapper.text()).toContain('tblExample123')
    expect(wrapper.get('a.open-feishu-link').attributes('href')).toBe('https://feishu.cn/base/bascnExample123?table=tblExample123')
    expect(wrapper.text()).not.toContain('app-secret')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('requires an explicit preview before starting the initial sync', async () => {
    const wrapper = mount(AdminFeishuIntegrationView)
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text() === '首次全量同步')
    await button!.trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feishu-integration/initial-sync/preview', { method: 'POST' })
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('私有客户 3 位'))
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feishu-integration/initial-sync/start', { method: 'POST' })
  })

  it('persists the OAuth and inbound collaboration switches', async () => {
    const wrapper = mount(AdminFeishuIntegrationView)
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text() === '保存配置')
    await button!.trigger('click')
    await flushPromises()

    const save = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/admin/feishu-integration' && init?.method === 'PUT')
    expect(JSON.parse(save![1].body)).toMatchObject({ oauthEnabled: true, inboundSyncEnabled: true })
  })
})
