import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import LoginView from './LoginView.vue'
import SettingsView from './SettingsView.vue'
import FeishuWorkbenchView from './FeishuWorkbenchView.vue'
import FeishuHomeView from './FeishuHomeView.vue'
import FeishuDealsView from './FeishuDealsView.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {
    constructor(public status: number, public body: { message: string; traceId?: string }) { super(body.message) }
  },
}))

function member() {
  return { userId: 'user-1', membershipId: 'member-1', organizationId: 'org-1', username: 'member', displayName: '测试成员', roles: ['MEMBER'], passwordChangeRequired: false }
}

async function routerAt(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', component: LoginView }, { path: '/dashboard', component: { template: '<div>dashboard</div>' } },
    { path: '/settings', component: SettingsView }, { path: '/feishu-workbench', component: FeishuWorkbenchView },
    { path: '/feishu', component: FeishuHomeView }, { path: '/feishu/deals', component: FeishuDealsView },
    { path: '/feishu/crm', component: { template: '<div>crm-entry</div>' } },
    { path: '/customers/:id', component: { template: '<div>customer</div>' } },
    { path: '/register', component: { template: '<div>register</div>' } },
  ] })
  await router.push(path); await router.isReady(); return router
}

describe('Feishu identity and collaboration views', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
  })

  it('enables Feishu login when an organization has enabled it', async () => {
    apiMock.mockResolvedValue({ available: true, httpsRequired: true, organizations: [{ integrationId: 'integration-1', organizationName: '英格科技' }] })
    const router = await routerAt('/login')
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('使用飞书登录')
    expect(wrapper.text()).toContain('可信 HTTPS')
    expect(wrapper.get('.feishu-mark').text()).toBe('飞')
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('keeps the Feishu login mark visible while OAuth is disabled', async () => {
    apiMock.mockResolvedValue({ available: false, httpsRequired: true, organizations: [] })
    const router = await routerAt('/login')
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.get('.feishu-mark').text()).toBe('飞')
    expect(wrapper.get('.feishu-login').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('功能入口已保留')
  })

  it('confirms an unbound Feishu identity using one existing account login', async () => {
    apiMock.mockResolvedValue(member())
    const router = await routerAt('/login?feishuStatus=confirm&feishuIntent=11111111-1111-1111-1111-111111111111')
    const wrapper = mount(LoginView, { global: { plugins: [router] } })
    await wrapper.get('input[autocomplete="username"]').setValue('member')
    await wrapper.get('input[autocomplete="current-password"]').setValue('secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/auth/feishu/binding/confirm', expect.objectContaining({ method: 'POST' }))
    expect(useAuthStore().user?.membershipId).toBe('member-1')
    expect(router.currentRoute.value.path).toBe('/dashboard')
  })

  it('shows a bound identity and requires the current password to unbind', async () => {
    useAuthStore().adopt(member())
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/auth/feishu/binding' && init?.method === 'DELETE') return Promise.resolve(undefined)
      if (path === '/api/v1/auth/feishu/binding') return Promise.resolve({ bound: true, oauthAvailable: true, httpsRequired: true, bindingId: 'binding-1', feishuUserId: 'feishu-user-123456', displayName: '飞书成员', boundAt: '2026-09-03T00:00:00Z' })
      return Promise.resolve(undefined)
    })
    const router = await routerAt('/settings')
    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('飞书成员')
    expect(wrapper.text()).toContain('打开飞书协同工作台')

    await wrapper.findAll('button').find((button) => button.text() === '解绑飞书')!.trigger('click')
    await wrapper.get('.unbind-form input').setValue('current-password')
    await wrapper.get('.unbind-form').trigger('submit')
    await flushPromises()
    const call = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/auth/feishu/binding' && init?.method === 'DELETE')
    expect(JSON.parse(call![1].body)).toEqual({ currentPassword: 'current-password' })
  })

  it('adds a communication through the bound workbench without changing customer tags', async () => {
    useAuthStore().adopt(member())
    const customer = { id: 'customer-1', ownerDisplayName: '负责人', visibility: 'PRIVATE', fullName: '客户甲', school: '示例大学', grade: '2026', identityType: '在校生', focusDirections: [], intentionLevel: '高', urgencyLevel: '中', verificationStatus: '未核实', currentStatus: '跟进中', leadReviewStatus: 'PENDING', createdAt: '2026-09-03T00:00:00Z', updatedAt: '2026-09-03T00:00:00Z', version: 3, tags: [], dealState: 'NONE', canRequestDeal: true }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/feishu/workbench/bootstrap') return Promise.resolve({ bound: true, feishuDisplayName: '飞书成员', memberDisplayName: '测试成员', writable: true, viewer: false, capabilities: ['CUSTOMER_READ', 'INTERACTION_CREATE'] })
      if (path.startsWith('/api/v1/feishu/workbench/customers?')) return Promise.resolve({ items: [customer], page: 0, size: 30, totalElements: 1, totalPages: 1 })
      if (path === '/api/v1/feishu/workbench/customers/customer-1' && !init?.method) return Promise.resolve(customer)
      if (path === '/api/v1/feishu/workbench/tasks') return Promise.resolve([])
      if (path.endsWith('/interactions') && !init?.method) return Promise.resolve([{ id: 'interaction-old', customerId: 'customer-1', contactedAt: '2026-09-03T00:00:00Z', channel: '飞书', summary: '已有沟通', createdAt: '2026-09-03T00:00:00Z' }])
      if (path.endsWith('/interactions') && init?.method === 'POST') return Promise.resolve({ id: 'interaction-1' })
      return Promise.resolve(undefined)
    })
    const router = await routerAt('/feishu-workbench')
    const wrapper = mount(FeishuWorkbenchView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.get('.customer-row').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已有沟通')
    const forms = wrapper.findAll('form.compact-form')
    const interactionForm = forms.find((form) => form.text().includes('追加沟通时间线'))!
    await interactionForm.get('input[required]').setValue('微信')
    const requiredInputs = interactionForm.findAll('input[required]')
    await requiredInputs[1].setValue('客户已明确回复')
    await interactionForm.trigger('submit'); await flushPromises()

    const call = apiMock.mock.calls.find(([path, init]) => path.endsWith('/interactions') && init?.method === 'POST')
    expect(JSON.parse(call![1].body)).toMatchObject({ contactedAt: null, tagIds: null, tagUpdateRequested: false, interactionType: 'CUSTOMER_REPLY' })
  })

  it('separates the complete CRM and quick deal entries on the Feishu homepage', async () => {
    useAuthStore().adopt(member())
    apiMock.mockResolvedValue({ bound: true, feishuDisplayName: '飞书成员', memberDisplayName: '测试成员', writable: true })
    const router = await routerAt('/feishu')
    const wrapper = mount(FeishuHomeView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('进入完整 CRM')
    expect(wrapper.text()).toContain('快捷成单')
    expect(wrapper.get('a.crm-card').attributes('href')).toContain('/feishu/crm?returnTo=/dashboard')
    expect(wrapper.get('a.deal-card').attributes('href')).toBe('/feishu/deals')
  })

  it('restores a server-side quick deal draft and validates exact bonus allocation', async () => {
    useAuthStore().adopt(member())
    const customer = { id: 'customer-1', ownerMembershipId: 'member-1', ownerDisplayName: '测试成员', fullName: '客户甲', school: '示例大学', version: 3 }
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/feishu/workbench/bootstrap') return Promise.resolve({ bound: true, feishuDisplayName: '飞书成员', memberDisplayName: '测试成员', writable: true })
      if (path === '/api/v1/feishu/workbench/members') return Promise.resolve([{ membershipId: 'member-1', displayName: '测试成员' }, { membershipId: 'member-2', displayName: '协助成员' }])
      if (path === '/api/v1/feishu/workbench/deal-drafts/current') return Promise.resolve({ id: 'draft-1', customerId: 'customer-1', customerName: '客户甲', ownerMembershipId: 'member-1', ownerDisplayName: '测试成员', primaryMembershipId: 'member-1', assistantMembershipIds: [], orderAmountCents: 300000, totalBonusCents: 50000, allocations: [{ membershipId: 'member-1', bonusCents: 50000 }], status: 'ACTIVE', version: 2, expiresAt: '2026-09-07T13:00:00Z' })
      if (path === '/api/v1/feishu/workbench/deal-leaderboard') return Promise.resolve({ from: '2026-09-01', to: '2026-09-30', historicalDealCount: 0, historicalOrderAmountCents: 0, historicalBonusCents: 0, entries: [] })
      if (path === '/api/v1/feishu/workbench/customers/customer-1') return Promise.resolve(customer)
      if (path === '/api/v1/feishu/workbench/customers/customer-1/collaboration') return Promise.resolve({ canTransferOwnership: true, transferCandidates: [] })
      return Promise.resolve(undefined)
    })
    const router = await routerAt('/feishu/deals')
    const wrapper = mount(FeishuDealsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('客户甲')
    expect(wrapper.text()).toContain('已分配 500 元 / 共 500 元')
    expect(wrapper.text()).toContain('确认后立即生效')
  })
})
