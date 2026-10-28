import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import appRouter from '../router'
import { ApiError } from '../lib/api'
import { useAuthStore } from '../stores/auth'
import DailySummaryView from './DailySummaryView.vue'

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

function authenticate(roles = ['MEMBER']) {
  const auth = useAuthStore()
  auth.user = {
    userId: 'user-1', membershipId: 'member-1', organizationId: 'org-1',
    username: 'member', displayName: '王同学', roles, passwordChangeRequired: false,
  }
  auth.checked = true
  return auth
}

function report(overrides: Record<string, unknown> = {}) {
  return {
    membershipId: 'member-1', memberDisplayName: '王同学', reportDate: '2026-08-21',
    grade26NewValidLeads: 3, deepCommunicationCount: 2, pendingDeepCommunicationCount: 0,
    performanceManaged: false, todayIssues: '', tomorrowPlan: '', submitted: false,
    updatedAt: undefined,
    ...overrides,
  }
}

function installLocalStorage() {
  const values = new Map<string, string>()
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      get length() { return values.size },
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      key: (index: number) => [...values.keys()][index] ?? null,
      removeItem: (key: string) => values.delete(key),
      setItem: (key: string, value: string) => values.set(key, String(value)),
    } satisfies Storage,
  })
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/daily-summary', component: DailySummaryView },
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/dashboard', component: { template: '<div>dashboard</div>' } },
      { path: '/customers', component: { template: '<div>customers</div>' } },
      { path: '/reminders', component: { template: '<div>reminders</div>' } },
      { path: '/notifications', component: { template: '<div>notifications</div>' } },
    ],
  })
  await router.push('/daily-summary')
  await router.isReady()
  const wrapper = mount(DailySummaryView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('DailySummaryView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    installLocalStorage()
    window.localStorage.clear()
    apiMock.mockReset()
    authenticate()
  })

  it('registers an authenticated standalone route outside AppLayout', () => {
    const route = appRouter.getRoutes().find((candidate) => candidate.path === '/daily-summary')
    expect(route?.meta.dailyReportContributor).toBe(true)
    expect(route?.components?.default).toBe(DailySummaryView)
  })

  it('redirects an unauthenticated direct visit to login with the exact return path', async () => {
    const auth = useAuthStore()
    auth.user = null
    auth.checked = true
    await appRouter.push('/daily-summary')
    expect(appRouter.currentRoute.value.fullPath).toBe('/login?redirect=/daily-summary')
  })

  it('keeps viewers out of the contributor-only mobile entry', async () => {
    authenticate(['VIEWER'])
    await appRouter.push('/daily-summary')
    expect(appRouter.currentRoute.value.fullPath).toBe('/dashboard')
  })

  it('renders the mobile questionnaire with automatic lead data and manual deep count', async () => {
    apiMock.mockResolvedValue(report())
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('每日小结')
    expect(wrapper.text()).toContain('王同学')
    expect(wrapper.text()).toContain('2026年8月21日星期五')
    expect(wrapper.text()).toContain('26 级新增有效线索')
    expect(wrapper.text()).toContain('3')
    expect(wrapper.get('input[name="deepCommunicationCount"]').attributes('inputmode')).toBe('numeric')
    expect(wrapper.get('textarea[name="todayIssues"]').attributes('maxlength')).toBe('5000')
    expect(wrapper.get('textarea[name="tomorrowPlan"]').attributes('maxlength')).toBe('5000')
  })

  it('reuses the role-aware mobile bottom navigation and complete more menu', async () => {
    apiMock.mockResolvedValue(report())
    const { wrapper } = await mountPage()

    const summaryLink = wrapper.get('.mobile-bottom-nav a[href="/daily-summary"]')
    expect(summaryLink.classes()).toContain('active')
    expect(wrapper.get('.mobile-bottom-nav').text()).toContain('小结')
    await wrapper.get('button[aria-label="更多功能"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('全部功能')
    expect(document.body.textContent).toContain('绩效中心')
    expect(document.body.textContent).not.toContain('组织管理')
    wrapper.unmount()
  })

  it('renders performance-managed deep communication as read-only', async () => {
    apiMock.mockResolvedValue(report({
      performanceManaged: true, deepCommunicationCount: 5, pendingDeepCommunicationCount: 2,
    }))
    const { wrapper } = await mountPage()

    expect(wrapper.find('input[name="deepCommunicationCount"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('5')
    expect(wrapper.text()).toContain('2 条待审核')
  })

  it('restores a newer local draft and keeps subsequent edits in the same account-day key', async () => {
    const key = 'yingge:daily-summary:member-1:2026-08-21'
    window.localStorage.setItem(key, JSON.stringify({
      savedAt: '2026-08-21T12:00:00Z', deepCommunicationCount: 4,
      todayIssues: '本地草稿问题', tomorrowPlan: '本地草稿计划',
    }))
    apiMock.mockResolvedValue(report({ updatedAt: '2026-08-21T10:00:00Z' }))
    const { wrapper } = await mountPage()

    expect((wrapper.get('textarea[name="todayIssues"]').element as HTMLTextAreaElement).value).toBe('本地草稿问题')
    expect((wrapper.get('input[name="deepCommunicationCount"]').element as HTMLInputElement).value).toBe('4')
    await wrapper.get('textarea[name="tomorrowPlan"]').setValue('已经修改的计划')
    await nextTick()
    expect(JSON.parse(window.localStorage.getItem(key) ?? '{}').tomorrowPlan).toBe('已经修改的计划')
  })

  it('does not overwrite server data with a stale browser draft', async () => {
    const key = 'yingge:daily-summary:member-1:2026-08-21'
    window.localStorage.setItem(key, JSON.stringify({
      savedAt: '2026-08-21T09:00:00Z', deepCommunicationCount: 1,
      todayIssues: '过期问题', tomorrowPlan: '过期计划',
    }))
    apiMock.mockResolvedValue(report({
      submitted: true, todayIssues: '服务器新内容', tomorrowPlan: '服务器新计划',
      updatedAt: '2026-08-21T11:00:00Z',
    }))
    const { wrapper } = await mountPage()

    expect((wrapper.get('textarea[name="todayIssues"]').element as HTMLTextAreaElement).value).toBe('服务器新内容')
    expect(window.localStorage.getItem(key)).toBeNull()
  })

  it('validates required answers before sending a write request', async () => {
    apiMock.mockResolvedValue(report())
    const { wrapper } = await mountPage()
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toContain('请填写今日问题')
    expect(apiMock).toHaveBeenCalledTimes(1)
  })

  it('updates the existing day once, clears the draft and shows the completion page', async () => {
    const key = 'yingge:daily-summary:member-1:2026-08-21'
    let resolveWrite!: (value: unknown) => void
    const pendingWrite = new Promise((resolve) => { resolveWrite = resolve })
    apiMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/v1/daily-reports/today') return Promise.resolve(report({ submitted: true }))
      if (path === '/api/v1/daily-reports/2026-08-21' && init?.method === 'PUT') return pendingWrite
      return Promise.resolve(undefined)
    })
    const { wrapper } = await mountPage()
    await wrapper.get('textarea[name="todayIssues"]').setValue('今日没有阻碍')
    await wrapper.get('textarea[name="tomorrowPlan"]').setValue('明天完成重点客户回访')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(apiMock.mock.calls.filter(([path]) => path === '/api/v1/daily-reports/2026-08-21')).toHaveLength(1)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolveWrite(report({
      id: 'report-1', submitted: true, todayIssues: '今日没有阻碍',
      tomorrowPlan: '明天完成重点客户回访', updatedAt: '2026-08-21T13:00:00Z',
    }))
    await flushPromises()

    const request = apiMock.mock.calls.find(([path]) => path === '/api/v1/daily-reports/2026-08-21')
    expect(request?.[1]).toMatchObject({ method: 'PUT' })
    expect(JSON.parse(request?.[1]?.body as string)).toEqual({
      deepCommunicationCount: 2,
      todayIssues: '今日没有阻碍',
      tomorrowPlan: '明天完成重点客户回访',
    })
    expect(window.localStorage.getItem(key)).toBeNull()
    expect(wrapper.text()).toContain('今日小结已保存')
    await wrapper.get('.secondary-action').trigger('click')
    expect(wrapper.text()).toContain('正在修改今日记录')
  })

  it('returns an expired session to login instead of leaving an unusable form', async () => {
    apiMock.mockRejectedValue(new ApiError(401, { code: 'UNAUTHENTICATED', message: '登录已过期' }))
    const { wrapper, router } = await mountPage()

    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/daily-summary')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(useAuthStore().user).toBeNull()
  })
})
