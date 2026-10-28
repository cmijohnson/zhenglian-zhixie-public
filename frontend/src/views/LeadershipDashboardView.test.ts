import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LeadershipDashboardView from './LeadershipDashboardView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({ api: apiMock }))

const dashboard = {
  generatedAt: '2026-07-25T05:30:00Z',
  lastBusinessActivityAt: '2026-07-25T05:29:00Z',
  startDate: '2026-06-26',
  endDate: '2026-07-25',
  days: 30,
  administrator: true,
  teams: [{ id: 'team-1', name: '营销一组' }],
  members: [{ membershipId: 'member-1', displayName: '成员甲', username: 'a', teamNames: '营销一组' }],
  headline: {
    totalCustomers: 128,
    addedFriendCustomers: 96,
    trustedFriendCustomers: 61,
    qualifiedFriendCustomers: 35,
    todayNewCustomers: 8,
    todayQualifiedCustomers: 3,
    addedRate: 0.75,
    trustRate: 0.6354,
    qualifiedRate: 0.5738,
  },
  recentCustomerActivities: [{
    customerId: 'customer-1',
    fullName: '陈同学',
    ownerName: '成员甲',
    teamName: '营销一组',
    currentStatus: '跟进中',
    intentionLevel: '高',
    createdAt: '2026-07-24T02:00:00Z',
    updatedAt: '2026-07-25T04:00:00Z',
  }],
  rankings: [{
    membershipId: 'member-1',
    displayName: '成员甲',
    teamNames: '营销一组',
    totalCustomers: 36,
    addedFriendCustomers: 30,
    trustedFriendCustomers: 21,
    qualifiedFriendCustomers: 14,
    qualityACustomers: 9,
    todayCustomers: 2,
    assignedTasks: 10,
    completedTasks: 8,
    taskCompletionRate: 0.8,
  }],
  dailyConversions: [
    { date: '2026-07-24', addedFriendCustomers: 4, trustedFriendCustomers: 2, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 32 },
    { date: '2026-07-25', addedFriendCustomers: 6, trustedFriendCustomers: 4, qualifiedFriendCustomers: 3, cumulativeQualifiedCustomers: 35 },
  ],
  customerQuality: {
    classifiedCustomers: 91,
    unclassifiedCustomers: 37,
    levels: [
      { code: 'A', label: 'A类用户', description: '高质量客户，有急迫需求且明确需要此方向产品', color: '#16A34A', customerCount: 23, share: 0.1797 },
      { code: 'B', label: 'B类用户', description: '对产品有一定兴趣，但目前阶段暂无迫切需求', color: '#F59E0B', customerCount: 41, share: 0.3203 },
      { code: 'C', label: 'C类用户', description: '对产品需求很低，或当前购买、支付能力不足', color: '#64748B', customerCount: 27, share: 0.2109 },
    ],
  },
  taskSummary: {
    assignmentCount: 10,
    pendingCount: 1,
    inProgressCount: 1,
    submittedCount: 0,
    completedCount: 8,
    returnedCount: 0,
    overdueCount: 1,
    completionRate: 0.8,
  },
  tasks: [{
    taskId: 'task-1',
    title: '本周客户复盘',
    dueAt: '2026-07-28T10:00:00Z',
    status: 'PUBLISHED',
    assignmentCount: 10,
    completedCount: 8,
    submittedCount: 0,
    completionRate: 0.8,
    overdue: false,
  }],
  recentTaskPublications: [{
    taskId: 'task-1',
    title: '本周客户复盘',
    distributionType: 'MANDATORY',
    startAt: '2026-07-24T02:00:00Z',
    dueAt: '2026-07-28T10:00:00Z',
    createdAt: '2026-07-24T01:00:00Z',
    publisherName: '运营中心',
    assignmentCount: 10,
  }],
  sources: [{
    source: '社群转介绍',
    customerCount: 52,
    addedFriendCustomers: 44,
    trustedFriendCustomers: 31,
    qualifiedFriendCustomers: 18,
    share: 0.4063,
    addedRate: 0.8462,
    trustRate: 0.7045,
    qualifiedRate: 0.5806,
  }],
  displayMessages: [],
  featuredGrade: '2026',
  aClassStale: {
    enabled: true, thresholdHours: 72, customerCount: 3, unassignedCount: 0,
    owners: [{ membershipId: 'member-1', displayName: '成员甲', customerCount: 3 }],
    calculatedAt: '2026-07-25T05:30:00Z',
  },
  schoolComparison: {
    featuredGrade: '2026', jiangsuUniversity: 70,
    jiangsuUniversityOfScienceAndTechnology: 58, difference: 12,
    leader: 'JIANGSU_UNIVERSITY',
  },
  tickerItems: [
    { id: 'a-class-stale', type: 'FOLLOW_UP_RISK', priority: 'URGENT', text: 'A 类客户超过 3 天未有效更新：3 人' },
    { id: 'featured-school-comparison', type: 'SCHOOL_COMPARISON', priority: 'IMPORTANT', text: '2026 级客户：示例大学 70 人，示例科技大学 58 人' },
  ],
  screenSettings: {
    animationsEnabled: true,
    standbyEnabled: true,
    idleTimeoutMinutes: 20,
    sceneDurationSeconds: 12,
    eventDurationSeconds: 7,
    motionIntensity: 'CINEMATIC',
    sceneOrder: ['time', 'culture', 'briefing', 'trend', 'champions', 'funnel', 'mission', 'followUpRisk', 'schoolComparison'],
    enabledScenes: ['time', 'culture', 'briefing', 'trend', 'champions', 'funnel', 'mission', 'followUpRisk', 'schoolComparison'],
    enabledEvents: [
      'zeroBreakthrough', 'customerAdded', 'customerUpdated',
      'milestone', 'trendSurge', 'conversionStage',
      'taskPublished', 'taskMilestone', 'rankingChange', 'teamMomentum',
    ],
    featuredGrade: '2026',
    version: 0,
  },
}

