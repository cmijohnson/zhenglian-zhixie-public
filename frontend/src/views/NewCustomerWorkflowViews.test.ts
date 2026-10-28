import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { useAuthStore } from '../stores/auth'
import DashboardView from './DashboardView.vue'
import ConsultationsView from './ConsultationsView.vue'
import QqChatView from './QqChatView.vue'
import AdminCustomerEnrichmentView from './AdminCustomerEnrichmentView.vue'

vi.mock('../lib/api', async (loadOriginal) => {
  const original = await loadOriginal<typeof import('../lib/api')>()
  return { ...original, api: vi.fn() }
})

const apiMock = vi.mocked(api)

async function context(path = '/') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { userId: 'u1', membershipId: 'm1', organizationId: 'o1', username: 'member',
    displayName: '员工甲', roles: ['MEMBER'], passwordChangeRequired: false }
  auth.checked = true
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/customers/:id', component: { template: '<div />' } },
    { path: '/consultations', component: { template: '<div />' } },
    { path: '/qq-chat', component: { template: '<div />' } },
  ] })
  await router.push(path); await router.isReady()
  return { pinia, router, auth }
}

beforeEach(() => apiMock.mockReset())
afterEach(() => vi.unstubAllGlobals())

describe('new customer collaboration workflows', () => {
  it('shows full A-class recruitment context and claims through the constrained endpoint', async () => {
    apiMock.mockImplementation(async (path, init) => {
      if (path === '/api/v1/dashboard') return { stats: { totalActive: 1, archivedCount: 0, todayDueCount: 0, overdueCount: 0, highIntentionCount: 1 }, dueToday: [], overdue: [], staleHighIntention: [], recentUpdated: [] } as never
      if (path === '/api/v1/follow-up-recruitments' && init?.method === 'POST') return {} as never
      if (path === '/api/v1/follow-up-recruitments') return [{ id: 'r1', customerId: 'c1', fullName: '张三', school: '示例大学', major: '计算机', grade: '2026', highSchool: '示例高中', sourceLocation: '示例城市', demandSummary: '希望转专业', ownerDisplayName: '负责人甲', requiredCount: 1, claimedCount: 0, openedAt: new Date().toISOString(), recentTimeline: [{ contactedAt: new Date().toISOString(), channel: '微信', summary: '确认需求' }] }] as never
      if (path === '/api/v1/follow-up-recruitments/r1/claim') return {} as never
      return [] as never
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { pinia, router } = await context()
    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('重点客户协作招募')
    expect(wrapper.text()).toContain('示例高中 / 示例城市')
    await wrapper.get('.recruitment-card .primary-button').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/follow-up-recruitments/r1/claim', { method: 'POST' })
  })

  it('loads consultation capacity, candidates and actionable handoff pipeline', async () => {
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/consultations') return [{ id: 'h1', customerId: 'c1', customerName: '张三', createdByMembershipId: 'm2', createdByName: '员工乙', consultantMembershipId: 'm1', consultantName: '员工甲', sourceInteractionIds: ['i1'], consultationReason: '确认升学方案', customerQuotes: '想先了解课程', goalsMotivation: '升学', actionsTaken: '已沟通', mainConcerns: '时间', decisionParticipantConsent: false, productFit: '待核实', suggestedFocus: '课程匹配', explicitTaboos: '不强推', expectedResult: '明确下一步', scheduledStart: '2026-08-20T02:00:00Z', scheduledEnd: '2026-08-20T03:00:00Z', status: 'PENDING_ACCEPTANCE', createdAt: '', updatedAt: '', version: 0 }] as never
      if (String(path).startsWith('/api/v1/customers?')) return { items: [] } as never
      if (path === '/api/v1/consultations/candidates') return [{ membershipId: 'm1', displayName: '员工甲', roles: ['MEMBER'], teams: ['一组'] }] as never
      if (path === '/api/v1/consultation-settings') return { businessStart: '09:00', businessEnd: '21:00', minimumLeadMinutes: 30, allowedDurations: [30, 60], dailyCapacity: 6, feedbackDueHours: 48, version: 0 } as never
      if (path === '/api/v1/consultation-failure-reasons') return [] as never
      if (path === '/api/v1/consultations/h1/accept') return {} as never
      return [] as never
    })
    const { pinia, router } = await context('/consultations')
    const wrapper = mount(ConsultationsView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('确认升学方案')
    expect(wrapper.text()).toContain('48 小时内闭环')
    await wrapper.get('.consultation-row .soft-button').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/consultations/h1/accept', expect.objectContaining({ method: 'POST' }))
  })

  it('embeds the full session-bound QQ workbench without loading tokens or the legacy 200-friend list', async () => {
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/qq-connections/me') return { id: 'q1', status: 'CONNECTED', maskedQq: '12***89', createdAt: '' } as never
      if (path === '/api/v1/qq-workbench/bootstrap') return { status: 'CONNECTED', maskedQq: '12***89', workbenchUrl: '/qq-workbench/', websocketUrl: '/api/v1/qq-workbench/ws', rawRetentionDays: 30, exportQuotaBytes: 10737418240 } as never
      return [] as never
    })
    const { pinia, router } = await context('/qq-chat')
    const wrapper = mount(QqChatView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('完整浏览、搜索、媒体、任务与导出')
    expect(wrapper.get('iframe').attributes('src')).toBe('/qq-workbench/')
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/qq-connections/friends?page=1&limit=200')
    expect(wrapper.html()).not.toContain('accessToken')
  })

  it('recovers stale workbench authorization by refreshing the real QQ state', async () => {
    let connectionReads = 0
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/qq-connections/me') {
        connectionReads += 1
        return connectionReads === 1
          ? { id: 'q1', status: 'CONNECTED', maskedQq: '12***89', createdAt: '' } as never
          : { id: 'q1', status: 'PAIRING', maskedQq: '12***89', qrCodeDataUrl: 'data:image/png;base64,test', createdAt: '' } as never
      }
      if (path === '/api/v1/qq-workbench/bootstrap') return { status: 'CONNECTED', maskedQq: '12***89', workbenchUrl: '/qq-workbench/', websocketUrl: '/api/v1/qq-workbench/ws', rawRetentionDays: 30, exportQuotaBytes: 10737418240 } as never
      return [] as never
    })
    const { pinia, router } = await context('/qq-chat')
    const wrapper = mount(QqChatView, { attachTo: document.body, global: { plugins: [pinia, router] } })
    await flushPromises()
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      source: frame.contentWindow,
      data: { type: 'yingge:qq-auth-required', version: 1 },
    }))
    await flushPromises()
    expect(connectionReads).toBe(2)
    expect(wrapper.text()).toContain('工作 QQ 连接已中断，请重新扫码')
    expect(wrapper.text()).toContain('使用工作 QQ 扫码')
    expect(wrapper.find('iframe').exists()).toBe(false)
    wrapper.unmount()
  })

  it('coalesces repeated workbench authorization events instead of entering an iframe reload loop', async () => {
    let connectionReads = 0
    let bootstrapReads = 0
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/qq-connections/me') {
        connectionReads += 1
        return { id: 'q1', status: 'CONNECTED', maskedQq: '12***89', createdAt: '' } as never
      }
      if (path === '/api/v1/qq-workbench/bootstrap') {
        bootstrapReads += 1
        return { status: 'CONNECTED', maskedQq: '12***89', workbenchUrl: '/qq-workbench/', websocketUrl: '/api/v1/qq-workbench/ws', rawRetentionDays: 30, exportQuotaBytes: 10737418240 } as never
      }
      return [] as never
    })
    const { pinia, router } = await context('/qq-chat')
    const wrapper = mount(QqChatView, { attachTo: document.body, global: { plugins: [pinia, router] } })
    await flushPromises()
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    const event = () => new MessageEvent('message', {
      origin: window.location.origin,
      source: frame.contentWindow,
      data: { type: 'yingge:qq-auth-required', version: 1 },
    })
    window.dispatchEvent(event())
    window.dispatchEvent(event())
    await flushPromises()
    expect(connectionReads).toBe(2)
    expect(bootstrapReads).toBe(2)
    expect(wrapper.text()).toContain('QQ 工作台会话已刷新')
    wrapper.unmount()
  })

  it('accepts a same-frame sync request only after preview and matches the CRM customer by exact QQ', async () => {
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/qq-connections/me') return { id: 'q1', status: 'CONNECTED', maskedQq: '12***89', createdAt: '' } as never
      if (path === '/api/v1/qq-workbench/bootstrap') return { status: 'CONNECTED', maskedQq: '12***89', workbenchUrl: '/qq-workbench/', websocketUrl: '/api/v1/qq-workbench/ws', rawRetentionDays: 30, exportQuotaBytes: 10737418240 } as never
      if (String(path).startsWith('/api/v1/customers?')) return { items: [{ id: 'c1', fullName: '客户甲', qqId: '12345678' }] } as never
      return [] as never
    })
    const { pinia, router } = await context('/qq-chat')
    const wrapper = mount(QqChatView, { attachTo: document.body, global: { plugins: [pinia, router] } })
    await flushPromises()
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      source: frame.contentWindow,
      data: { type: 'yingge:qq-sync', version: 1, payload: {
        chatType: 1, peerUid: 'uid-1', peerQq: '12345678', sessionName: '测试好友',
        rangeStart: Date.now() - 3600000, rangeEnd: Date.now(), messageCount: 50,
      } },
    }))
    await flushPromises()
    expect(wrapper.text()).toContain('SYNC TO CRM')
    expect(wrapper.text()).toContain('已按 QQ 唯一匹配到客户档案')
    expect(apiMock).toHaveBeenCalledWith(expect.stringContaining('search=12345678'))
    wrapper.unmount()
  })

  it('confirms a QQ draft over HTTP when crypto.randomUUID is unavailable', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query: string) => ({
      matches: query === '(max-width: 760px)', media: query, onchange: null,
      addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
    })))
    vi.stubGlobal('crypto', {
      getRandomValues(array: Uint8Array): Uint8Array {
        array.set(Array.from({ length: 16 }, (_, index) => index + 1))
        return array
      },
    })
    apiMock.mockImplementation(async (path, init) => {
      if (path === '/api/v1/qq-connections/me') return { id: 'q1', status: 'CONNECTED', maskedQq: '12***89', createdAt: '' } as never
      if (path === '/api/v1/qq-workbench/bootstrap') return { status: 'CONNECTED', maskedQq: '12***89', workbenchUrl: '/qq-workbench/', websocketUrl: '/api/v1/qq-workbench/ws', rawRetentionDays: 30, exportQuotaBytes: 10737418240 } as never
      if (String(path).startsWith('/api/v1/customers?')) return { items: [{ id: 'c1', fullName: '客户甲', qqId: '12345678' }] } as never
      if (path === '/api/v1/qq-imports/preview' && init?.method === 'POST') return {
        importId: 'import-1', customerId: 'c1', chatType: 1, peerUid: 'uid-1', peerQq: '12345678',
        groupContextConfirmed: false, messageCount: 2, rangeStart: '2026-08-18T01:00:00Z',
        rangeEnd: '2026-08-18T02:00:00Z', expiresAt: '2026-09-17T02:00:00Z', messages: [],
        draft: { summary: '已确认需求', customerQuotes: [], goalsMotivation: '学习', actionsTaken: '咨询',
          mainConcerns: '时间', decisionParticipants: '未确认', productFit: '待核实', suggestedFocus: '课程',
          explicitTaboos: '无', outcome: '继续沟通', nextStep: '约时间', uncertainties: [] },
      } as never
      if (path === '/api/v1/qq-imports/import-1/confirm' && init?.method === 'POST') return { interactionId: 'interaction-1' } as never
      return [] as never
    })
    const { pinia, router } = await context('/qq-chat')
    const wrapper = mount(QqChatView, { attachTo: document.body, global: { plugins: [pinia, router] } })
    await flushPromises()
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      source: frame.contentWindow,
      data: { type: 'yingge:qq-sync', version: 1, payload: {
        chatType: 1, peerUid: 'uid-1', peerQq: '12345678', sessionName: '测试好友',
        rangeStart: Date.now() - 3600000, rangeEnd: Date.now(), messageCount: 2,
      } },
    }))
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('生成 AI 草稿'))!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.draft-disclosure>button').attributes('aria-expanded')).toBe('false')
    await wrapper.get('.draft-disclosure>button').trigger('click')
    expect(wrapper.get('.draft-disclosure>button').attributes('aria-expanded')).toBe('true')
    await wrapper.get('.drawer-actions .primary-button').trigger('click')
    await flushPromises()

    const call = apiMock.mock.calls.find(([path]) => path === '/api/v1/qq-imports/import-1/confirm')
    expect(call).toBeDefined()
    expect(JSON.parse(String(call![1]?.body))).toMatchObject({
      idempotencyKey: '01020304-0506-4708-890a-0b0c0d0e0f10',
      draft: { summary: '已确认需求' },
    })
    expect(wrapper.text()).not.toContain('确认写入失败')
    wrapper.unmount()
  })

  it('renders enrichment preview outcomes without treating conflicts as ready rows', async () => {
    const job = { id: 'j1', filename: '学生所在地补全.xlsx', fileSha256: 'abcdef0123456789', status: 'COMPLETED', totalRows: 583, readyRows: 534, updatedRows: 500, skippedRows: 49, conflictRows: 34, expiresAt: '', createdAt: '2026-08-13T00:00:00Z' }
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/v1/admin/customer-enrichment-jobs') return [job] as never
      if (path === '/api/v1/admin/customer-enrichment-jobs/j1') return { job, rows: [{ rowNumber: 2, fullName: '张三', major: '计算机', grade: '2026', highSchool: '示例高中', sourceLocation: '示例城市', mappingStatus: '已匹配', status: 'CONFLICT', message: 'CRM 已有不同高中', appliedHighSchool: false, appliedSourceLocation: false }] } as never
      return [] as never
    })
    const { pinia, router } = await context()
    const wrapper = mount(AdminCustomerEnrichmentView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await wrapper.get('.job-row').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('资料冲突')
    expect(wrapper.text()).toContain('CRM 已有不同高中')
    expect(wrapper.findAll('button.primary-button').some((button) => button.text().includes('确认提交'))).toBe(false)
  })
})
