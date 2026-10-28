export type NavigationCapability = 'all' | 'write' | 'daily' | 'bulk' | 'team-data' | 'admin'

export type NavigationGroupKey = 'core' | 'daily' | 'collaboration' | 'tools' | 'data' | 'admin'

export interface NavigationItem {
  id: string
  to: string
  label: string
  shortLabel?: string
  icon: string
  group: NavigationGroupKey
  capability: NavigationCapability
  desktop?: boolean
}

export interface NavigationGroup {
  key: NavigationGroupKey
  label: string
  items: NavigationItem[]
}

const groupLabels: Record<NavigationGroupKey, string> = {
  core: '主要功能',
  daily: '每日执行',
  collaboration: '客户协作',
  tools: '效率工具',
  data: '团队与数据',
  admin: '组织管理',
}

const groupOrder: NavigationGroupKey[] = ['core', 'daily', 'collaboration', 'tools', 'data', 'admin']

export const navigationItems: NavigationItem[] = [
  { id: 'dashboard', to: '/dashboard', label: '工作台', icon: '▦', group: 'core', capability: 'all' },
  { id: 'customers', to: '/customers', label: '客户档案', shortLabel: '客户', icon: '◉', group: 'core', capability: 'all' },
  { id: 'reminders', to: '/reminders', label: '跟进提醒', shortLabel: '提醒', icon: '◷', group: 'core', capability: 'all' },
  { id: 'notifications', to: '/notifications', label: '通知中心', shortLabel: '通知', icon: '◇', group: 'collaboration', capability: 'all' },
  { id: 'releases', to: '/releases', label: '更新日志', icon: '⬡', group: 'collaboration', capability: 'all' },
  { id: 'tasks', to: '/tasks', label: '任务中心', icon: '☑', group: 'collaboration', capability: 'all' },
  { id: 'circle', to: '/circle', label: '内容圈子', icon: '◎', group: 'collaboration', capability: 'all' },
  { id: 'announcements', to: '/announcements', label: '公告中心', icon: '◫', group: 'collaboration', capability: 'all' },
  { id: 'customer-pool', to: '/customer-pool', label: '客户公共池', icon: '↻', group: 'collaboration', capability: 'write' },
  { id: 'new-customer', to: '/customers/new', label: '新建客户', icon: '＋', group: 'collaboration', capability: 'write', desktop: false },

  { id: 'performance', to: '/performance', label: '绩效中心', icon: '◆', group: 'daily', capability: 'all' },
  { id: 'consultations', to: '/consultations', label: '咨询交接', icon: '⇄', group: 'daily', capability: 'all' },
  { id: 'daily-report', to: '/daily-report', label: '每日汇报', icon: '≡', group: 'daily', capability: 'all' },
  { id: 'daily-summary', to: '/daily-summary', label: '每日小结', shortLabel: '小结', icon: '✐', group: 'daily', capability: 'daily', desktop: false },
  { id: 'content-tasks', to: '/content-tasks', label: '内容任务', icon: '◈', group: 'daily', capability: 'all' },

  { id: 'assistant', to: '/assistant', label: '智能助手', icon: '✦', group: 'tools', capability: 'all' },
  { id: 'qq-chat', to: '/qq-chat', label: 'QQ 对话', icon: '◌', group: 'tools', capability: 'write' },
  { id: 'agent', to: '/agent', label: '我的 Agent', icon: '⌘', group: 'tools', capability: 'all' },
  { id: 'imports', to: '/imports', label: '批量导入', icon: '⇥', group: 'tools', capability: 'bulk' },
  { id: 'exports', to: '/exports', label: '数据导出', icon: '⇤', group: 'tools', capability: 'bulk' },

  { id: 'leadership-dashboard', to: '/leadership-dashboard', label: '领导大屏', icon: '◈', group: 'data', capability: 'team-data' },
  { id: 'team-data', to: '/team-data', label: '团队数据', icon: '◩', group: 'data', capability: 'team-data' },
  { id: 'market-insights', to: '/market-insights', label: '业务洞察', icon: '◉', group: 'data', capability: 'team-data' },

  { id: 'admin-members', to: '/admin/members', label: '成员账户', icon: '♙', group: 'admin', capability: 'admin' },
  { id: 'admin-teams', to: '/admin/teams', label: '团队设置', icon: '◇', group: 'admin', capability: 'admin' },
  { id: 'admin-dictionaries', to: '/admin/dictionaries', label: '字典标签', icon: '⌘', group: 'admin', capability: 'admin' },
  { id: 'admin-audit', to: '/admin/audit', label: '审计日志', icon: '≣', group: 'admin', capability: 'admin' },
  { id: 'admin-governance', to: '/admin/governance', label: '市场治理台', icon: '◉', group: 'admin', capability: 'admin' },
  { id: 'admin-assistant', to: '/admin/assistant', label: '智能助手配置', icon: '✦', group: 'admin', capability: 'admin' },
  { id: 'admin-leadership', to: '/admin/leadership-screen', label: '大屏设置', icon: '◈', group: 'admin', capability: 'admin' },
  { id: 'admin-agents', to: '/admin/agents', label: 'Agent 管理', icon: '⌘', group: 'admin', capability: 'admin' },
  { id: 'admin-performance', to: '/admin/performance', label: '绩效管理', icon: '◆', group: 'admin', capability: 'admin' },
  { id: 'admin-follow-up', to: '/admin/follow-up', label: '跟进与咨询设置', icon: '⇄', group: 'admin', capability: 'admin' },
  { id: 'admin-enrichment', to: '/admin/customer-enrichment', label: '客户资料补全', icon: '⊕', group: 'admin', capability: 'admin' },
  { id: 'admin-intelligence', to: '/admin/customer-intelligence', label: '客户智能分析', icon: '✧', group: 'admin', capability: 'admin' },
  { id: 'admin-feishu', to: '/admin/feishu', label: '飞书多维表格', icon: '▦', group: 'admin', capability: 'admin' },
]

