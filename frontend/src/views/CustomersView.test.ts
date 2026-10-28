import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import type { Customer } from '../types'
import CustomersView from './CustomersView.vue'

const apiMock = vi.hoisted(() => vi.fn())

vi.mock('../lib/api', () => ({ api: apiMock }))

function authenticate(roles: string[], membershipId = 'member-1') {
  const auth = useAuthStore()
  auth.user = {
    userId: `user-${membershipId}`,
    membershipId,
    organizationId: 'org-1',
    username: membershipId,
    displayName: '测试账号',
    roles,
    passwordChangeRequired: false,
  }
  auth.checked = true
}

function customer(overrides: Partial<Customer> = {}): Customer {
  return {
    id: 'customer-1',
    ownerMembershipId: 'owner-1',
    ownerDisplayName: '负责人甲',
    createdByMembershipId: 'creator-1',
    creatorDisplayName: '录入人乙',
    teamId: 'team-1',
    visibility: 'ORGANIZATION',
    fullName: '客户甲',
    nickname: '小戴',
    phone: '13800000000',
    wechatId: 'wechat-account-with-a-very-long-name',
    qqId: '1471632013',
    school: '示例大学',
    major: '工业工程',
    grade: '2026级',
    city: '示例市',
    identityType: '在校生',
    focusDirections: ['转专业'],
    intentionLevel: '高',
    urgencyLevel: '中',
    verificationStatus: '已核实',
    currentStatus: '跟进中',
    leadReviewStatus: 'VALID',
    lastContactAt: '2026-07-20T08:00:00Z',
    nextFollowUpAt: '2026-07-22T08:00:00Z',
    createdAt: '2026-07-19T08:00:00Z',
    updatedAt: '2026-07-21T08:00:00Z',
    version: 1,
    tags: [],
    dealState: 'NONE',
    canRequestDeal: true,
    ...overrides,
  }
}

function page(items: Customer[], totalPages = 1) {
  return { items, page: 0, size: items.length, totalElements: items.length, totalPages }
}

function mountView() {
  return mount(CustomersView, { global: { stubs: { RouterLink: RouterLinkStub } } })
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
    },
  })
}

