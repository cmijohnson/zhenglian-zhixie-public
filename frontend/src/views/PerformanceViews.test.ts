import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PerformanceView from './PerformanceView.vue'
import AdminPerformanceView from './AdminPerformanceView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {
    constructor(public status: number, public body: { code: string; message: string }) {
      super(body.message)
    }
  },
}))

const rule = {
  id: 'rule-1', versionNo: 1, effectiveFrom: '2026-08-01', dailyBaseline: 14,
  calendarMode: 'WEEKDAYS', weekdays: [1, 2, 3, 4, 5], includedDates: [], excludedDates: [],
  submissionOpensAt: '09:00:00', submissionClosesAt: '22:00:00', shortfallMode: 'STATUS_ONLY',
  ordinaryPoints: 1, deepPoints: 2, eventTiers: [{ label: 'Pro', minParticipants: 50, points: 8 }],
  createdAt: '2026-08-01T00:00:00Z',
}

const today = {
  date: '2026-08-04', participant: true, dayStatus: 'OPEN', submissionOpen: true,
  opensAt: '09:00:00', closesAt: '22:00:00', rule,
  score: { businessPoints: 10, logisticsPoints: 2, correctionPoints: 0, earnedPoints: 12,
    baseline: 14, shortfall: 2, deduction: 0, finalPoints: 12 },
  pendingClaims: 1, pendingActivities: 0, approvedDeepCount: 3, pendingDeepCount: 1,
  availableInteractions: [{ interactionId: 'interaction-1', customerId: 'customer-1', customerName: '客户甲',
    contactedAt: '2026-08-04T08:00:00Z', channel: '微信', summary: '已确认下一步安排' }],
  claims: [{ id: 'claim-old', membershipId: 'member-1', memberDisplayName: '成员甲', interactionId: 'old',
    customerId: 'customer-1', customerName: '客户甲', performanceDate: '2026-08-04', requestedType: 'DEEP',
    status: 'PENDING', evidence: [{ id: 'evidence-1', ownerType: 'INTERACTION', ownerId: 'claim-old',
      fileName: '交流截图.png', contentType: 'image/png', fileSize: 8, createdAt: '2026-08-04T08:00:00Z' }], version: 1 }],
  activities: [{ id: 'activity-1', organizerMembershipId: 'member-1', organizerDisplayName: '成员甲',
    eventDate: '2026-08-04', title: '迎新活动', goal: '完成现场咨询', expectedParticipants: 60,
    description: '活动说明', status: 'APPROVED', allocations: [], evidence: [],
    createdAt: '2026-08-04T08:00:00Z', version: 1 }],
  ledger: [{ id: 'ledger-1', membershipId: 'member-1', memberDisplayName: '成员甲', performanceDate: '2026-08-04',
    category: 'INTERACTION', sourceId: 'claim-old', points: 2, description: '深度交流审核通过',
    createdAt: '2026-08-04T08:00:00Z' }],
}