function hasRole(roles: string[], role: string) {
  return roles.includes(role)
}

export function canAccessNavigation(item: NavigationItem, roles: string[]) {
  if (item.capability === 'all') return true
  if (item.capability === 'admin') return hasRole(roles, 'ORG_ADMIN')
  if (item.capability === 'team-data' || item.capability === 'bulk') {
    return hasRole(roles, 'ORG_ADMIN') || hasRole(roles, 'TEAM_MANAGER')
  }
  if (item.capability === 'write' || item.capability === 'daily') {
    return roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER' || role === 'MEMBER')
  }
  return false
}

export function navigationForRoles(roles: string[]) {
  return navigationItems.filter((item) => canAccessNavigation(item, roles))
}

export function groupNavigation(items: NavigationItem[]) {
  return groupOrder
    .map((key) => ({ key, label: groupLabels[key], items: items.filter((item) => item.group === key) }))
    .filter((group) => group.items.length > 0)
}

export function desktopNavigationForRoles(roles: string[]) {
  return groupNavigation(navigationForRoles(roles).filter((item) => item.desktop !== false))
}

export function bottomNavigationForRoles(roles: string[]) {
  const ids = roles.some((role) => role === 'ORG_ADMIN' || role === 'TEAM_MANAGER' || role === 'MEMBER')
    ? ['dashboard', 'customers', 'reminders', 'daily-summary']
    : ['dashboard', 'customers', 'reminders', 'notifications']
  const allowed = navigationForRoles(roles)
  return ids.map((id) => allowed.find((item) => item.id === id)).filter((item): item is NavigationItem => Boolean(item))
}

export function moreNavigationForRoles(roles: string[]) {
  const fixed = new Set(bottomNavigationForRoles(roles).map((item) => item.id))
  return groupNavigation(navigationForRoles(roles).filter((item) => !fixed.has(item.id)))
}

export function commonNavigationForRoles(roles: string[]) {
  const preferred = hasRole(roles, 'ORG_ADMIN')
    ? ['admin-governance', 'team-data', 'admin-members', 'admin-performance', 'notifications', 'admin-intelligence']
    : hasRole(roles, 'TEAM_MANAGER')
      ? ['team-data', 'market-insights', 'tasks', 'consultations', 'imports', 'exports']
      : hasRole(roles, 'MEMBER')
        ? ['new-customer', 'tasks', 'performance', 'qq-chat', 'assistant', 'customer-pool']
        : ['tasks', 'circle', 'announcements', 'releases', 'assistant', 'agent']
  const allowed = navigationForRoles(roles)
  return preferred.map((id) => allowed.find((item) => item.id === id)).filter((item): item is NavigationItem => Boolean(item))
}

export function isNavigationActive(path: string, target: string) {
  if (target === '/dashboard') return path === target
  if (target === '/customers') return path === target || path.startsWith('/customers/')
  return path === target || path.startsWith(`${target}/`)
}
