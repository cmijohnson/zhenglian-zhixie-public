import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppLayout from '../components/AppLayout.vue'
import { useAuthStore } from '../stores/auth'
import CircleView from './CircleView.vue'
import CustomerFormView from './CustomerFormView.vue'
import CustomerDetailView from './CustomerDetailView.vue'
import CustomersView from './CustomersView.vue'
import ContentTasksView from './ContentTasksView.vue'
import DailyReportView from './DailyReportView.vue'
import DashboardView from './DashboardView.vue'
import AdminMembersView from './AdminMembersView.vue'
import AdminTeamsView from './AdminTeamsView.vue'
import RegisterView from './RegisterView.vue'
import TaskCenterView from './TaskCenterView.vue'
import TeamDataView from './TeamDataView.vue'
import AnnouncementsView from './AnnouncementsView.vue'
import AdminAssistantSettingsView from './AdminAssistantSettingsView.vue'
import AdminLeadershipScreenSettingsView from './AdminLeadershipScreenSettingsView.vue'
import ReleasesView from './ReleasesView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  ApiError: class ApiError extends Error {
    constructor(public status: number, public body: { message: string }) {
      super(body.message)
    }
  },
}))

function authenticate(roles: string[]) {
  const auth = useAuthStore()
  auth.user = {
    userId: 'user-1', membershipId: 'member-1', organizationId: 'org-1',
    username: 'mock-user', displayName: '模拟成员', roles, passwordChangeRequired: false,
  }
  auth.checked = true
  return auth
}

function customer(index: number) {
  return {
    id: `customer-${index}`, ownerMembershipId: 'member-1', ownerDisplayName: '负责人甲',
    createdByMembershipId: 'member-2', creatorDisplayName: '录入人乙', visibility: 'PRIVATE',
    fullName: `Mock客户-${String(index).padStart(3, '0')}`, phone: `1380000${String(index).padStart(4, '0')}`,
    identityType: '在校生', focusDirections: ['升学规划'], intentionLevel: index % 2 ? '高' : '中',
    urgencyLevel: '中', verificationStatus: '未核实', currentStatus: '跟进中',
    leadReviewStatus: 'PENDING',
    createdAt: '2026-07-19T00:00:00Z', updatedAt: '2026-07-19T00:00:00Z', version: 0, tags: [],
  }
}