describe('administrator customer table view', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    setActivePinia(createPinia())
    installLocalStorage()
    window.localStorage.clear()
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false, media: query, onchange: null,
        addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
      })),
    })
    apiMock.mockReset()
  })

  it('defaults administrators to a 50-row management table and maps team states', async () => {
    authenticate(['ORG_ADMIN'])
    const rows = [
      customer(),
      customer({ id: 'customer-2', fullName: '微信客户', teamId: undefined, nickname: undefined, phone: undefined, wechatId: 'only-wechat', qqId: undefined, school: undefined, major: undefined, grade: undefined, lastContactAt: undefined, nextFollowUpAt: undefined }),
      customer({ id: 'customer-3', fullName: '历史团队客户', teamId: 'deleted-team', phone: undefined, wechatId: undefined, qqId: 'only-qq', archivedAt: '2026-07-21T09:00:00Z', leadReviewStatus: 'INVALID' }),
      customer({ id: 'customer-4', fullName: '无联系方式客户', phone: undefined, wechatId: undefined, qqId: undefined }),
    ]
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/teams') return Promise.resolve([{ id: 'team-1', name: '江苏营销一组' }])
      if (path === '/api/v1/admin/members') return Promise.resolve([{ membershipId: 'owner-1', displayName: '负责人甲', status: 'ACTIVE', roles: ['MEMBER'], teamNames: '江苏营销一组' }])
      if (path === '/api/v1/dictionaries') return Promise.resolve([{ type: 'SCHOOL', label: '示例大学' }, { type: 'SCHOOL', label: '示例科技大学' }])
      if (path.startsWith('/api/v1/customers?')) return Promise.resolve(page(rows))
      return Promise.resolve(undefined)
    })

    const wrapper = mountView()
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?page=0&size=50&sort=updatedDesc/))
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/teams')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/members')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/dictionaries')
    expect(wrapper.get('[data-testid="customer-table"]').classes()).toContain('customer-table-panel')
    expect(wrapper.findAll('.customer-admin-table thead th')).toHaveLength(15)
    expect(wrapper.findAll('.customer-admin-table tbody tr')).toHaveLength(4)
    expect(wrapper.text()).toContain('江苏营销一组')
    expect(wrapper.text()).toContain('未分组')
    expect(wrapper.text()).toContain('历史/已删除团队')
    expect(wrapper.text()).toContain('微信 only-wechat')
    expect(wrapper.text()).toContain('QQ only-qq')
    expect(wrapper.text()).toContain('暂无联系方式')
    expect(wrapper.text()).not.toContain('手机未填')
    expect(wrapper.text()).not.toContain('微信/QQ 未填')
    expect(wrapper.text()).toContain('已归档')
    expect(wrapper.text()).toContain('最近沟通')
    expect(wrapper.text()).toContain('下次跟进')
    expect(wrapper.findAll('.customer-table-actions a')).toHaveLength(8)
    expect(wrapper.get('.customer-search-group').element.lastElementChild).toBe(wrapper.get('.customer-search-button').element)
  })

  it('switches page size, persists the account preference and restores the default after clearing it', async () => {
    authenticate(['ORG_ADMIN'], 'admin-account')
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/teams' || path === '/api/v1/admin/members' || path === '/api/v1/dictionaries') return Promise.resolve([])
      if (path.startsWith('/api/v1/customers?')) return Promise.resolve(page([customer()]))
      return Promise.resolve(undefined)
    })

    const first = mountView()
    await flushPromises()
    await first.get('[data-testid="customer-view-switch"] button:last-child').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringContaining('size=30'))
    expect(window.localStorage.getItem('yingge.customers.view-mode:admin-account')).toBe('cards')
    expect(first.find('.customer-row').exists()).toBe(true)
    first.unmount()

    apiMock.mockClear()
    const restored = mountView()
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?page=0&size=30/))
    expect(restored.find('.customer-row').exists()).toBe(true)
    restored.unmount()

    window.localStorage.clear()
    apiMock.mockClear()
    const reset = mountView()
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?page=0&size=50/))
    expect(reset.find('[data-testid="customer-table"]').exists()).toBe(true)
  })

  it('defaults an administrator phone viewport to cards and opens dense filters in a mobile sheet', async () => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: query === '(max-width: 820px)', media: query, onchange: null,
        addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
      })),
    })
    authenticate(['ORG_ADMIN'], 'mobile-admin')
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/teams' || path === '/api/v1/admin/members' || path === '/api/v1/dictionaries') return Promise.resolve([])
      if (path.startsWith('/api/v1/customers?')) return Promise.resolve(page([customer()]))
      return Promise.resolve(undefined)
    })

    const wrapper = mountView()
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?page=0&size=30/))
    expect(wrapper.find('.customer-row').exists()).toBe(true)
    expect(wrapper.get('.mobile-filter-toggle').attributes('aria-expanded')).toBe('false')
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    await wrapper.get('.mobile-filter-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.get('.mobile-filter-toggle').attributes('aria-expanded')).toBe('true')
    const sheet = document.body.querySelector('[role="dialog"]')
    expect(sheet?.textContent).toContain('筛选客户')
    expect(sheet?.querySelector('input[aria-label="移动端搜索客户"]')).not.toBeNull()
    expect(sheet?.textContent).toContain('负责人')
  })

  it.each(['TEAM_MANAGER', 'MEMBER', 'VIEWER'])('keeps %s on 30-row cards without calling admin teams', async (role) => {
    authenticate([role])
    apiMock.mockResolvedValue(page([customer()]))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="customer-view-switch"]').exists()).toBe(false)
    expect(wrapper.find('.customer-row').exists()).toBe(true)
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/customers\?page=0&size=30/))
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/teams')
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/members')
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/dictionaries')
  })

  it('keeps filters across sorting and pagination in table mode', async () => {
    vi.useFakeTimers()
    authenticate(['ORG_ADMIN'])
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/teams') return Promise.resolve([{ id: 'team-1', name: '江苏营销一组' }])
      if (path === '/api/v1/admin/members') return Promise.resolve([{ membershipId: 'owner-1', displayName: '负责人甲', status: 'ACTIVE', roles: ['MEMBER'] }])
      if (path === '/api/v1/dictionaries') return Promise.resolve([{ type: 'SCHOOL', label: '示例大学' }])
      if (path.startsWith('/api/v1/customers?')) return Promise.resolve(page([customer()], 2))
      return Promise.resolve(undefined)
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.customer-search-group input').setValue('客户甲')
    vi.advanceTimersByTime(250)
    await flushPromises()
    await wrapper.get('select[aria-label="客户状态"]').setValue('跟进中')
    await wrapper.get('select[aria-label="意向级别"]').setValue('高')
    await wrapper.get('select[aria-label="客户排序"]').setValue('createdDesc')
    await wrapper.get('input[aria-label="筛选院校"]').setValue('示例大学')
    vi.advanceTimersByTime(250)
    await wrapper.get('select[aria-label="筛选团队"]').setValue('team-1')
    await wrapper.get('select[aria-label="筛选负责人"]').setValue('owner-1')
    await wrapper.get('.check-label input').setValue(true)
    const gradeButton = wrapper.findAll('[data-testid="grade-filter"] button').find((button) => button.text() === '2026')
    await gradeButton!.trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/search=%E6%88%B4%E8%87%BB%E5%A5%BD/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/status=%E8%B7%9F%E8%BF%9B%E4%B8%AD/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/intention=%E9%AB%98/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/sort=createdDesc/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/grade=2026/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/school=%E6%B1%9F%E8%8B%8F%E5%A4%A7%E5%AD%A6/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/teamId=team-1/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/ownerMembershipId=owner-1/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/includeArchived=true/))

    await wrapper.get('.pagination button:last-child').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/page=1&size=50&sort=createdDesc/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/grade=2026/))
    expect(apiMock).toHaveBeenCalledWith(expect.stringMatching(/ownerMembershipId=owner-1/))
    expect(wrapper.get('select[aria-label="客户排序"]').findAll('option').map((option) => option.attributes('value'))).toEqual([
      'updatedDesc', 'createdDesc', 'lastContactDesc', 'nextFollowUpAsc',
    ])
    vi.useRealTimers()
  })
})
