import { describe, expect, it } from 'vitest'
import router from '../router'
import {
  bottomNavigationForRoles,
  commonNavigationForRoles,
  desktopNavigationForRoles,
  isNavigationActive,
  moreNavigationForRoles,
  navigationForRoles,
} from './navigation'

describe('role-aware navigation manifest', () => {
  it('keeps contributor mobile tabs focused on the daily workflow', () => {
    expect(bottomNavigationForRoles(['MEMBER']).map((item) => item.id)).toEqual([
      'dashboard', 'customers', 'reminders', 'daily-summary',
    ])
    expect(bottomNavigationForRoles(['VIEWER']).map((item) => item.id)).toEqual([
      'dashboard', 'customers', 'reminders', 'notifications',
    ])
  })

  it('applies the existing role capabilities to every navigation surface', () => {
    const memberIds = navigationForRoles(['MEMBER']).map((item) => item.id)
    const managerIds = navigationForRoles(['TEAM_MANAGER']).map((item) => item.id)
    const adminIds = navigationForRoles(['ORG_ADMIN']).map((item) => item.id)
    expect(memberIds).toContain('qq-chat')
    expect(memberIds).not.toContain('imports')
    expect(memberIds).not.toContain('team-data')
    expect(managerIds).toContain('imports')
    expect(managerIds).toContain('team-data')
    expect(managerIds).not.toContain('admin-members')
    expect(adminIds).toContain('admin-members')
    expect(adminIds).toContain('market-insights')
  })

  it('uses one manifest for desktop, more groups, and role-specific shortcuts', () => {
    expect(desktopNavigationForRoles(['MEMBER']).flatMap((group) => group.items).some((item) => item.id === 'daily-summary')).toBe(false)
    expect(moreNavigationForRoles(['MEMBER']).flatMap((group) => group.items).some((item) => item.id === 'daily-summary')).toBe(false)
    expect(commonNavigationForRoles(['ORG_ADMIN']).map((item) => item.id)).toEqual([
      'admin-governance', 'team-data', 'admin-members', 'admin-performance', 'notifications', 'admin-intelligence',
    ])
  })

  it('keeps customer detail and nested administration routes highlighted', () => {
    expect(isNavigationActive('/customers/customer-1', '/customers')).toBe(true)
    expect(isNavigationActive('/admin/agents/session', '/admin/agents')).toBe(true)
    expect(isNavigationActive('/dashboard/stale', '/dashboard')).toBe(false)
  })

  it('keeps the navigation manifest synchronized with every shell route', () => {
    for (const item of navigationForRoles(['ORG_ADMIN', 'TEAM_MANAGER', 'MEMBER', 'VIEWER'])) {
      expect(router.resolve(item.to).matched.length, `missing router target for ${item.to}`).toBeGreaterThan(0)
    }

    const shell = router.getRoutes().find((route) => route.path === '/')
    const intentionallyContextual = new Set([
      '/customers/:id',
      '/customers/:id/edit',
      '/agent/approvals/:id',
      '/oauth/authorize',
      '/feishu-workbench',
      '/settings',
    ])
    const manifestPaths = new Set(navigationForRoles(['ORG_ADMIN', 'TEAM_MANAGER', 'MEMBER', 'VIEWER']).map((item) => item.to))
    const missing = (shell?.children ?? [])
      .filter((route) => route.path && !route.redirect)
      .map((route) => `/${route.path}`)
      .filter((path) => !intentionallyContextual.has(path) && !manifestPaths.has(path))
    expect(missing).toEqual([])
  })
})