describe('leadership dashboard', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockResolvedValue(dashboard)
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })))
  })

  afterEach(() => {
    window.history.pushState({}, '', '/')
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders leadership KPIs, ranking, conversion, task and source analysis', async () => {
    const wrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/leadership-dashboard?days=30')
    expect(wrapper.text()).toContain('英格营销数据指挥中心')
    expect(wrapper.text()).toContain('128')
    expect(wrapper.text()).toContain('成员甲')
    expect(wrapper.text()).toContain('个人业绩五维排行榜')
    expect(wrapper.text()).toContain('总添加客户排行榜')
    expect(wrapper.text()).toContain('总添加客户日排行榜')
    expect(wrapper.text()).toContain('A类客户排行榜')
    expect(wrapper.text()).not.toContain('总客户排行榜')
    expect(wrapper.text()).not.toContain('A类客户日排行榜')
    expect(wrapper.text()).not.toContain('添加好友排行榜')
    expect(wrapper.text()).not.toContain('筛选好友排行榜')
    expect(wrapper.text()).toContain('仅统计 2026 级')
    expect(wrapper.find('[data-testid="leadership-ticker"]').text()).toContain('A 类客户超过 3 天')
    expect(wrapper.find('[data-testid="leadership-ticker"]').text()).toContain('示例大学 70 人')
    expect(wrapper.text()).toContain('统计当前全部任务')
    expect(wrapper.text()).toContain('每日好友转化 K 线')
    expect(wrapper.text()).toContain('客户质量分层')
    expect(wrapper.text()).toContain('A类用户')
    expect(wrapper.text()).toContain('未评级 37')
    expect(wrapper.findAll('.quality-tier-grid article')).toHaveLength(4)
    expect(wrapper.findAll('.leadership-kpis .kpi-card')).toHaveLength(1)
    expect(wrapper.find('[data-testid="leadership-friend-stage-totals"]').text()).toContain('添加好友客户96')
    expect(wrapper.find('[data-testid="leadership-friend-stage-totals"]').text()).toContain('信任好友客户61')
    expect(wrapper.find('[data-testid="leadership-friend-stage-totals"]').text()).toContain('筛选好友客户35')
    expect(wrapper.text()).toContain('本周客户复盘')
    expect(wrapper.text()).toContain('社群转介绍')
    expect(wrapper.text()).toContain('筛选 18 / 58.1%')
    const rankCards = wrapper.findAll('.rank-metric-card')
    expect(rankCards).toHaveLength(5)
    expect(rankCards.map((card) => card.find('h3').text())).toEqual([
      '总添加客户排行榜',
      '总添加客户日排行榜',
      '信任好友排行榜',
      'A类客户排行榜',
      '任务完成排行榜',
    ])
    expect(wrapper.find('.rank-tabs').exists()).toBe(false)
    expect(wrapper.find('button.leadership-export').exists()).toBe(true)
    expect(wrapper.find('a.leadership-tv-link').attributes('href')).toBe('/leadership-screen')
    expect(wrapper.find('svg[aria-label="每日好友转化人数K线"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows a stable error state when aggregation fails', async () => {
    apiMock.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(wrapper.text()).toContain('数据大屏暂时无法加载')
    wrapper.unmount()
  })

  it('enters rotating standby mode after twenty minutes without business activity', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-25T05:30:00Z'))
    window.history.pushState({}, '', '/leadership-screen')
    apiMock.mockResolvedValue({
      ...dashboard,
      lastBusinessActivityAt: '2026-07-25T05:30:00Z',
    })
    const wrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(wrapper.find('[data-testid="leadership-experience"]').exists()).toBe(false)
    vi.advanceTimersByTime(20 * 60_000 + 1_000)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="leadership-experience"]').exists()).toBe(true)
    expect(wrapper.find('.standby-scene-time').exists()).toBe(true)

    vi.advanceTimersByTime(12_000)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.standby-scene-culture').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders the full-screen mission brief for a newly published task preview', async () => {
    window.history.pushState({}, '', '/leadership-screen?previewEvent=taskPublished')
    const wrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(wrapper.find('.event-taskPublished').exists()).toBe(true)
    expect(wrapper.find('.event-task-published').exists()).toBe(true)
    expect(wrapper.text()).toContain('新任务发布')
    expect(wrapper.text()).toContain('本周客户增长冲刺')
    expect(wrapper.text()).toContain('发布人 · 运营中心')
    expect(wrapper.text()).toContain('覆盖成员 · 18 人')
    wrapper.unmount()
  })

  it('renders customer addition and profile update previews with useful details', async () => {
    window.history.pushState({}, '', '/leadership-screen?previewEvent=customerAdded')
    const addedWrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(addedWrapper.find('.event-customerAdded').exists()).toBe(true)
    expect(addedWrapper.text()).toContain('新客户接入')
    expect(addedWrapper.text()).toContain('陈同学')
    expect(addedWrapper.text()).toContain('王顾问 · 营销一组 · 初次接触')
    addedWrapper.unmount()

    window.history.pushState({}, '', '/leadership-screen?previewEvent=customerUpdated')
    const updatedWrapper = mount(LeadershipDashboardView)
    await flushPromises()

    expect(updatedWrapper.find('.event-customerUpdated').exists()).toBe(true)
    expect(updatedWrapper.text()).toContain('客户档案更新')
    expect(updatedWrapper.text()).toContain('SYNC')
    updatedWrapper.unmount()
  })
})
