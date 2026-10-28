import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CustomerClassificationPanel from '../components/CustomerClassificationPanel.vue'
import AdminMarketGovernancePanel from '../components/AdminMarketGovernancePanel.vue'
import CustomerPoolView from './CustomerPoolView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {},
}))

const uuid = '00000000-0000-4000-8000-000000000001'

function requestBody(path: string) {
  const call = apiMock.mock.calls.find(([calledPath, init]) => calledPath === path && init?.body)
  expect(call, `missing request for ${path}`).toBeTruthy()
  return JSON.parse(call![1].body as string)
}

beforeEach(() => {
  apiMock.mockReset()
  Object.defineProperty(globalThis.crypto, 'randomUUID', {
    configurable: true,
    value: vi.fn(() => uuid),
  })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('customer classification panel', () => {
  const history = [{
    id: 'classification-1', revision: 1, level: 'B', recognitionReason: '持续了解方案',
    potentialDemand: '转专业咨询', aSignals: [], evidenceInteractionIds: ['interaction-1'],
    source: 'MEMBER_DECLARATION', createdAt: '2026-08-17T01:00:00Z',
  }]
  const interactions = [{
    id: 'interaction-1', customerId: 'customer-1', contactedAt: '2026-08-17T00:30:00Z',
    channel: 'QQ', summary: '客户主动询问课程安排', createdAt: '2026-08-17T00:35:00Z',
  }]

  it('keeps write controls hidden for read-only viewers', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path.endsWith('/classifications')) return Promise.resolve(history)
      if (path.endsWith('/deadline-shadow')) return Promise.resolve({ classificationLevel: 'B', computedDeadline: '2026-08-21T01:00:00Z', mode: 'SHADOW' })
      return Promise.resolve({})
    })
    const wrapper = mount(CustomerClassificationPanel, {
      props: { customerId: 'customer-1', interactions, canEdit: false },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('客户分级与归属期限')
    expect(wrapper.text()).toContain('持续了解方案')
    expect(wrapper.findAll('button').some((button) => button.text().includes('变更分级'))).toBe(false)
  })

  it('submits structured A-class facts instead of a plain label', async () => {
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path.endsWith('/classifications') && init?.method === 'POST') return Promise.resolve({})
      if (path.endsWith('/classifications')) return Promise.resolve(history)
      if (path.endsWith('/deadline-shadow')) return Promise.resolve({ classificationLevel: 'B', mode: 'SHADOW' })
      return Promise.resolve({})
    })
    const wrapper = mount(CustomerClassificationPanel, {
      props: { customerId: 'customer-1', interactions, canEdit: true },
    })
    await flushPromises()
    await wrapper.get('button.ghost-button').trigger('click')
    await wrapper.get('input[type="radio"][value="A"]').setValue(true)
    await flushPromises()
    const textareas = wrapper.findAll('.classification-form textarea')
    await textareas[0].setValue('客户已明确报名时间')
    await textareas[1].setValue('需要转专业课程方案')
    await textareas[2].setValue('主动询问适配方案\n给出明确时间表')
    await wrapper.get('fieldset input[type="checkbox"]').setValue(true)
    await wrapper.get('form.classification-form').trigger('submit')
    await flushPromises()

    expect(requestBody('/api/v1/customers/customer-1/classifications')).toEqual({
      level: 'A',
      recognitionReason: '客户已明确报名时间',
      potentialDemand: '需要转专业课程方案',
      aSignals: ['主动询问适配方案', '给出明确时间表'],
      evidenceInteractionIds: ['interaction-1'],
    })
  })
})

describe('customer public pool', () => {
  it('renders only masked context and sends an idempotent temporary claim', async () => {
    const rows = [{
      id: 'pool-1', customerId: 'customer-1', poolType: 'AB', releaseReason: '归属期限已到',
      customerName: '客户甲',
      school: '示例大学', grade: '2026', source: '新生群', demandSummary: '希望了解转专业',
      timeline: [{ occurredAt: '2026-08-16T01:00:00Z', channel: 'QQ', description: '客户确认有咨询需求' }],
      availableAt: '2026-08-17T01:00:00Z', canClaim: true, status: 'OPEN',
      phone: '13800000000', qqId: '12345678',
    }, {
      id: 'pool-2', customerId: 'customer-2', poolType: 'AB', releaseReason: '冷却期',
      customerName: '客户乙',
      school: '示例科技大学', grade: '2025', source: '抖音', demandSummary: '待核实', timeline: [],
      availableAt: '2026-08-17T01:00:00Z', canClaim: false, status: 'OPEN',
    }]
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customer-pools?type=AB') return Promise.resolve(rows)
      if (path === '/api/v1/second-line-recruitments') return Promise.resolve([])
      if (path === '/api/v1/customer-pools/pool-1/claims' && init?.method === 'POST') {
        return Promise.resolve({ customerId: 'customer-1', status: 'TEMPORARY', expiresAt: '2026-08-18T01:00:00Z', idempotentReplay: false })
      }
      return Promise.resolve([])
    })
    const wrapper = mount(CustomerPoolView)
    await flushPromises()

    expect(wrapper.text()).toContain('客户姓名、简要概述和脱敏时间线')
    expect(wrapper.text()).toContain('客户甲')
    expect(wrapper.text()).toContain('客户确认有咨询需求')
    expect(wrapper.text()).not.toContain('13800000000')
    expect(wrapper.text()).not.toContain('12345678')
    const buttons = wrapper.findAll('.pool-grid .primary-button')
    expect(buttons[1].attributes('disabled')).toBeDefined()
    await buttons[0].trigger('click')
    await flushPromises()

    expect(requestBody('/api/v1/customer-pools/pool-1/claims')).toEqual({ idempotencyKey: uuid })
    expect(wrapper.text()).toContain('已获得 24 小时临时跟进权')
  })

  it('claims an A-class second-line role through its constrained endpoint', async () => {
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customer-pools?type=AB') return Promise.resolve([])
      if (path === '/api/v1/second-line-recruitments') return Promise.resolve([{
        id: 'recruitment-1', customerId: 'customer-1', customerName: '客户甲', school: '示例大学', grade: '2026',
        demandSummary: '重点推进', requiredCount: 1, claimedCount: 0,
        volunteerUntil: '2026-08-18T01:00:00Z', status: 'OPEN',
      }])
      if (path === '/api/v1/second-line-recruitments/recruitment-1/claim' && init?.method === 'POST') return Promise.resolve({})
      return Promise.resolve([])
    })
    const wrapper = mount(CustomerPoolView)
    await flushPromises()
    await wrapper.get('.recruitment-grid .primary-button').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/second-line-recruitments/recruitment-1/claim', { method: 'POST' })
    expect(wrapper.text()).toContain('直接联系客户前，请在客户档案中申请授权')
  })
})