describe('performance center views', () => {
  beforeEach(() => apiMock.mockReset())

  it('shows audited personal scoring and submits an evidence-backed interaction claim', async () => {
    apiMock.mockImplementation((path: string) => {
      if (!path) return Promise.resolve({})
      if (path === '/api/v1/performance/today') return Promise.resolve(structuredClone(today))
      if (path.startsWith('/api/v1/performance/leaderboard')) return Promise.resolve([
        { rank: 1, membershipId: 'member-1', displayName: '成员甲', teamName: '营销一组',
          earnedPoints: 12, finalPoints: 12, scoredDays: 1, achievedDays: 0 },
      ])
      if (path.startsWith('/api/v1/performance/history')) return Promise.resolve([
        { date: '2026-08-04', earnedPoints: 12, finalPoints: 12, baseline: 14, settled: false },
      ])
      if (path === '/api/v1/performance/claims') return Promise.resolve({ id: 'claim-new' })
      return Promise.resolve({})
    })
    const wrapper = mount(PerformanceView)
    await flushPromises()

    expect(wrapper.text()).toContain('12')
    expect(wrapper.text()).toContain('/ 14 分 baseline')
    expect(wrapper.text()).toContain('交流截图.png')
    expect(wrapper.text()).toContain('本月积分明细与趋势')
    expect(wrapper.text()).toContain('营销一组')

    await wrapper.get('.performance-claim-form select').setValue('interaction-1')
    const fileInput = wrapper.get('.performance-claim-form input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'claim.png', { type: 'image/png' })],
    })
    await fileInput.trigger('change')
    await wrapper.get('.performance-claim-form').trigger('submit')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/performance/claims', expect.objectContaining({ method: 'POST' }))
    expect(apiMock).toHaveBeenCalledWith('/api/v1/performance/claims/claim-new/evidence', expect.objectContaining({ method: 'POST' }))
    expect(apiMock).toHaveBeenCalledWith('/api/v1/performance/claims/claim-new/submit', expect.objectContaining({ method: 'POST' }))
  })

  it('renders administrator configuration, review and settlement controls', async () => {
    const participant = { id: 'participant-1', membershipId: 'member-1', displayName: '成员甲', teamName: '营销一组',
      startsOn: '2026-08-01', activeToday: true, version: 0 }
    const pendingClaim = { ...today.claims[0], memberDisplayName: '成员甲' }
    const day = { date: '2026-08-04', status: 'REVIEWING', rule, pendingClaims: 1, pendingActivities: 0,
      members: [{ membershipId: 'member-1', displayName: '成员甲', teamName: '营销一组', score: today.score, settled: false }], version: 2 }
    apiMock.mockImplementation((path: string) => {
      if (!path) return Promise.resolve({})
      if (path === '/api/v1/performance/admin/rules') return Promise.resolve([rule])
      if (path === '/api/v1/performance/admin/participants') return Promise.resolve([participant])
      if (path === '/api/v1/admin/members') return Promise.resolve([
        { membershipId: 'member-1', displayName: '成员甲', status: 'ACTIVE', roles: ['MEMBER'], teams: ['营销一组'], teamIds: ['team-1'] },
      ])
      if (path === '/api/v1/admin/teams') return Promise.resolve([{ id: 'team-1', name: '营销一组', active: true }])
      if (path === '/api/v1/performance/claims?status=PENDING') return Promise.resolve([pendingClaim])
      if (path === '/api/v1/performance/activities') return Promise.resolve([])
      if (path.startsWith('/api/v1/performance/leaderboard')) return Promise.resolve([
        { rank: 1, membershipId: 'member-1', displayName: '成员甲', teamId: 'team-1', teamName: '营销一组',
          earnedPoints: 12, finalPoints: 12, scoredDays: 1, achievedDays: 0 },
      ])
      if (path.startsWith('/api/v1/performance/admin/days/')) return Promise.resolve(day)
      return Promise.resolve({})
    })
    const wrapper = mount(AdminPerformanceView)
    await flushPromises()

    expect(wrapper.text()).toContain('创建未来规则版本')
    expect(wrapper.text()).toContain('每日 baseline')
    expect(wrapper.text()).toContain('成员甲 · 客户甲')
    expect(wrapper.text()).toContain('待审核交流 1')
    expect(wrapper.get('.settlement-summary button').attributes('disabled')).toBeDefined()

    const monthInput = wrapper.get('input[type="month"]')
    const settlementDateInput = wrapper.get('.panel-title input[type="date"]')
    expect(monthInput.element).not.toBe(settlementDateInput.element)
    expect((settlementDateInput.element as HTMLInputElement).value).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    apiMock.mockClear()
    await monthInput.setValue('2026-02')
    await monthInput.trigger('change')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/performance/leaderboard?from=2026-02-01&to=2026-02-28')
    expect((settlementDateInput.element as HTMLInputElement).value).not.toBe('2026-02')

    await wrapper.get('.performance-review-list .soft-button').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/performance/claims/claim-old/review', expect.objectContaining({ method: 'POST' }))
  })
})
