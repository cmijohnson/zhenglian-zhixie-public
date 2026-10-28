import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ExpandableText from './ExpandableText.vue'
import MobileBottomNav from './MobileBottomNav.vue'
import MobileFilterSheet from './MobileFilterSheet.vue'
import MobileMoreSheet from './MobileMoreSheet.vue'
import ResponsiveDataView from './ResponsiveDataView.vue'
import { bottomNavigationForRoles, commonNavigationForRoles, moreNavigationForRoles } from '../lib/navigation'

function testRouter(path = '/dashboard') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', component: { template: '<div>dashboard</div>' } },
      { path: '/customers', component: { template: '<div>customers</div>' } },
      { path: '/reminders', component: { template: '<div>reminders</div>' } },
      { path: '/daily-summary', component: { template: '<div>summary</div>' } },
      { path: '/notifications', component: { template: '<div>notifications</div>' } },
    ],
  })
  return router.push(path).then(() => router.isReady()).then(() => router)
}

afterEach(() => {
  document.body.innerHTML = ''
  document.body.style.overflow = ''
  vi.restoreAllMocks()
})

describe('mobile shell components', () => {
  it('renders five touch destinations with accurate reminder and notification badges', async () => {
    const router = await testRouter('/reminders')
    const wrapper = mount(MobileBottomNav, {
      props: { items: bottomNavigationForRoles(['VIEWER']), urgentCount: 3, unreadCount: 12 },
      global: { plugins: [router] },
    })
    expect(wrapper.findAll('nav > *')).toHaveLength(5)
    expect(wrapper.get('[href="/reminders"]').attributes('aria-label')).toContain('3 位 A 类客户超时')
    expect(wrapper.get('[href="/notifications"] .mobile-nav-badge').text()).toBe('12')
    expect(wrapper.get('[href="/reminders"]').classes()).toContain('active')
    await wrapper.get('button[aria-label="更多功能"]').trigger('click')
    expect(wrapper.emitted('more')).toHaveLength(1)
  })

  it('searches the permission-trimmed more menu and restores body scrolling on close', async () => {
    const wrapper = mount(MobileMoreSheet, {
      attachTo: document.body,
      props: {
        open: true,
        groups: moreNavigationForRoles(['ORG_ADMIN']),
        commonItems: commonNavigationForRoles(['ORG_ADMIN']),
        currentPath: '/admin/governance',
        displayName: '管理员',
        roles: ['ORG_ADMIN'],
      },
    })
    await flushPromises()
    expect(document.body.style.overflow).toBe('hidden')
    expect(document.body.textContent).toContain('市场治理台')
    const search = document.querySelector<HTMLInputElement>('input[aria-label="搜索功能"]')!
    search.value = '智能分析'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(document.body.textContent).toContain('客户智能分析')
    expect(document.body.textContent).not.toContain('成员账户')
    document.querySelector<HTMLElement>('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ open: false })
    await flushPromises()
    expect(document.body.style.overflow).toBe('')
    wrapper.unmount()
  })

  it('provides a reusable mobile filter footer contract', async () => {
    const wrapper = mount(MobileFilterSheet, {
      attachTo: document.body,
      props: { open: true },
      slots: { default: '<label>团队<input value="一组"></label>' },
    })
    document.querySelector<HTMLButtonElement>('.mobile-filter-actions .primary-button')!.click()
    document.querySelector<HTMLButtonElement>('.mobile-filter-actions .ghost-button')!.click()
    await flushPromises()
    expect(wrapper.emitted('apply')).toHaveLength(1)
    expect(wrapper.emitted('reset')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('mobile content primitives', () => {
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(120)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(48)
  })

  it('collapses long text and allows explicit expansion', async () => {
    const wrapper = mount(ExpandableText, { props: { text: '这是一段需要折叠的很长内容', lines: 2 } })
    await flushPromises()
    expect(wrapper.get('button').text()).toContain('展开全文')
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('p').classes()).toContain('expanded')
    expect(wrapper.get('button').text()).toContain('收起')
  })

  it('renders matching desktop cells and labelled mobile cards', () => {
    const wrapper = mount(ResponsiveDataView, {
      props: {
        items: [{ id: '1', name: '客户甲', owner: '成员甲' }],
        columns: [
          { key: 'name', label: '客户', mobileRole: 'primary' },
          { key: 'owner', label: '负责人' },
        ],
      },
    })
    expect(wrapper.get('table').text()).toContain('客户甲')
    expect(wrapper.get('.responsive-data-mobile').text()).toContain('负责人')
    expect(wrapper.get('.responsive-data-mobile').text()).toContain('成员甲')
  })
})