describe('administrator market governance panel', () => {
  function mockGovernanceApi(path: string, init?: RequestInit) {
    if (init?.method === 'POST') return Promise.resolve({})
    if (path === '/api/v1/admin/governance/overview') return Promise.resolve({ deadlineDue: 2, poolOpen: 3, temporaryClaims: 1, secondLineRequired: 4, contactAuthorizations: 1, takeoverTasks: 1, classificationReviews: 1, wonPending: 1 })
    if (path === '/api/v1/admin/market/classification-reviews?recommendedOnly=false') return Promise.resolve([{
      classificationId: 'classification-1', customerId: 'customer-1', customerName: '客户甲', level: 'A',
      submittedByDisplayName: '员工甲', recommended: true, selected: true, reviewStatus: 'PENDING', createdAt: '2026-08-17T01:00:00Z',
    }])
    if (path === '/api/v1/admin/market/classification-anomalies') return Promise.resolve([{ membershipId: 'member-1', displayName: '员工甲', totalDecisions: 10, aDecisions: 8, aRate: 0.8, teamMedianRate: 0.3, abnormal: true }])
    if (path === '/api/v1/admin/market/deadline-rules') return Promise.resolve([{ id: 'rule-1', version: 1, effectiveFrom: '2026-08-01T00:00:00Z', mode: 'SHADOW', aPeakHours: 72, bPeakHours: 96, aOffPeakHours: 168, bOffPeakHours: 216, weekendsCount: true }])
    if (path === '/api/v1/admin/deal-requests?status=PENDING&page=0&size=100') return Promise.resolve({ content: [{ id: 'deal-1', customerId: 'customer-1', customerName: '客户甲', teamName: '一队', ownerDisplayName: '负责人甲', applicantMembershipId: 'member-1', applicantDisplayName: '申请人甲', status: 'PENDING', requestedAt: '2026-08-17T02:00:00Z', version: 3 }], page: 0, size: 100, totalElements: 1, totalPages: 1 })
    return Promise.resolve({})
  }

  it('renders governance queues and posts auditable review and minimal deal approval', async () => {
    apiMock.mockImplementation(mockGovernanceApi)
    const wrapper = mount(AdminMarketGovernancePanel, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('A 类抽查与异常')
    expect(wrapper.text()).toContain('A 类率 80.0%')
    expect(wrapper.text()).toContain('成单审核')
    expect(wrapper.text()).not.toContain('成单与交叉单确认')
    expect(wrapper.text()).not.toContain('正式报价后主辅调整')
    await wrapper.findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await flushPromises()
    expect(requestBody('/api/v1/admin/market/classification-reviews/classification-1/review')).toEqual({
      decision: 'CONFIRMED', note: '管理员复核确认',
    })

    await wrapper.findAll('button').find((button) => button.text() === '确认成单')!.trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/deal-requests/deal-1/approve', {
      method: 'POST', headers: { 'If-Match-Version': '3' },
    })
  })

  it('rejects a deal request without asking for obsolete quote or participant fields', async () => {
    apiMock.mockImplementation(mockGovernanceApi)
    const wrapper = mount(AdminMarketGovernancePanel, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '驳回')!.trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(
      '/api/v1/admin/deal-requests/deal-1/reject',
      { method: 'POST', headers: { 'If-Match-Version': '3' } },
    )
    expect(apiMock.mock.calls.some(([path]) => String(path).includes('/business-cycles'))).toBe(false)
  })

  it('creates a future deadline-rule version with parsed peak and pause ranges', async () => {
    apiMock.mockImplementation(mockGovernanceApi)
    const wrapper = mount(AdminMarketGovernancePanel, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('新版本'))!.trigger('click')
    await wrapper.get('.rule-form select').setValue('ENFORCED')
    const textareas = wrapper.findAll('.rule-form textarea')
    await textareas[0].setValue('2026-06-01 ~ 2026-09-15')
    await textareas[1].setValue('2026-06-20 ~ 2026-06-28\n2026-12-31 ~ 2027-01-02')
    await wrapper.get('form.rule-form').trigger('submit')
    await flushPromises()

    const body = requestBody('/api/v1/admin/market/deadline-rules')
    expect(body).toMatchObject({
      mode: 'ENFORCED',
      aPeakHours: 72,
      bPeakHours: 96,
      aOffPeakHours: 168,
      bOffPeakHours: 216,
      weekendsCount: true,
      peakPeriods: [{ start: '2026-06-01', end: '2026-09-15' }],
      pausePeriods: [
        { start: '2026-06-20', end: '2026-06-28' },
        { start: '2026-12-31', end: '2027-01-02' },
      ],
    })
    expect(body.effectiveFrom).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })
})
