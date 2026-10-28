import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import AnnouncementsView from './AnnouncementsView.vue'
import NotificationsView from './NotificationsView.vue'
import ReleasesView from './ReleasesView.vue'
import TaskCenterView from './TaskCenterView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({
  api: apiMock,
  jsonBody: (value: unknown) => JSON.stringify(value),
  dateTime: (value: string) => value,
  ApiError: class ApiError extends Error {
    constructor(public status: number, public body: { message: string }) {
      super(body.message)
    }
  },
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : to?.path"><slot /></a>',
}

function authenticate(roles: string[]) {
  const auth = useAuthStore()
  auth.user = {
    userId: 'user-1', membershipId: 'member-1', organizationId: 'org-1',
    username: 'member', displayName: '成员甲', roles, passwordChangeRequired: false,
  }
  auth.checked = true
}

describe('mobile business page interactions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    authenticate(['MEMBER'])
  })

  it('collapses and expands long announcement content without changing the record', async () => {
    const content = '这是需要在手机端收起的公告正文。'.repeat(16)
    apiMock.mockResolvedValue([{
      id: 'announcement-1', authorMembershipId: 'member-2', authorDisplayName: '管理员',
      title: '重要安排', content, priority: 'IMPORTANT', pinned: true,
      showOnLeadershipScreen: false, status: 'PUBLISHED', publishedAt: '2026-08-22T09:00:00Z',
    }])
    const wrapper = mount(AnnouncementsView)
    await flushPromises()

    const toggle = wrapper.get('.content-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.announcement-content').classes()).not.toContain('expanded')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.announcement-content').classes()).toContain('expanded')
  })

  it('expands a long notification while preserving its action link', async () => {
    apiMock.mockResolvedValue({ unreadCount: 1, items: [{
      id: 'notice-1', eventType: 'FOLLOW_UP_RISK', title: '重点客户提醒',
      summary: '需要尽快补充真实沟通记录并安排下一步。'.repeat(10), targetPath: '/customers/customer-1',
      read: false, createdAt: '2026-08-22T09:00:00Z', expiresAt: '2026-08-29T09:00:00Z',
    }] })
    const wrapper = mount(NotificationsView, { global: { stubs: { RouterLink: RouterLinkStub } } })
    await flushPromises()

    expect(wrapper.get('.notification-item a').attributes('href')).toBe('/customers/customer-1')
    await wrapper.get('.summary-toggle').trigger('click')
    expect(wrapper.get('.notification-item p').classes()).toContain('expanded')
  })

  it('keeps release notes compact until the user asks for the full version detail', async () => {
    apiMock.mockImplementation((path: string) => path === '/api/v1/mobile/app-releases/current' ? Promise.resolve({
      id: 'mobile-release-8', platform: 'ANDROID', versionCode: 8, versionName: '0.2.3',
      notes: '修复应用内更新下载', apkSize: 5242880,
    }) : Promise.resolve({
      items: [{ id: 'release-1', version: '2026.08.22.1', title: '移动体验升级',
        summary: '覆盖全站手机交互与长内容阅读。'.repeat(8), current: true, acknowledged: false,
        releasedAt: '2026-08-22T09:00:00Z', sections: { 新增: ['移动底部导航', '紧凑日报日历'], 改进: ['长内容按需展开'] } }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    }))
    const wrapper = mount(ReleasesView)
    await flushPromises()

    expect(wrapper.get('.android-download-card a').attributes('href')).toBe('/api/v1/mobile/app-releases/mobile-release-8/apk')
    expect(wrapper.get('.android-download-card').text()).toContain('5.0 MB')
    expect(wrapper.get('.release-sections').classes()).not.toContain('expanded')
    await wrapper.get('.release-toggle').trigger('click')
    expect(wrapper.get('.release-sections').classes()).toContain('expanded')
  })

  it('renders a discoverable horizontal-scroll hint for the administrator task matrix', async () => {
    authenticate(['ORG_ADMIN'])
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/tasks/matrix') return Promise.resolve([{
        task: { id: 'task-1', title: '客户复盘', description: '逐项核对客户跟进状态',
          distributionType: 'MANDATORY', startAt: '2026-08-22T08:00:00Z', dueAt: '2026-08-23T08:00:00Z',
          status: 'PUBLISHED', assignmentCount: 1, completedCount: 0, overdue: false, claimable: false },
        assignments: [{ id: 'assignment-1', membershipId: 'member-1', memberDisplayName: '成员甲',
          assignmentSource: 'MANDATORY', status: 'PENDING' }],
      }])
      if (path === '/api/v1/admin/members') return Promise.resolve([{
        membershipId: 'member-1', displayName: '成员甲', status: 'ACTIVE', roles: ['MEMBER'],
      }])
      return Promise.resolve([])
    })
    const wrapper = mount(TaskCenterView)
    await flushPromises()

    expect(wrapper.get('[data-testid="task-matrix"] .mobile-scroll-hint').text()).toContain('左右滑动')
    expect(wrapper.get('[data-testid="task-matrix"] table').classes()).toContain('task-matrix-table')
  })
})