describe('large mocked workflow views', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    apiMock.mockReset()
    window.sessionStorage.clear()
  })

  afterEach(() => vi.clearAllMocks())

  it('renders mandatory, assigned and self-claim task distribution for an administrator', async () => {
    authenticate(['ORG_ADMIN'])
    const now = new Date().toISOString()
    const due = new Date(Date.now() + 86_400_000).toISOString()
    const tasks = [
      {
        id: 'mandatory', title: '每日必须任务', description: '全员完成', distributionType: 'MANDATORY',
        startAt: now, dueAt: due, status: 'PUBLISHED', assignmentCount: 80, completedCount: 30,
        overdue: false, claimable: false,
        myAssignment: { id: 'a-1', membershipId: 'member-1', memberDisplayName: '模拟成员', assignmentSource: 'MANDATORY', status: 'PENDING' },
      },
      {
        id: 'assigned', title: '指定人员任务', description: '精准派发', distributionType: 'ASSIGNED',
        startAt: now, dueAt: due, status: 'PUBLISHED', assignmentCount: 12, completedCount: 5,
        overdue: false, claimable: false,
      },
      {
        id: 'claim', title: '可选领取任务', description: '任务池', distributionType: 'SELF_CLAIM',
        startAt: now, dueAt: due, status: 'PUBLISHED', maxClaimants: 10, assignmentCount: 4,
        completedCount: 1, overdue: false, claimable: true,
      },
    ]
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/tasks/matrix') return Promise.resolve(tasks.map((task, index) => ({
        task,
        assignments: index === 0 ? [
          { id: 'matrix-pending', membershipId: 'member-2', memberDisplayName: '成员2', assignmentSource: 'MANDATORY', status: 'PENDING' },
          { id: 'matrix-completed', membershipId: 'member-3', memberDisplayName: '成员3', assignmentSource: 'MANDATORY', status: 'COMPLETED' },
        ] : [],
      })))
      if (path === '/api/v1/admin/members') {
        return Promise.resolve(Array.from({ length: 80 }, (_, index) => ({
          membershipId: `member-${index}`, displayName: `成员${index}`, status: 'ACTIVE', roles: ['MEMBER'],
        })))
      }
      return Promise.resolve(undefined)
    })

    const wrapper = mount(TaskCenterView, { global: { plugins: [pinia] } })
    await flushPromises()

    expect(wrapper.text()).toContain('每日必须任务')
    expect(wrapper.text()).toContain('可选领取任务')
    expect(wrapper.text()).toContain('强制性')
    expect(wrapper.text()).toContain('派发性')
    expect(wrapper.text()).toContain('自领取性')
    expect(wrapper.find('[data-testid="task-matrix"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('未完成')
    expect(wrapper.text()).toContain('已完成')
    await wrapper.get('.page-header .primary-button').trigger('click')
    await wrapper.get('.task-create select').setValue('ASSIGNED')
    expect(wrapper.findAll('.member-picker label')).toHaveLength(80)
    expect(wrapper.find('select').text()).toContain('强制性 · 全员自动分配')
  })

  it('lets a team leader review only the assignments returned by the scoped API', async () => {
    authenticate(['TEAM_MANAGER'])
    const now = new Date().toISOString()
    const due = new Date(Date.now() + 86_400_000).toISOString()
    const task = { id: 'team-task', title: '团队每日任务', description: '提交后由团队长验收', distributionType: 'MANDATORY', startAt: now, dueAt: due, status: 'PUBLISHED', assignmentCount: 8, completedCount: 3, overdue: false, claimable: false }
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/tasks') return Promise.resolve([task])
      if (path === '/api/v1/tasks/team-task/assignments') return Promise.resolve([
        { id: 'assignment-a', membershipId: 'team-member-a', memberDisplayName: '本团队队员', assignmentSource: 'MANDATORY', status: 'SUBMITTED', resultSummary: '已完成日报' },
      ])
      return Promise.resolve(undefined)
    })
    const wrapper = mount(TaskCenterView, { global: { plugins: [pinia] } })
    await flushPromises()
    expect(wrapper.text()).toContain('本团队任务验收')
    expect(wrapper.find('.page-header .primary-button').exists()).toBe(false)
    await wrapper.get('.task-admin-row .soft-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本团队队员')
    expect(wrapper.text()).toContain('已完成日报')
  })

  it('renders 120 content records, organization progress and review queue from mocks', async () => {
    const posts = Array.from({ length: 120 }, (_, index) => ({
      id: `post-${index}`, membershipId: 'member-1', memberDisplayName: '模拟成员',
      platform: index % 2 ? 'DOUYIN' : 'XIAOHONGSHU', publishDate: '2026-07-19',
      directionId: 'direction-1', directionTitle: '新生成长', topic: `内容主题-${index}`,
      contentUrl: `https://example.com/${index}`, viewCount: index * 100, likeCount: index * 10,
      favoriteCount: index, reviewStatus: index < 20 ? 'SUBMITTED' : 'VALID',
    }))
    apiMock.mockResolvedValue({
      campaign: { name: '2026夏季任务', startDate: '2026-07-15', endDate: '2026-08-20',
        xiaohongshuTarget: 20, douyinTarget: 20, today: '2026-07-19', active: true },
      directions: [{ id: 'direction-1', title: '新生成长', description: '内容说明', active: true }],
      myProgress: { xiaohongshuValid: 20, douyinValid: 18 }, myPosts: posts,
      reviewQueue: posts.slice(0, 20),
      memberProgress: Array.from({ length: 50 }, (_, index) => ({
        membershipId: `m-${index}`, displayName: `成员${index}`, xiaohongshuValid: index % 21, douyinValid: index % 20,
      })),
      administrator: true,
      reviewer: true,
    })

    const wrapper = mount(ContentTasksView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('每日执行')
    expect(wrapper.text()).toContain('每日汇报')
    expect(wrapper.text()).toContain('内容任务')
    expect(wrapper.findAll('.content-row')).toHaveLength(120)
    expect(wrapper.findAll('.review-row')).toHaveLength(20)
    expect(wrapper.findAll('tbody tr')).toHaveLength(50)
    expect(wrapper.text()).toContain('20/20')
    expect(wrapper.text()).toContain('18/20')
  })

  it('extracts a shared-content URL and blocks duplicate submissions while pending', async () => {
    let releasePost!: () => void
    const pendingPost = new Promise<void>((resolve) => { releasePost = resolve })
    const overview = {
      campaign: { name: '内容任务', startDate: '2026-07-01', endDate: '2026-07-31', xiaohongshuTarget: 20, douyinTarget: 20, today: '2026-07-20', active: true },
      directions: [{ id: 'direction-1', title: '成长内容', active: true }],
      myProgress: { xiaohongshuValid: 0, douyinValid: 0 }, myPosts: [], reviewQueue: [], memberProgress: [],
      administrator: false, reviewer: false,
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/content-posts' && init?.method === 'POST') return pendingPost
      return Promise.resolve(overview)
    })
    const wrapper = mount(ContentTasksView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()
    await wrapper.get('input[placeholder="简明填写本条内容的主题"]').setValue('抖音分享')
    await wrapper.get('[data-testid="content-url"]').setValue('3.28 复制打开抖音 https://v.douyin.com/eucZY9gC5Ec/ 07/30 测试分享文案')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    const submitForm = wrapper.get('.work-grid form')
    await submitForm.trigger('submit')
    await submitForm.trigger('submit')
    const postCalls = apiMock.mock.calls.filter(([path, init]) => path === '/api/v1/content-posts' && init?.method === 'POST')
    expect(postCalls).toHaveLength(1)
    expect(JSON.parse(postCalls[0][1].body)).toMatchObject({ contentUrl: 'https://v.douyin.com/eucZY9gC5Ec/' })
    expect(wrapper.get('[data-testid="content-submit"]').attributes('disabled')).toBeDefined()
    releasePost()
    await flushPromises()
  })

  it('quickly filters customer records by a normalized grade year', async () => {
    apiMock.mockResolvedValue({ items: [customer(1)], page: 0, size: 30, totalElements: 1, totalPages: 1 })
    const wrapper = mount(CustomersView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()
    const yearButton = wrapper.findAll('[data-testid="grade-filter"] button').find((button) => button.text() === '2026')
    expect(yearButton).toBeDefined()
    await yearButton!.trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringContaining('grade=2026'))
    expect(yearButton!.classes()).toContain('active')
  })

  it('shows team-scoped content review to a team leader without direction administration', async () => {
    const auth = authenticate(['TEAM_MANAGER'])
    apiMock.mockResolvedValue({
      campaign: { name: '2026夏季任务', startDate: '2026-07-15', endDate: '2026-08-20', xiaohongshuTarget: 20, douyinTarget: 20, today: '2026-07-20', active: true },
      directions: [{ id: 'direction-1', title: '管理员方向', active: true }],
      myProgress: { xiaohongshuValid: 1, douyinValid: 2 }, myPosts: [],
      reviewQueue: [{ id: 'team-post', membershipId: 'team-member', memberDisplayName: '本团队队员', platform: 'DOUYIN', publishDate: '2026-07-20', directionId: 'direction-1', directionTitle: '管理员方向', topic: '待审核内容', contentUrl: 'https://example.com/team-post', viewCount: 20, likeCount: 3, favoriteCount: 1, reviewStatus: 'SUBMITTED' }],
      memberProgress: [{ membershipId: 'team-member', displayName: '本团队队员', xiaohongshuValid: 4, douyinValid: 5 }],
      administrator: false, reviewer: true,
    })
    const wrapper = mount(ContentTasksView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()
    expect(auth.canReviewOperations).toBe(true)
    expect(wrapper.text()).toContain('本团队有效进度')
    expect(wrapper.text()).toContain('待审核内容')
    expect(wrapper.text()).not.toContain('发布内容方向')
    expect(wrapper.findAll('.review-row')).toHaveLength(1)
  })

  it('orders the newest task first, adds sequence numbers and lets an administrator delete an untouched task', async () => {
    authenticate(['ORG_ADMIN'])
    const oldTask = { id: 'old-task', title: '较早任务', description: '旧任务', distributionType: 'ASSIGNED', startAt: '2026-07-19T00:00:00Z', dueAt: '2026-07-21T00:00:00Z', createdAt: '2026-07-19T00:00:00Z', status: 'PUBLISHED', assignmentCount: 1, completedCount: 0, overdue: false, claimable: false }
    const newTask = { ...oldTask, id: 'new-task', title: '最新任务', createdAt: '2026-07-20T00:00:00Z' }
    let current = [oldTask, newTask]
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/tasks/matrix' && !init?.method) return Promise.resolve(current.map((task) => ({ task, assignments: [] })))
      if (path === '/api/v1/tasks/new-task' && init?.method === 'DELETE') {
        current = [oldTask]
        return Promise.resolve(undefined)
      }
      if (path === '/api/v1/admin/members') return Promise.resolve([])
      return Promise.resolve(undefined)
    })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(TaskCenterView, { global: { plugins: [pinia] } })
    await flushPromises()
    await wrapper.get('[data-testid="task-admin-view-switch"] button:last-child').trigger('click')
    const rows = wrapper.findAll('.task-admin-row')
    expect(rows[0].text()).toContain('#002')
    expect(rows[0].text()).toContain('最新任务')
    await rows[0].get('.task-delete').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/tasks/new-task', { method: 'DELETE' })
    expect(wrapper.findAll('.task-admin-row')).toHaveLength(1)
    expect(wrapper.get('.task-admin-row').text()).toContain('较早任务')
    confirm.mockRestore()
  })

  it('loads an existing customer profile and saves edits with optimistic locking', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1), nickname: '小一', wechatId: 'wx-one', school: '示例大学', major: '工业工程',
      grade: '2026级', city: '示例市', demandSummary: '需要转专业规划', sourceChannel: '转介绍',
      ownerNotes: '重点跟进', nextFollowUpAt: '2026-07-21T08:00:00Z', version: 7,
      tags: [
        { id: 'tag-1', name: '重点', category: '业务', color: '#3157d5', system: false },
        { id: 'tag-quality-a', name: 'A类用户', category: '客户质量', color: '#16A34A', system: true },
        { id: 'tag-grade-1', name: '2025', category: '年级', color: '#2563eb', system: true },
        { id: 'tag-grade-2', name: '2026', category: '年级', color: '#0f766e', system: true },
      ],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/tags') return Promise.resolve(existing.tags)
      if (path === '/api/v1/dictionaries') return Promise.resolve([
        { id: 'source-1', type: 'SOURCE_CHANNEL', code: 'XHS', label: '小红书', sortOrder: 1 },
        { id: 'source-2', type: 'SOURCE_CHANNEL', code: 'DOUYIN', label: '抖音', sortOrder: 2 },
        { id: 'source-3', type: 'SOURCE_CHANNEL', code: 'FRESHMAN', label: '新生群', sortOrder: 3 },
        { id: 'source-4', type: 'SOURCE_CHANNEL', code: 'OPERATOR', label: '运营商', sortOrder: 4 },
        { id: 'school-1', type: 'SCHOOL', code: 'JIANGSU_UNIVERSITY', label: '示例大学', sortOrder: 1 },
        { id: 'school-2', type: 'SCHOOL', code: 'JIANGSU_UNIVERSITY_OF_SCIENCE_AND_TECHNOLOGY', label: '示例科技大学', sortOrder: 2 },
      ])
      if (path === '/api/v1/dictionaries/schools' && init?.method === 'POST') {
        return Promise.resolve({ id: 'school-3', type: 'SCHOOL', code: 'school-new', label: '示例大学B', sortOrder: 3 })
      }
      if (path === '/api/v1/dictionaries/sources' && init?.method === 'POST') {
        return Promise.resolve({ id: 'source-custom', type: 'SOURCE_CHANNEL', code: 'source-new', label: '校园地推', sortOrder: 5 })
      }
      if (path === '/api/v1/customers/customer-1' && !init?.method) return Promise.resolve(existing)
      if (path === '/api/v1/customers/duplicate-check') return Promise.resolve({ blocking: false, hiddenExactMatches: 0, matches: [] })
      if (path === '/api/v1/customers/customer-1' && init?.method === 'PATCH') return Promise.resolve({ id: 'customer-1' })
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/customers/:id/edit', component: CustomerFormView },
        { path: '/customers/:id', component: { template: '<div />' } },
      ],
    })
    await router.push('/customers/customer-1/edit')
    await router.isReady()
    const wrapper = mount(CustomerFormView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('编辑客户画像')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe(existing.fullName)
    const schoolSelect = wrapper.get('[data-testid="school-select"]')
    expect(schoolSelect.findAll('option').map((option) => option.text())).toEqual([
      '请选择学校', '示例大学', '示例科技大学',
    ])
    expect((schoolSelect.element as HTMLSelectElement).value).toBe('示例大学')
    const sourceSelect = wrapper.get('[data-testid="source-select"]')
    expect(sourceSelect.findAll('option').map((option) => option.text())).toEqual([
      '请选择来源', '小红书', '抖音', '新生群', '运营商', '转介绍',
    ])
    expect((sourceSelect.element as HTMLSelectElement).value).toBe('转介绍')
    const gradeSelect = wrapper.get('[data-testid="grade-select"]')
    expect(gradeSelect.findAll('option').map((option) => option.text())).toEqual([
      '请选择年级', '2024级', '2025级', '2026级', '2027级', '2028级',
    ])
    expect((gradeSelect.element as HTMLSelectElement).value).toBe('2026')
    expect(wrapper.get('[data-testid="customer-tag-picker"]').text()).toBe('重点')
    expect(wrapper.get('[data-testid="customer-quality-picker"]').text()).toContain('A类用户')
    expect(wrapper.get('[data-testid="customer-quality-picker"]').text()).toContain('结构化认定')
    expect(wrapper.find('input[value="tag-quality-a"]').exists()).toBe(false)
    await wrapper.get('[data-testid="add-school-toggle"]').trigger('click')
    await wrapper.get('[data-testid="new-school-input"]').setValue('示例大学B')
    await wrapper.get('[data-testid="add-school-submit"]').trigger('click')
    await flushPromises()
    expect((schoolSelect.element as HTMLSelectElement).value).toBe('示例大学B')
    await wrapper.get('[data-testid="add-source-toggle"]').trigger('click')
    await wrapper.get('[data-testid="new-source-input"]').setValue('校园地推')
    await wrapper.get('[data-testid="add-source-submit"]').trigger('click')
    await flushPromises()
    expect((sourceSelect.element as HTMLSelectElement).value).toBe('校园地推')
    await gradeSelect.setValue('2025')
    await wrapper.find('input').setValue('更新后的客户')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const updateCall = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/customers/customer-1' && init?.method === 'PATCH')
    expect(updateCall).toBeTruthy()
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({
      fullName: '更新后的客户', school: '示例大学B', grade: '2025', tagIds: ['tag-1'],
      sourceChannel: '校园地推', sourceDictionaryId: 'source-custom',
      expectedVersion: 7, ownerMembershipId: 'member-1',
    })
  })

  it('warns and requires a reason when name and major match an existing customer', async () => {
    authenticate(['MEMBER'])
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/tags' || path === '/api/v1/dictionaries') return Promise.resolve([])
      if (path === '/api/v1/customers/duplicate-check') {
        return Promise.resolve({
          blocking: false,
          requiresConfirmation: true,
          hiddenExactMatches: 0,
          matches: [{ customerId: 'existing-customer', fullName: '张同学', reasons: ['姓名与专业一致'] }],
        })
      }
      if (path === '/api/v1/customers' && init?.method === 'POST') return Promise.resolve({ id: 'created-customer' })
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/customers/new', component: CustomerFormView },
        { path: '/customers/:id', component: { template: '<div />' } },
      ],
    })
    await router.push('/customers/new')
    await router.isReady()
    const wrapper = mount(CustomerFormView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('[data-testid="customer-name-input"]').setValue('张同学')
    await wrapper.get('[data-testid="customer-major-input"]').setValue('计算机科学与技术')
    await wrapper.get('[data-testid="customer-major-input"]').trigger('blur')
    await flushPromises()
    expect(wrapper.get('[data-testid="customer-conflict-warning"]').text()).toContain('姓名与专业一致的客户')

    await wrapper.get('[data-testid="customer-phone-input"]').setValue('13900001111')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('填写继续创建原因')
    expect(apiMock.mock.calls.some(([path, init]) => path === '/api/v1/customers' && init?.method === 'POST')).toBe(false)

    await wrapper.get('[data-testid="customer-conflict-warning"] textarea').setValue('已核对学号，确认是不同学生')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const createCall = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/customers' && init?.method === 'POST')
    expect(createCall).toBeTruthy()
    expect(JSON.parse(String(createCall?.[1]?.body))).toMatchObject({
      fullName: '张同学',
      major: '计算机科学与技术',
      duplicateOverrideReason: '已核对学号，确认是不同学生',
    })
  })

  it('also requires a reason when an edit would match another customer name and major', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1),
      fullName: '待修改客户',
      major: '软件工程',
      focusDirections: ['考研'],
      tags: [],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/tags' || path === '/api/v1/dictionaries') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1' && !init?.method) return Promise.resolve(existing)
      if (path === '/api/v1/customers/duplicate-check') {
        return Promise.resolve({
          blocking: false,
          requiresConfirmation: true,
          hiddenExactMatches: 0,
          matches: [{ customerId: 'another-customer', fullName: '张同学', reasons: ['姓名与专业一致'] }],
        })
      }
      if (path === '/api/v1/customers/customer-1' && init?.method === 'PATCH') {
        return Promise.resolve({ id: 'customer-1' })
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/customers/:id/edit', component: CustomerFormView },
        { path: '/customers/:id', component: { template: '<div />' } },
      ],
    })
    await router.push('/customers/customer-1/edit')
    await router.isReady()
    const wrapper = mount(CustomerFormView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('[data-testid="customer-name-input"]').setValue('张同学')
    await wrapper.get('[data-testid="customer-major-input"]').setValue('计算机科学与技术')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('填写继续修改原因')
    expect(apiMock.mock.calls.some(
      ([path, init]) => path === '/api/v1/customers/customer-1' && init?.method === 'PATCH',
    )).toBe(false)

    await wrapper.get('[data-testid="duplicate-override-reason"]').setValue('已核对学号，确认修改后仍为不同学生')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const updateCall = apiMock.mock.calls.find(
      ([path, init]) => path === '/api/v1/customers/customer-1' && init?.method === 'PATCH',
    )
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({
      fullName: '张同学',
      major: '计算机科学与技术',
      duplicateOverrideReason: '已核对学号，确认修改后仍为不同学生',
    })
  })

  it('lets the primary owner share one customer timeline without duplicating the profile', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1), fullName: '协作客户', ownerDisplayName: '主负责人甲', creatorDisplayName: '录入人甲',
      school: '示例大学', major: '计算机科学与技术', grade: '2026', focusDirections: ['考研'], tags: [],
    }
    const ownerOverview = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: true, canEditTimeline: true,
      collaborators: [{ membershipId: 'member-2', displayName: '协作者乙', teamNames: '华东营销组' }],
      candidates: [
        { membershipId: 'member-2', displayName: '协作者乙', teamNames: '华东营销组' },
        { membershipId: 'member-3', displayName: '协作者丙', teamNames: '内容增长组' },
      ],
      transferCandidates: [],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([{
        id: 'interaction-1', customerId: 'customer-1', contactedAt: '2026-07-21T08:00:00Z',
        channel: '微信', summary: '协作沟通记录', createdByMembershipId: 'member-2',
        createdByDisplayName: '协作者乙', createdAt: '2026-07-21T08:00:00Z',
      }])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(ownerOverview)
      if (path === '/api/v1/customers/customer-1/collaborators' && init?.method === 'PUT') {
        return Promise.resolve({ ...ownerOverview, collaborators: ownerOverview.candidates })
      }
      return Promise.resolve(undefined)
    })
    const returnTo = '/team-data?teamId=team-1&grade=2026&tab=customers&view=table'
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/team-data', component: { template: '<div />' } },
        { path: '/customers/:id/edit', component: { template: '<div />' } },
        { path: '/customers/:id', component: CustomerDetailView },
      ],
    })
    await router.push(returnTo)
    await router.push({ path: '/customers/customer-1', query: { returnTo } })
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="customer-parent-return"]').text()).toBe('返回团队数据')
    expect(wrapper.text()).toContain('主负责人甲')
    expect(wrapper.text()).toContain('协作跟进')
    expect(wrapper.text()).toContain('记录人：协作者乙')
    expect(wrapper.findAll('.profile-header .primary-button')).toHaveLength(1)
    await wrapper.get('[data-testid="share-customer"]').trigger('click')
    expect(wrapper.get('[data-testid="collaboration-drawer"]').text()).toContain('协作者丙')
    await wrapper.get('input[value="member-3"]').setValue(true)
    await wrapper.get('[data-testid="save-collaborators"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/customers/customer-1/collaborators', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ membershipIds: ['member-2', 'member-3'] }),
    }))
    expect(wrapper.text()).toContain('协作者乙、协作者丙')
    await wrapper.get('[data-testid="edit-customer"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/customers/customer-1/edit')
    expect(router.currentRoute.value.query.returnTo).toBe(returnTo)
  })

  it('records communication for a legacy A customer without resubmitting its quality tag', async () => {
    authenticate(['MEMBER'])
    const qualityA = { id: 'tag-quality-a', name: 'A类用户', category: '客户质量', color: '#16A34A', system: true }
    const regular = { id: 'tag-regular', name: '重点', category: '业务', color: '#3157d5', system: false }
    const existing = { ...customer(1), fullName: '历史A级客户', tags: [qualityA, regular] }
    const collaboration = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: true, canEditTimeline: true,
      collaborators: [], candidates: [], transferCandidates: [],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions' && init?.method === 'POST') {
        return Promise.resolve({ id: 'interaction-1', customerId: 'customer-1', contactedAt: new Date().toISOString(), channel: '微信', summary: '正常沟通', createdAt: new Date().toISOString() })
      }
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([qualityA, regular])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(collaboration)
      if (path === '/api/v1/customers/customer-1/classifications') return Promise.resolve([])
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/customers/:id', component: CustomerDetailView }],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('.customer-primary-action').trigger('click')
    expect(wrapper.get('.current-quality-readonly').text()).toContain('A类用户')
    expect(wrapper.get('.current-quality-readonly').text()).toContain('本次沟通不会改变等级')
    await wrapper.get('.drawer input[placeholder="一句话记录本次沟通重点"]').setValue('正常沟通')
    await wrapper.get('.drawer form').trigger('submit')
    await flushPromises()

    const write = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/customers/customer-1/interactions' && init?.method === 'POST')
    const payload = JSON.parse(String(write?.[1]?.body))
    expect(payload.tagUpdateRequested).toBe(false)
    expect(payload).not.toHaveProperty('tagIds')
  })

  it('lets a shared collaborator edit the profile without exposing ownership controls', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1), fullName: '共享画像客户', ownerDisplayName: '主负责人甲',
      creatorDisplayName: '录入人甲', tags: [],
    }
    const collaboratorOverview = {
      ownerMembershipId: 'member-1', canManage: false, canTransferOwnership: false,
      canEditProfile: true, canArchive: false, canEditTimeline: true,
      collaborators: [{ membershipId: 'member-2', displayName: '当前协作者', teamNames: '华东营销组' }],
      candidates: [], transferCandidates: [],
    }
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(collaboratorOverview)
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/customers/:id/edit', component: { template: '<div>画像编辑页</div>' } },
        { path: '/customers/:id', component: CustomerDetailView },
      ],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="edit-customer"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="share-customer"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="transfer-customer"]').exists()).toBe(false)
    expect(wrapper.find('.danger-link').exists()).toBe(false)
    await wrapper.get('[data-testid="edit-customer"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/customers/customer-1/edit')
  })

  it('creates an editable reminder from versioned intelligence and queues explicit reanalysis', async () => {
    authenticate(['MEMBER'])
    const existing = { ...customer(1), fullName: '智能分析客户', tags: [] }
    const collaboration = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: true, canEditTimeline: true,
      collaborators: [], candidates: [], transferCandidates: [],
    }
    const overview = {
      customerId: 'customer-1', enabled: true, processing: false,
      latest: {
        id: 'analysis-1', versionNumber: 2, triggerType: 'TIMELINE_UPDATED', status: 'SUCCEEDED',
        narrative: '客户希望确认转专业路径，当前仍需核实时间安排。',
        result: {
          goals: ['确认转专业路径'], progress: ['已完成首次沟通'], concerns: ['时间安排未确认'],
          risks: ['关键信息不完整'], recommendedActions: ['下次沟通确认时间'],
          unresolvedQuestions: ['最终申请时间'],
        },
        evidence: [{ sourceType: 'INTERACTION', sourceKey: 'interaction:1',
          sourceAt: '2026-08-14T01:00:00Z', excerpt: '客户希望了解转专业路径。' }],
        model: 'uat-contract-model', createdAt: '2026-08-14T01:01:00Z',
      },
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(collaboration)
      if (path === '/api/v1/customers/customer-1/intelligence' && !init) return Promise.resolve(overview)
      if (path === '/api/v1/customers/customer-1/intelligence/reanalyze' && init?.method === 'POST') {
        return Promise.resolve({ ...overview, processing: true, processingStatus: 'PENDING' })
      }
      if (path === '/api/v1/reminders' && init?.method === 'POST') {
        const request = JSON.parse(String(init.body))
        return Promise.resolve({ id: 'reminder-ai-1', ...request, status: 'PENDING', deliveryStatus: 'UNSENT', overdue: false })
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/customers/:id', component: CustomerDetailView }],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="customer-intelligence"]').text()).toContain('AI 客户洞察')
    expect(wrapper.text()).toContain('客户希望确认转专业路径')
    expect(wrapper.text()).toContain('版本 2')
    expect(wrapper.text()).toContain('不会自动改写正式档案')
    await wrapper.get('[data-testid="create-intelligence-reminder"]').trigger('click')
    expect(wrapper.get('[data-testid="intelligence-reminder-title"]').element).toHaveProperty('value', '下次沟通确认时间')
    await wrapper.get('[data-testid="intelligence-reminder-title"]').setValue('人工确认后的跟进动作')
    await wrapper.get('[data-testid="intelligence-reminder-at"]').setValue('2026-09-20T09:00')
    await wrapper.get('[data-testid="intelligence-reminder-drawer"] form').trigger('submit')
    await flushPromises()
    const reminderCall = apiMock.mock.calls.find(([path, init]) => path === '/api/v1/reminders' && init?.method === 'POST')
    expect(reminderCall).toBeTruthy()
    const reminderBody = JSON.parse(String(reminderCall?.[1]?.body))
    expect(reminderBody).toMatchObject({
      customerId: 'customer-1', title: '人工确认后的跟进动作', sourceAnalysisVersionId: 'analysis-1',
    })
    expect(reminderBody.remindAt).toBe(new Date('2026-09-20T09:00').toISOString())
    expect(wrapper.get('[data-testid="intelligence-reminder-created"]').text()).toContain('跟进提醒已创建')
    await wrapper.get('[data-testid="reanalyze-customer"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/customers/customer-1/intelligence/reanalyze', { method: 'POST' })
    expect(wrapper.text()).toContain('分析队列中')
  })

  it('hides intelligence write actions for an archived customer', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1), fullName: '归档智能分析客户', archivedAt: '2026-09-15T00:00:00Z', tags: [],
    }
    const collaboration = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: false, canEditTimeline: true,
      collaborators: [], candidates: [], transferCandidates: [],
    }
    const overview = {
      customerId: 'customer-1', enabled: true, processing: false,
      latest: {
        id: 'analysis-archived', versionNumber: 1, triggerType: 'MANUAL', status: 'SUCCEEDED',
        narrative: '这是归档前形成的历史分析版本。', result: { recommendedActions: ['历史建议'] },
        evidence: [], model: 'deterministic-test', createdAt: '2026-09-14T00:00:00Z',
      },
    }
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(collaboration)
      if (path === '/api/v1/customers/customer-1/intelligence') return Promise.resolve(overview)
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/customers/:id', component: CustomerDetailView }],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="customer-intelligence"]').text()).toContain('这是归档前形成的历史分析版本')
    expect(wrapper.find('[data-testid="reanalyze-customer"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="create-intelligence-reminder"]').exists()).toBe(false)
  })

  it('transfers customer ownership from the profile with an explicit recipient and version', async () => {
    authenticate(['MEMBER'])
    const existing = {
      ...customer(1), fullName: '待转接客户', ownerDisplayName: '原负责人甲',
      creatorDisplayName: '录入人乙', version: 4,
    }
    const overview = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: true, canEditTimeline: true, collaborators: [], candidates: [],
      transferCandidates: [
        { membershipId: 'member-2', displayName: '接收人乙', teamNames: '华东营销组' },
        { membershipId: 'member-3', displayName: '接收人丙', teamNames: '内容增长组' },
      ],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') return Promise.resolve(overview)
      if (path === '/api/v1/customers/customer-1/transfer' && init?.method === 'POST') {
        return Promise.resolve({ ...existing, ownerMembershipId: 'member-2', ownerDisplayName: '接收人乙', version: 5 })
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/customers', component: { template: '<div>客户列表</div>' } },
        { path: '/customers/:id/edit', component: { template: '<div />' } },
        { path: '/customers/:id', component: CustomerDetailView },
      ],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('[data-testid="transfer-customer"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="ownership-transfer-drawer"]').text()).toContain('录入人、历史时间线和现有协作者保持不变')
    await wrapper.get('[data-testid="ownership-transfer-target"]').setValue('member-2')
    await wrapper.get('[data-testid="ownership-transfer-drawer"] textarea').setValue('排班调整，正式交接')
    await wrapper.get('[data-testid="confirm-ownership-transfer"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('接收人乙'))
    expect(apiMock).toHaveBeenCalledWith('/api/v1/customers/customer-1/transfer', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        targetMembershipId: 'member-2',
        expectedVersion: 4,
        reason: '排班调整，正式交接',
      }),
    }))
    expect(router.currentRoute.value.path).toBe('/customers')
    confirm.mockRestore()
  })

  it('refreshes transfer candidates when the ownership drawer opens', async () => {
    authenticate(['ORG_ADMIN'])
    const existing = {
      ...customer(1), fullName: '候选人刷新客户', ownerDisplayName: '原负责人甲',
      creatorDisplayName: '录入人乙', version: 4,
    }
    const emptyOverview = {
      ownerMembershipId: 'member-1', canManage: true, canTransferOwnership: true,
      canEditProfile: true, canArchive: true, canEditTimeline: true,
      collaborators: [], candidates: [], transferCandidates: [],
    }
    const freshOverview = {
      ...emptyOverview,
      transferCandidates: [{ membershipId: 'member-2', displayName: '实时接收人', teamNames: '华东营销组' }],
    }
    let collaborationReads = 0
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/customers/customer-1') return Promise.resolve(existing)
      if (path === '/api/v1/customers/customer-1/interactions') return Promise.resolve([])
      if (path === '/api/v1/tags') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1/collaboration') {
        collaborationReads += 1
        return Promise.resolve(collaborationReads === 1 ? emptyOverview : freshOverview)
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/customers/:id', component: CustomerDetailView }],
    })
    await router.push('/customers/customer-1')
    await router.isReady()
    const wrapper = mount(CustomerDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="ownership-transfer-target"]').exists()).toBe(false)
    await wrapper.get('[data-testid="transfer-customer"]').trigger('click')
    await flushPromises()

    expect(collaborationReads).toBe(2)
    expect(wrapper.get('[data-testid="ownership-transfer-target"]').text()).toContain('实时接收人 · 华东营销组')
    expect(wrapper.find('[data-testid="ownership-transfer-target"] option[value="member-2"]').exists()).toBe(true)
  })

  it('pages through all 120 public circle posts without dropping older content', async () => {
    authenticate(['MEMBER'])
    const posts = Array.from({ length: 120 }, (_, index) => ({
      id: `circle-${index}`, membershipId: `member-${index % 6}`, memberDisplayName: `成员${index % 6}`,
      platform: index % 2 ? 'DOUYIN' : 'XIAOHONGSHU', publishDate: '2026-07-19',
      directionTitle: '新生成长', topic: `圈子主题-${index}`, contentUrl: `https://example.com/circle/${index}`,
      viewCount: index * 100, likeCount: index * 10, favoriteCount: index,
      weightScore: index % 5 ? 1_000_000 + index * 220 : index * 100,
      reviewStatus: index % 5 ? 'VALID' : 'INVALID', reviewReason: index % 5 ? undefined : '质量不符合要求',
    }))
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/circle/senders') return Promise.resolve(Array.from({ length: 6 }, (_, index) => ({ membershipId: `member-${index}`, displayName: `成员${index}` })))
      const page = Number(new URL(`http://mock${path}`).searchParams.get('page') ?? 0)
      const items = posts.slice(page * 20, page * 20 + 20)
      return Promise.resolve({ items, page, size: 20, totalElements: 120, totalPages: 6 })
    })

    const wrapper = mount(CircleView)
    await flushPromises()
    expect(wrapper.findAll('.circle-card')).toHaveLength(20)
    for (let page = 1; page < 6; page++) {
      await wrapper.get('.secondary-button').trigger('click')
      await flushPromises()
    }
    expect(wrapper.findAll('.circle-card')).toHaveLength(120)
    expect(wrapper.text()).toContain('圈子主题-119')
    expect(wrapper.text()).toContain('高权重')
    expect(wrapper.find('.circle-workspace').exists()).toBe(true)
    expect(wrapper.findAll('.circle-tabs button')).toHaveLength(5)
    await wrapper.findAll('.view-switch button')[1].trigger('click')
    expect(wrapper.findAll('.circle-list-row')).toHaveLength(120)
    expect(wrapper.find('.secondary-button').exists()).toBe(false)
  })

  it('renders a 100-row report history with the automatic lead count', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path.endsWith('/today')) return Promise.resolve({
        membershipId: 'member-1', memberDisplayName: '模拟成员', reportDate: '2026-07-19',
        grade26NewValidLeads: 36, deepCommunicationCount: 8, todayIssues: '素材不足',
        tomorrowPlan: '完成回访', submitted: true,
      })
      return Promise.resolve(Array.from({ length: 100 }, (_, index) => ({
        id: `report-${index}`, membershipId: `m-${index % 20}`, memberDisplayName: `成员${index % 20}`,
        reportDate: `2026-07-${String(19 - (index % 10)).padStart(2, '0')}`,
        grade26NewValidLeads: index % 8, deepCommunicationCount: index % 12,
        todayIssues: `今日问题${index}`, tomorrowPlan: `明日计划${index}`, submitted: true,
      })))
    })

    const wrapper = mount(DailyReportView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('每日执行')
    expect(wrapper.text()).toContain('内容任务')
    expect(wrapper.text()).toContain('36')
    expect(wrapper.findAll('.report-row')).toHaveLength(100)
    expect(wrapper.text()).toContain('审核结果实时更新')
  })

  it('shows administrators a monthly report calendar with submitted and missing members', async () => {
    authenticate(['ORG_ADMIN'])
    const reports = [{
      id: 'report-1', membershipId: 'member-1', memberDisplayName: '已提交甲',
      reportDate: '2026-07-19', grade26NewValidLeads: 4, deepCommunicationCount: 6,
      todayIssues: '暂无', tomorrowPlan: '继续跟进', submitted: true,
    }]
    const days = Array.from({ length: 31 }, (_, index) => {
      const date = `2026-07-${String(index + 1).padStart(2, '0')}`
      const selected = index === 18
      return {
        date,
        reportingDay: index < 19,
        expectedCount: index < 19 ? 3 : 0,
        submittedCount: selected ? 2 : 0,
        submittedMembers: selected
          ? [{ membershipId: 'member-1', displayName: '已提交甲' }, { membershipId: 'member-2', displayName: '已提交乙' }]
          : [],
        missingMembers: selected ? [{ membershipId: 'member-3', displayName: '未提交丙' }] : [],
      }
    })
    apiMock.mockImplementation((path: string) => {
      if (path.includes('/api/v1/daily-reports/calendar?month=')) {
        return Promise.resolve({ month: '2026-07', today: '2026-07-19', expectedMemberCount: 3, days })
      }
      if (path.endsWith('/today')) return Promise.resolve(reports[0])
      if (path === '/api/v1/daily-reports') return Promise.resolve(reports)
      return Promise.resolve(undefined)
    })

    const wrapper = mount(DailyReportView, { global: { plugins: [pinia], stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.get('[data-testid="report-calendar"]').text()).toContain('2026 年 7 月')
    expect(wrapper.findAll('.report-calendar-day[data-date]')).toHaveLength(31)
    expect(wrapper.get('[data-testid="report-day-detail"]').text()).toContain('2 人已提交')
    expect(wrapper.get('[data-testid="report-day-detail"]').text()).toContain('1 人未提交')
    expect(wrapper.get('[data-testid="report-day-detail"]').text()).toContain('已提交甲')
    expect(wrapper.get('[data-testid="report-day-detail"]').text()).toContain('未提交丙')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/daily-reports/calendar?month=2026-07')
  })

  it('keeps the dashboard usable with dozens of recent customers', async () => {
    const recent = Array.from({ length: 40 }, (_, index) => customer(index))
    apiMock.mockResolvedValue({
      stats: { totalActive: 600, archivedCount: 24, todayDueCount: 30, overdueCount: 18, highIntentionCount: 120 },
      dueToday: recent.slice(0, 8), overdue: recent.slice(8, 16), staleHighIntention: recent.slice(16, 22),
      recentUpdated: recent,
    })

    const wrapper = mount(DashboardView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('600')
    expect(wrapper.text()).not.toContain('工作台暂时无法加载')
    expect(wrapper.findAll('.span-3 .customer-row')).toHaveLength(40)
  })

  it('hides bulk and organization administration navigation from ordinary members', async () => {
    authenticate(['MEMBER'])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: AppLayout, children: [{ path: 'dashboard', component: { template: '<div>dashboard</div>' } }] },
        { path: '/login', component: { template: '<div>login</div>' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div>placeholder</div>' } },
      ],
    })
    await router.push('/dashboard')
    await router.isReady()
    const wrapper = mount(AppLayout, { global: { plugins: [router, pinia] } })

    expect(wrapper.text()).toContain('任务中心')
    expect(wrapper.text()).toContain('每日执行')
    expect(wrapper.text()).toContain('每日汇报')
    expect(wrapper.text()).toContain('内容任务')
    expect(wrapper.text()).not.toContain('批量导入')
    expect(wrapper.text()).not.toContain('成员账户')
  })

  it('shows unresolved A-class stale reminders as a full-screen alert on every layout mount', async () => {
    authenticate(['MEMBER'])
    apiMock.mockImplementation((path: string) => path === '/api/v1/reminders/urgent-a-class'
      ? Promise.resolve([{
          id: 'urgent-1', customerId: 'customer-urgent', remindAt: '2026-08-18T00:00:00Z',
          title: 'A 类客户已超过 72 小时未更新：测试客户', status: 'PENDING',
          deliveryStatus: 'UNSENT', reminderType: 'A_CLASS_STALE', overdue: true,
        }])
      : Promise.resolve(undefined))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: AppLayout, children: [{ path: 'dashboard', component: { template: '<div>dashboard</div>' } }] },
        { path: '/customers/:id', component: { template: '<div>customer</div>' } },
        { path: '/login', component: { template: '<div>login</div>' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div>placeholder</div>' } },
      ],
    })
    await router.push('/dashboard')
    await router.isReady()

    const first = mount(AppLayout, { global: { plugins: [router, pinia] } })
    await flushPromises()
    expect(first.get('[role="alertdialog"]').text()).toContain('A 类客户跟进已超时')
    expect(first.get('.urgent-followup-card > .soft-button').text()).toContain('本次登录稍后处理')
    first.unmount()

    const second = mount(AppLayout, { global: { plugins: [router, pinia] } })
    await flushPromises()
    expect(second.get('[role="alertdialog"]').text()).toContain('下次登录将再次全屏提醒')
  })

  it('shows an unacknowledged release once per account after higher-priority A-class alerts', async () => {
    authenticate(['MEMBER'])
    const release = {
      id: 'release-current', version: '2026.08.18.1', title: '市场治理与重要提醒',
      summary: '客户分级、归属流转和重要通知形成闭环。',
      sections: { 新增: ['产品更新中心'], 修复: ['批量提醒参数问题'] },
      releasedAt: '2026-08-18T00:00:00Z', current: true, acknowledged: false,
    }
    let acknowledged = false
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/reminders/urgent-a-class') return Promise.resolve([{
        id: 'urgent-1', customerId: 'customer-urgent', remindAt: '2026-08-18T00:00:00Z',
        title: 'A 类客户已超过 72 小时未更新：测试客户', status: 'PENDING',
        deliveryStatus: 'UNSENT', reminderType: 'A_CLASS_STALE', overdue: true,
      }])
      if (path === '/api/v1/releases/current/unacknowledged') return Promise.resolve(acknowledged ? undefined : release)
      if (path === '/api/v1/releases/release-current/acknowledge' && init?.method === 'POST') {
        acknowledged = true
        return Promise.resolve(undefined)
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: AppLayout, children: [{ path: 'dashboard', component: { template: '<div>dashboard</div>' } }] },
        { path: '/releases', component: { template: '<div>releases</div>' } },
        { path: '/customers/:id', component: { template: '<div>customer</div>' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div>placeholder</div>' } },
      ],
    })
    await router.push('/dashboard'); await router.isReady()
    const wrapper = mount(AppLayout, { global: { plugins: [router, pinia] } })
    await flushPromises()
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('A 类客户跟进已超时')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.get('.urgent-followup-card > .soft-button').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('市场治理与重要提醒')
    expect(wrapper.find('.release-update-card [aria-label="关闭"]').exists()).toBe(false)
    await wrapper.get('.release-update-actions .primary-button').trigger('click')
    await flushPromises()
    expect(acknowledged).toBe(true)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()

    apiMock.mockImplementation((path: string) => path === '/api/v1/releases/current/unacknowledged'
      ? Promise.resolve(undefined) : Promise.resolve([]))
    const nextLogin = mount(AppLayout, { global: { plugins: [router, pinia] } })
    await flushPromises()
    expect(nextLogin.find('[role="dialog"]').exists()).toBe(false)
    nextLogin.unmount()
  })

  it('renders the complete product release history for every role', async () => {
    authenticate(['VIEWER'])
    apiMock.mockResolvedValue({
      items: [{
        id: 'release-current', version: '2026.08.18.1', title: '市场治理与重要提醒',
        summary: '本次版本摘要', sections: { 新增: ['产品更新中心'], 安全: ['认领前脱敏'] },
        releasedAt: '2026-08-18T00:00:00Z', current: true, acknowledged: false,
      }],
      page: 0, size: 20, totalElements: 11, totalPages: 1,
    })
    const wrapper = mount(ReleasesView)
    await flushPromises()
    expect(wrapper.text()).toContain('更新日志')
    expect(wrapper.text()).toContain('11 个云端版本')
    expect(wrapper.text()).toContain('v2026.08.18.1')
    expect(wrapper.text()).toContain('产品更新中心')
    expect(wrapper.text()).toContain('当前版本')
  })

  it('lets an administrator update AI service settings without rendering an existing key', async () => {
    authenticate(['ORG_ADMIN'])
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/admin/assistant-settings' && init?.method === 'PUT') {
        return Promise.resolve({ enabled: false, baseUrl: 'https://provider.example/v1', model: 'model-v2', apiKeyConfigured: true, apiKeySource: 'DATABASE', version: 4 })
      }
      return Promise.resolve({ enabled: true, baseUrl: 'https://provider.example/v1', model: 'model-v1', apiKeyConfigured: true, apiKeySource: 'ENVIRONMENT', version: 3 })
    })
    const wrapper = mount(AdminAssistantSettingsView)
    await flushPromises()
    expect(wrapper.text()).toContain('服务器环境配置')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    await wrapper.get('input[placeholder="model-name"]').setValue('model-v2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/assistant-settings', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ enabled: false, baseUrl: 'https://provider.example/v1', model: 'model-v2', clearApiKey: false, expectedVersion: 3 }),
    }))
    expect(wrapper.text()).toContain('动态生效')
    expect(wrapper.text()).toContain('管理员加密配置')
  })

  it('lets an administrator configure, reorder and preview the television leadership screen', async () => {
    authenticate(['ORG_ADMIN'])
    const initial = {
      animationsEnabled: true,
      standbyEnabled: true,
      idleTimeoutMinutes: 20,
      sceneDurationSeconds: 12,
      eventDurationSeconds: 7,
      motionIntensity: 'CINEMATIC',
      sceneOrder: ['time', 'culture', 'briefing', 'trend', 'champions', 'funnel', 'mission'],
      enabledScenes: ['time', 'culture', 'briefing', 'trend', 'champions', 'funnel', 'mission'],
      enabledEvents: ['zeroBreakthrough', 'customerAdded', 'customerUpdated', 'milestone', 'trendSurge', 'conversionStage', 'taskPublished', 'taskMilestone', 'rankingChange', 'teamMomentum'],
      version: 0,
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/admin/leadership-screen-settings' && init?.method === 'PUT') {
        return Promise.resolve({ ...initial, ...JSON.parse(String(init.body)), version: 1 })
      }
      return Promise.resolve(initial)
    })
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(AdminLeadershipScreenSettingsView)
    await flushPromises()

    expect(wrapper.text()).toContain('大屏设置')
    expect(wrapper.text()).toContain('10 / 10 已启用')
    await wrapper.findAll('.scene-order-list article')[0].findAll('button')[2].trigger('click')
    await wrapper.get('input[type="number"]').setValue('30')
    await wrapper.get('.event-setting-grid article .event-enable').trigger('click')
    await wrapper.get('.event-setting-grid article .event-preview').trigger('click')
    expect(open).toHaveBeenCalledWith(
      '/leadership-screen?previewEvent=zeroBreakthrough',
      'yingge-leadership-preview',
      'noopener',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const saveCall = apiMock.mock.calls.find(([path, init]) =>
      path === '/api/v1/admin/leadership-screen-settings' && init?.method === 'PUT')
    expect(JSON.parse(String(saveCall?.[1]?.body))).toMatchObject({
      idleTimeoutMinutes: 30,
      sceneOrder: ['culture', 'time', 'briefing', 'trend', 'champions', 'funnel', 'mission'],
      enabledEvents: ['customerAdded', 'customerUpdated', 'milestone', 'trendSurge', 'conversionStage', 'taskPublished', 'taskMilestone', 'rankingChange', 'teamMomentum'],
      expectedVersion: 0,
    })
    expect(wrapper.text()).toContain('下次刷新后自动生效')
  })

  it('registers a member with real name, weak password and registration code', async () => {
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/auth/registration-options') return Promise.resolve({ teams: [{ id: 'team-1', name: '华东营销组' }] })
      return Promise.resolve({
        userId: 'registered-user', membershipId: 'registered-member', username: 'new-user',
        realName: '王小英', role: 'MEMBER', teamId: 'team-1', teamName: '华东营销组',
      })
    })
    const wrapper = mount(RegisterView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('YG-2345-6789-ABCD-EFGH')
    await wrapper.get('.input-action-row button').trigger('click')
    await flushPromises()
    await wrapper.get('select').setValue('team-1')
    await inputs[1].setValue('new-user')
    await inputs[2].setValue('王小英')
    await inputs[3].setValue('1')
    await inputs[4].setValue('1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/auth/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ username: 'new-user', realName: '王小英', password: '1', registrationCode: 'YG-2345-6789-ABCD-EFGH', teamId: 'team-1' }),
    }))
    expect(wrapper.text()).toContain('注册完成')
    expect(wrapper.text()).toContain('王小英')
  })

  it('blocks mismatched passwords before registration and manages registration codes as admin', async () => {
    const register = mount(RegisterView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    const inputs = register.findAll('input')
    await inputs[0].setValue('YG-2345-6789-ABCD-EFGH')
    await inputs[1].setValue('new-user-2')
    await inputs[2].setValue('李小格')
    await inputs[3].setValue('a')
    await inputs[4].setValue('b')
    await register.get('form').trigger('submit')
    expect(register.text()).toContain('两次输入的密码不一致')
    expect(apiMock).not.toHaveBeenCalled()

    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/admin/members') return Promise.resolve([])
      if (path === '/api/v1/admin/teams') return Promise.resolve([])
      if (path === '/api/v1/admin/registration-codes' && init?.method === 'POST') {
        return Promise.resolve({ id: 'code-2', code: 'YG-AAAA-BBBB-CCCC-DDDD', codeHint: 'YG-AAAA-••••-••••-DDDD', maxUses: 3, usedCount: 0, status: 'ACTIVE' })
      }
      if (path === '/api/v1/admin/registration-codes') {
        return Promise.resolve([{ id: 'code-1', codeHint: 'YG-2345-••••-••••-EFGH', maxUses: 2, usedCount: 1, status: 'ACTIVE', createdAt: '2026-07-19T00:00:00Z' }])
      }
      return Promise.resolve(undefined)
    })
    const admin = mount(AdminMembersView)
    await flushPromises()
    expect(admin.text()).toContain('注册码管理')
    expect(admin.text()).toContain('YG-2345-••••-••••-EFGH')
    await admin.get('.registration-code-form input[type="number"]').setValue('3')
    await admin.get('.registration-code-form').trigger('submit')
    await flushPromises()
    expect((admin.get('.registration-code-reveal input').element as HTMLInputElement).value)
      .toBe('YG-AAAA-BBBB-CCCC-DDDD')
    expect(admin.text()).toContain('数据库不会保存明文注册码')
  })

  it('shows one team leader and allows an administrator to replace the leader', async () => {
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/admin/teams' && !init) return Promise.resolve([{
        id: 'team-1', name: '示例市营销组', active: true, memberCount: 8,
        leaderMembershipId: 'member-1', leaderDisplayName: '原团队长',
      }])
      if (path === '/api/v1/admin/members') return Promise.resolve([
        { membershipId: 'member-1', username: 'old-leader', displayName: '原团队长', status: 'ACTIVE', roles: ['TEAM_MANAGER'] },
        { membershipId: 'member-2', username: 'new-leader', displayName: '新团队长', status: 'ACTIVE', roles: ['MEMBER'] },
      ])
      if (path === '/api/v1/admin/teams/team-1/leader' && init?.method === 'PUT') {
        return Promise.resolve({ id: 'team-1', leaderMembershipId: 'member-2', leaderDisplayName: '新团队长' })
      }
      return Promise.resolve(undefined)
    })
    const wrapper = mount(AdminTeamsView)
    await flushPromises()
    expect(wrapper.text()).toContain('原团队长')
    expect(wrapper.text()).toContain('可查看本团队所有成员录入或负责的客户')
    await wrapper.get('.leader-select-form select').setValue('member-2')
    await wrapper.get('.leader-select-form').trigger('submit')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/teams/team-1/leader', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ membershipId: 'member-2' }),
    }))
  })

  it('renders team-scoped customers, reports and content progress for a team leader', async () => {
    authenticate(['TEAM_MANAGER'])
    apiMock.mockImplementation((path: string) => {
      if (path.startsWith('/api/v1/team-data/overview')) {
        const grade2025 = path.includes('grade=2025')
        return Promise.resolve({
          teams: [{ id: 'team-1', name: '华东营销组' }],
          members: Array.from({ length: 12 }, (_, index) => ({ membershipId: `member-${index}`, displayName: `队员${index}`, username: `m${index}`, teamNames: '华东营销组' })),
          stats: { memberCount: 12, activeCustomerCount: grade2025 ? 120 : 240, todayNewCustomerCount: grade2025 ? 8 : 18, todayGrade26LeadCount: grade2025 ? 4 : 10, pendingLeadReviewCount: grade2025 ? 1 : 3, todayReportCount: 9, xiaohongshuValidCount: 80, douyinValidCount: 70 },
          dailyReports: Array.from({ length: 9 }, (_, index) => ({ id: `r-${index}`, membershipId: `member-${index}`, memberDisplayName: `队员${index}`, reportDate: '2026-07-20', grade26NewValidLeads: index, deepCommunicationCount: 5, todayIssues: '问题', tomorrowPlan: '计划' })),
          contentProgress: Array.from({ length: 12 }, (_, index) => ({ membershipId: `member-${index}`, displayName: `队员${index}`, xiaohongshuValid: index, douyinValid: index + 1 })),
          leadReviewQueue: Array.from({ length: grade2025 ? 1 : 3 }, (_, index) => ({ customerId: `review-${index}`, fullName: `待审客户${index}`, ownerDisplayName: '队员甲', creatorDisplayName: '队员乙', status: 'PENDING', createdAt: '2026-07-20T00:00:00Z' })),
          administrator: false,
        })
      }
      if (path.startsWith('/api/v1/customers')) {
        const params = new URL(path, 'https://yingge.test').searchParams
        const page = Number(params.get('page') ?? '0')
        const totalElements = params.get('grade') === '2025' ? 120 : 240
        return Promise.resolve({ items: Array.from({ length: 30 }, (_, index) => customer(page * 30 + index)), page, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) })
      }
      return Promise.resolve(undefined)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/team-data', component: { template: '<div />' } }],
    })
    await router.push('/team-data?teamId=team-1&membershipId=member-2&search=Mock&grade=2026&tab=customers&view=table')
    await router.isReady()
    const wrapper = mount(TeamDataView, { global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()
    expect(wrapper.text()).toContain('团队数据')
    expect(wrapper.text()).toContain('240')
    expect(wrapper.get('.segmented-tabs button:first-child').text()).toBe('客户 240')
    expect(wrapper.get('[data-testid="active-customer-count"]').text()).toBe('240')
    expect(wrapper.findAll('.team-metrics article')).toHaveLength(7)
    expect(wrapper.findAll('.team-customer-table tbody tr')).toHaveLength(30)
    expect(wrapper.text()).toContain('负责人甲')
    expect((wrapper.find('.team-data-filters select').element as HTMLSelectElement).value).toBe('team-1')
    expect((wrapper.findAll('.team-data-filters select')[1].element as HTMLSelectElement).value).toBe('member-2')
    expect((wrapper.find('.team-data-filters input').element as HTMLInputElement).value).toBe('Mock')
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/team-data\/overview\?.*membershipId=member-2.*search=Mock.*grade=2026/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?.*membershipId=member-2.*search=Mock.*grade=2026/))
    expect(wrapper.findComponent(RouterLinkStub).props('to')).toEqual({
      path: '/customers/customer-0',
      query: {
        returnTo: '/team-data?tab=customers&view=table&teamId=team-1&membershipId=member-2&search=Mock&grade=2026',
      },
    })
    await wrapper.get('[data-testid="team-customer-view-switch"] button:last-child').trigger('click')
    expect(wrapper.findAll('.customer-row')).toHaveLength(30)
    const gradeButton = wrapper.findAll('[data-testid="grade-filter"] button').find((button) => button.text() === '2025')
    await gradeButton!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="active-customer-count"]').text()).toBe('120')
    expect(wrapper.get('.segmented-tabs button:first-child').text()).toBe('客户 120')
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/team-data\/overview\?.*grade=2025/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?.*grade=2025/))
    await wrapper.get('[data-testid="team-customer-pagination"] button:last-child').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?.*grade=2025.*page=1.*size=50/))
    await wrapper.findAll('.segmented-tabs button')[2].trigger('click')
    expect(wrapper.findAll('.report-row')).toHaveLength(9)
    await wrapper.findAll('.segmented-tabs button')[3].trigger('click')
    expect(wrapper.findAll('tbody tr')).toHaveLength(12)
  })

  it('returns a saved team customer edit to the original filtered team-data route', async () => {
    authenticate(['TEAM_MANAGER'])
    const existing = {
      ...customer(1),
      id: 'customer-1',
      fullName: '张同学',
      school: '示例大学',
      major: '计算机科学与技术',
      grade: '2026',
      city: '示例市',
      sourceChannel: '小红书',
      demandSummary: '',
      ownerNotes: '',
      nextFollowUpAt: undefined,
      friendAddedAt: undefined,
      friendTrustedAt: undefined,
      friendQualifiedAt: undefined,
      focusDirections: ['考研'],
      tags: [],
    }
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/tags' || path === '/api/v1/dictionaries') return Promise.resolve([])
      if (path === '/api/v1/customers/customer-1' && !init?.method) return Promise.resolve(existing)
      if (path === '/api/v1/customers/duplicate-check') {
        return Promise.resolve({ blocking: false, requiresConfirmation: false, hiddenExactMatches: 0, matches: [] })
      }
      if (path === '/api/v1/customers/customer-1' && init?.method === 'PATCH') return Promise.resolve({ id: 'customer-1' })
      return Promise.resolve(undefined)
    })
    const returnTo = '/team-data?teamId=team-1&membershipId=member-2&search=%E5%BC%A0&grade=2026&tab=customers&view=cards'
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/team-data', component: { template: '<div />' } },
        { path: '/customers/:id', component: { template: '<div />' } },
        { path: '/customers/:id/edit', component: CustomerFormView },
      ],
    })
    await router.push(returnTo)
    await router.push({ path: '/customers/customer-1', query: { returnTo } })
    await router.push({ path: '/customers/customer-1/edit', query: { returnTo } })
    await router.isReady()
    const wrapper = mount(CustomerFormView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await new Promise((resolve) => window.setTimeout(resolve, 0))

    const updateCall = apiMock.mock.calls.find(
      ([path, init]) => path === '/api/v1/customers/customer-1' && init?.method === 'PATCH',
    )
    const updateBody = JSON.parse(String(updateCall?.[1]?.body))
    expect(updateBody.sourceChannel).toBe('小红书')
    expect(updateBody).not.toHaveProperty('sourceDictionaryId')
    expect(router.currentRoute.value.fullPath).toBe(returnTo)
    expect(window.sessionStorage.getItem('yingge:team-data-resume')).toContain('张同学的客户档案已保存')
  })

  it('shows weighted announcements to members and publishing controls to administrators', async () => {
    authenticate(['ORG_ADMIN'])
    const rows = [{ id: 'notice-1', authorMembershipId: 'member-1', authorDisplayName: '管理员', title: '紧急集合通知', content: '今晚八点前完成日报。', priority: 'URGENT', pinned: true, showOnLeadershipScreen: true, status: 'PUBLISHED', publishedAt: '2026-07-20T00:00:00Z' }]
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path.startsWith('/api/v1/announcements') && !init) return Promise.resolve(rows)
      if (path === '/api/v1/announcements' && init?.method === 'POST') return Promise.resolve({ ...rows[0], id: 'notice-2' })
      return Promise.resolve(undefined)
    })
    const wrapper = mount(AnnouncementsView)
    await flushPromises()
    expect(wrapper.text()).toContain('紧急集合通知')
    expect(wrapper.text()).toContain('置顶')
    expect(wrapper.text()).toContain('大屏轮播')
    expect(wrapper.find('.announcement-form').exists()).toBe(true)
    await wrapper.get('.announcement-form input').setValue('新公告')
    await wrapper.get('.announcement-form textarea').setValue('公告正文')
    await wrapper.get('.announcement-form').trigger('submit')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/announcements', expect.objectContaining({ method: 'POST' }))
  })

  it('lets a team leader publish announcements and manage only their own cards', async () => {
    authenticate(['TEAM_MANAGER'])
    const rows = [
      { id: 'own', authorMembershipId: 'member-1', authorDisplayName: '模拟成员', title: '本团队公告', content: '执行要求', priority: 'IMPORTANT', pinned: false, showOnLeadershipScreen: false, status: 'PUBLISHED', publishedAt: '2026-07-20T00:00:00Z' },
      { id: 'admin', authorMembershipId: 'admin-member', authorDisplayName: '管理员', title: '管理员公告', content: '组织要求', priority: 'URGENT', pinned: true, showOnLeadershipScreen: false, status: 'PUBLISHED', publishedAt: '2026-07-20T01:00:00Z' },
    ]
    apiMock.mockResolvedValue(rows)
    const wrapper = mount(AnnouncementsView)
    await flushPromises()
    expect(wrapper.find('.announcement-form').exists()).toBe(true)
    expect(wrapper.findAll('.announcement-card footer .danger-link')).toHaveLength(1)
    expect(wrapper.text()).toContain('管理员公告')
  })
})
