export interface TeamOption { id: string; name: string }
export interface MemberOption { membershipId: string; displayName: string; username: string; teamNames: string }
export interface Headline {
  totalCustomers: number
  addedFriendCustomers: number
  trustedFriendCustomers: number
  qualifiedFriendCustomers: number
  todayNewCustomers: number
  todayQualifiedCustomers: number
  addedRate: number
  trustRate: number
  qualifiedRate: number
}
export interface CustomerActivity {
  customerId: string
  fullName: string
  ownerName: string
  teamName: string
  currentStatus: string
  intentionLevel: string
  createdAt: string
  updatedAt: string
}
export interface MemberRanking {
  membershipId: string
  displayName: string
  teamNames: string
  totalCustomers: number
  addedFriendCustomers: number
  trustedFriendCustomers: number
  qualifiedFriendCustomers: number
  qualityACustomers: number
  todayCustomers: number
  assignedTasks: number
  completedTasks: number
  taskCompletionRate: number
}
export interface DailyConversion {
  date: string
  addedFriendCustomers: number
  trustedFriendCustomers: number
  qualifiedFriendCustomers: number
  cumulativeQualifiedCustomers: number
}
export interface TaskSummary {
  assignmentCount: number
  pendingCount: number
  inProgressCount: number
  submittedCount: number
  completedCount: number
  returnedCount: number
  overdueCount: number
  completionRate: number
}
export interface TaskProgress {
  taskId: string
  title: string
  dueAt: string
  status: string
  assignmentCount: number
  completedCount: number
  submittedCount: number
  completionRate: number
  overdue: boolean
}
export interface TaskPublication {
  taskId: string
  title: string
  distributionType: 'MANDATORY' | 'ASSIGNED' | 'SELF_CLAIM'
  startAt: string
  dueAt: string
  createdAt: string
  publisherName: string
  assignmentCount: number
}
export interface SourceSlice {
  source: string
  customerCount: number
  addedFriendCustomers: number
  trustedFriendCustomers: number
  qualifiedFriendCustomers: number
  share: number
  addedRate: number
  trustRate: number
  qualifiedRate: number
}
export interface CustomerQualitySlice {
  code: 'A' | 'B' | 'C'
  label: string
  description: string
  color: string
  customerCount: number
  share: number
}
export interface CustomerQualitySummary {
  classifiedCustomers: number
  unclassifiedCustomers: number
  levels: CustomerQualitySlice[]
}
export interface DisplayMessage {
  id: string
  title: string
  content: string
  priority: 'NORMAL' | 'IMPORTANT' | 'URGENT'
  publishedAt: string
}
export interface StaleOwner { membershipId: string; displayName: string; customerCount: number }
export interface AClassStaleSummary {
  enabled: boolean
  thresholdHours: number
  customerCount: number
  unassignedCount: number
  owners: StaleOwner[]
  calculatedAt: string
}
export interface SchoolComparison {
  featuredGrade: string
  jiangsuUniversity: number
  jiangsuUniversityOfScienceAndTechnology: number
  difference: number
  leader: 'JIANGSU_UNIVERSITY' | 'JIANGSU_UST' | 'TIED'
}
export interface TickerItem {
  id: string
  type: 'FOLLOW_UP_RISK' | 'SCHOOL_COMPARISON' | 'ANNOUNCEMENT'
  priority: 'NORMAL' | 'IMPORTANT' | 'URGENT'
  text: string
}
export type LeadershipSceneId = 'time' | 'culture' | 'briefing' | 'trend' | 'champions' | 'funnel' | 'mission' | 'followUpRisk' | 'schoolComparison'
export type LeadershipEventType =
  'zeroBreakthrough' | 'customerAdded' | 'customerUpdated'
  | 'milestone' | 'trendSurge' | 'conversionStage'
  | 'taskPublished' | 'taskMilestone' | 'rankingChange' | 'teamMomentum'
export type MotionIntensity = 'SUBTLE' | 'CINEMATIC' | 'STRONG'
export interface LeadershipScreenSettings {
  animationsEnabled: boolean
  standbyEnabled: boolean
  idleTimeoutMinutes: number
  sceneDurationSeconds: number
  eventDurationSeconds: number
  motionIntensity: MotionIntensity
  sceneOrder: LeadershipSceneId[]
  enabledScenes: LeadershipSceneId[]
  enabledEvents: LeadershipEventType[]
  featuredGrade: string
  updatedAt?: string
  version: number
}
export const defaultLeadershipScreenSettings: LeadershipScreenSettings = {
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
}
export interface LeadershipDashboardData {
  generatedAt: string
  lastBusinessActivityAt?: string
  startDate: string
  endDate: string
  days: number
  administrator: boolean
  selectedTeamId?: string
  selectedMembershipId?: string
  teams: TeamOption[]
  members: MemberOption[]
  headline: Headline
  recentCustomerActivities: CustomerActivity[]
  rankings: MemberRanking[]
  dailyConversions: DailyConversion[]
  customerQuality: CustomerQualitySummary
  taskSummary: TaskSummary
  tasks: TaskProgress[]
  recentTaskPublications: TaskPublication[]
  sources: SourceSlice[]
  displayMessages: DisplayMessage[]
  featuredGrade: string
  aClassStale: AClassStaleSummary
  schoolComparison: SchoolComparison
  tickerItems: TickerItem[]
  screenSettings: LeadershipScreenSettings
}

type EventBase = {
  id: string
  priority: number
  title: string
  eyebrow: string
  value: string
  subtitle: string
  accent: 'cyan' | 'blue' | 'purple' | 'gold' | 'green'
  delta?: number
  names?: string[]
  points?: number[]
}

type CustomerEventCard = {
  name: string
  detail: string
}

export type LeadershipAnimationEvent =
  | (EventBase & { type: 'wakeUp' })
  | (EventBase & { type: 'zeroBreakthrough'; metric: string })
  | (EventBase & { type: 'customerAdded'; customerCount: number; customers: CustomerEventCard[] })
  | (EventBase & { type: 'customerUpdated'; customerCount: number; customers: CustomerEventCard[] })
  | (EventBase & { type: 'milestone'; metric: string; previousValue: number; currentValue: number })
  | (EventBase & { type: 'trendSurge'; metric: string; growthRate: number })
  | (EventBase & { type: 'conversionStage'; metric: string; threshold: number })
  | (EventBase & {
    type: 'taskPublished'
    taskTitle: string
    publisherName: string
    dueAt: string
    assignmentCount: number
    distributionType: TaskPublication['distributionType']
  })
  | (EventBase & { type: 'taskMilestone'; threshold: number; taskTitle?: string })
  | (EventBase & { type: 'rankingChange'; metric: string; previousLeader?: string; currentLeader: string })
  | (EventBase & { type: 'teamMomentum'; memberCount: number })

type RankMetric = 'total' | 'qualityA' | 'trusted' | 'todayTotal' | 'task'
type NumberMetric = keyof Pick<Headline,
  'totalCustomers' | 'addedFriendCustomers' | 'trustedFriendCustomers' | 'qualifiedFriendCustomers'>
type RateMetric = keyof Pick<Headline, 'addedRate' | 'trustRate' | 'qualifiedRate'>

const numberMetrics: Array<{
  key: NumberMetric
  label: string
  step: number
  accent: EventBase['accent']
}> = [
  { key: 'totalCustomers', label: '客户总数', step: 10, accent: 'cyan' },
  { key: 'addedFriendCustomers', label: '添加好友', step: 5, accent: 'blue' },
  { key: 'trustedFriendCustomers', label: '建立信任', step: 5, accent: 'purple' },
  { key: 'qualifiedFriendCustomers', label: '筛选确认', step: 5, accent: 'gold' },
]

const rateMetrics: Array<{
  key: RateMetric
  label: string
  accent: EventBase['accent']
}> = [
  { key: 'addedRate', label: '客户添加率', accent: 'blue' },
  { key: 'trustRate', label: '信任转化率', accent: 'purple' },
  { key: 'qualifiedRate', label: '筛选转化率', accent: 'gold' },
]

const rankMetrics: Array<{ key: RankMetric; label: string; accent: EventBase['accent'] }> = [
  { key: 'total', label: '总添加客户榜', accent: 'cyan' },
  { key: 'todayTotal', label: '总添加客户日榜', accent: 'gold' },
  { key: 'trusted', label: '信任好友榜', accent: 'purple' },
  { key: 'qualityA', label: 'A类客户榜', accent: 'blue' },
  { key: 'task', label: '任务完成榜', accent: 'green' },
]

export function rankingValue(row: MemberRanking, metric: RankMetric) {
  if (metric === 'qualityA') return row.qualityACustomers ?? 0
  if (metric === 'trusted') return row.trustedFriendCustomers
  if (metric === 'todayTotal') return row.todayCustomers ?? 0
  if (metric === 'task') return row.completedTasks
  return row.totalCustomers
}

function sortedRanking(data: LeadershipDashboardData, metric: RankMetric) {
  return [...data.rankings].sort((left, right) =>
    rankingValue(right, metric) - rankingValue(left, metric)
      || (right.qualityACustomers ?? 0) - (left.qualityACustomers ?? 0)
      || left.displayName.localeCompare(right.displayName, 'zh-CN'))
}

function crossedThreshold(previous: number, current: number, thresholds: number[]) {
  return [...thresholds].reverse().find((threshold) => previous < threshold && current >= threshold)
}

function eventId(type: LeadershipAnimationEvent['type'], key: string) {
  return `${type}:${key}`
}

function trendEvents(previous: LeadershipDashboardData, current: LeadershipDashboardData) {
  const events: LeadershipAnimationEvent[] = []
  const definitions: Array<{
    key: keyof Pick<DailyConversion, 'addedFriendCustomers' | 'trustedFriendCustomers' | 'qualifiedFriendCustomers'>
    label: string
    accent: EventBase['accent']
  }> = [
    { key: 'addedFriendCustomers', label: '添加好友', accent: 'blue' },
    { key: 'trustedFriendCustomers', label: '建立信任', accent: 'purple' },
    { key: 'qualifiedFriendCustomers', label: '筛选确认', accent: 'gold' },
  ]
  const today = current.dailyConversions.at(-1)
  if (!today) return events
  const previousToday = previous.dailyConversions.find((item) => item.date === today.date)
  const priorDays = current.dailyConversions.slice(-8, -1)
  for (const definition of definitions) {
    const value = today[definition.key]
    const oldValue = previousToday?.[definition.key] ?? 0
    const average = priorDays.length
      ? priorDays.reduce((sum, item) => sum + item[definition.key], 0) / priorDays.length
      : 0
    if (value <= oldValue || value < 5 || (average > 0 && value < average * 1.3)) continue
    const growthRate = average > 0 ? (value - average) / average : 1
    events.push({
      id: eventId('trendSurge', `${definition.key}:${today.date}`),
      type: 'trendSurge',
      priority: 45,
      title: '趋势升空',
      eyebrow: 'TREND ACCELERATION',
      value: `+${Math.round(growthRate * 100)}%`,
      subtitle: `今日${definition.label} ${value} 人，显著高于近 7 日均值`,
      accent: definition.accent,
      metric: definition.label,
      growthRate,
      points: current.dailyConversions.slice(-8).map((item) => item[definition.key]),
    })
  }
  return events
}

export function detectLeadershipEvents(
  previous: LeadershipDashboardData,
  current: LeadershipDashboardData,
): LeadershipAnimationEvent[] {
  const events: LeadershipAnimationEvent[] = []
  const previousGeneratedAt = Date.parse(previous.generatedAt)
  const changedCustomers = (current.recentCustomerActivities ?? [])
    .filter((customer) => Date.parse(customer.updatedAt) > previousGeneratedAt)
  const addedCustomers = changedCustomers
    .filter((customer) => Date.parse(customer.createdAt) > previousGeneratedAt)
  const updatedCustomers = changedCustomers
    .filter((customer) => Date.parse(customer.createdAt) <= previousGeneratedAt)
  const netCustomerIncrease = Math.max(
    0,
    current.headline.totalCustomers - previous.headline.totalCustomers,
  )

  if (addedCustomers.length || netCustomerIncrease) {
    const customerCount = Math.max(addedCustomers.length, netCustomerIncrease)
    events.push({
      id: eventId('customerAdded', `${current.generatedAt}:${addedCustomers.map((item) => item.customerId).join(',')}`),
      type: 'customerAdded',
      priority: 96,
      title: '新客户接入',
      eyebrow: 'NEW CUSTOMER CONNECTED',
      value: `+${customerCount}`,
      subtitle: customerCount === 1 ? '一份新的客户信任正式接入经营体系' : `${customerCount} 位新客户已同步进入经营体系`,
      accent: 'cyan',
      customerCount,
      names: addedCustomers.slice(0, 8).map((customer) => customer.fullName),
      customers: addedCustomers.slice(0, 6).map((customer) => ({
        name: customer.fullName,
        detail: `${customer.ownerName} · ${customer.teamName} · ${customer.currentStatus}`,
      })),
    })
  }

  if (updatedCustomers.length) {
    events.push({
      id: eventId('customerUpdated', `${current.generatedAt}:${updatedCustomers.map((item) => item.customerId).join(',')}`),
      type: 'customerUpdated',
      priority: 94,
      title: '客户档案更新',
      eyebrow: 'CUSTOMER PROFILE SYNCHRONIZED',
      value: String(updatedCustomers.length),
      subtitle: updatedCustomers.length === 1 ? '客户信息发生变化，经营档案已实时同步' : `${updatedCustomers.length} 份客户档案已完成实时同步`,
      accent: 'blue',
      customerCount: updatedCustomers.length,
      names: updatedCustomers.slice(0, 8).map((customer) => customer.fullName),
      customers: updatedCustomers.slice(0, 6).map((customer) => ({
        name: customer.fullName,
        detail: `${customer.ownerName} · ${customer.currentStatus} · ${customer.intentionLevel}意向`,
      })),
    })
  }

  for (const metric of numberMetrics) {
    const before = previous.headline[metric.key]
    const after = current.headline[metric.key]
    if (before === 0 && after > 0) {
      events.push({
        id: eventId('zeroBreakthrough', metric.key),
        type: 'zeroBreakthrough',
        priority: 100,
        title: '破零时刻',
        eyebrow: 'ZERO BREAKTHROUGH',
        value: String(after),
        subtitle: `${metric.label}完成从 0 到 ${after} 的首次突破`,
        accent: metric.accent,
        delta: after,
        metric: metric.label,
      })
      continue
    }
    if (after > before && Math.floor(after / metric.step) > Math.floor(before / metric.step)) {
      events.push({
        id: eventId('milestone', `${metric.key}:${Math.floor(after / metric.step)}`),
        type: 'milestone',
        priority: 55,
        title: '数字跃迁',
        eyebrow: 'PERFORMANCE MILESTONE',
        value: after.toLocaleString(),
        subtitle: `${metric.label}跨越阶段节点，本轮增长 +${after - before}`,
        accent: metric.accent,
        delta: after - before,
        metric: metric.label,
        previousValue: before,
        currentValue: after,
        points: current.dailyConversions.slice(-7).map((item) =>
          metric.key === 'addedFriendCustomers' ? item.addedFriendCustomers
            : metric.key === 'trustedFriendCustomers' ? item.trustedFriendCustomers
              : metric.key === 'qualifiedFriendCustomers' ? item.qualifiedFriendCustomers
                : item.addedFriendCustomers + item.trustedFriendCustomers + item.qualifiedFriendCustomers),
      })
    }
  }

  for (const metric of rateMetrics) {
    const threshold = crossedThreshold(previous.headline[metric.key], current.headline[metric.key], [.25, .5, .75])
    if (!threshold) continue
    events.push({
      id: eventId('conversionStage', `${metric.key}:${threshold}`),
      type: 'conversionStage',
      priority: 60,
      title: '转化贯通',
      eyebrow: 'FUNNEL ENERGY UNLOCKED',
      value: `${Math.round(threshold * 100)}%`,
      subtitle: `${metric.label}正式跨越 ${Math.round(threshold * 100)}% 阶段`,
      accent: metric.accent,
      metric: metric.label,
      threshold,
    })
  }

  const previousTaskIds = new Set(previous.recentTaskPublications.map((task) => task.taskId))
  for (const task of current.recentTaskPublications) {
    if (previousTaskIds.has(task.taskId) || Date.parse(task.createdAt) <= previousGeneratedAt) continue
    const audience = task.distributionType === 'SELF_CLAIM'
      ? '面向全员开放领取'
      : `已覆盖 ${task.assignmentCount} 名成员`
    events.push({
      id: eventId('taskPublished', task.taskId),
      type: 'taskPublished',
      priority: 92,
      title: '新任务发布',
      eyebrow: 'NEW MISSION DEPLOYED',
      value: 'MISSION',
      subtitle: `“${task.title}”已由 ${task.publisherName} 发布 · ${audience}`,
      accent: 'gold',
      taskTitle: task.title,
      publisherName: task.publisherName,
      dueAt: task.dueAt,
      assignmentCount: task.assignmentCount,
      distributionType: task.distributionType,
    })
  }

  const taskThreshold = crossedThreshold(
    previous.taskSummary.completionRate,
    current.taskSummary.completionRate,
    [.25, .5, .75, 1],
  )
  if (taskThreshold) {
    events.push({
      id: eventId('taskMilestone', `overall:${taskThreshold}`),
      type: 'taskMilestone',
      priority: taskThreshold === 1 ? 90 : 65,
      title: taskThreshold === 1 ? '任务攻顶' : '任务进阶',
      eyebrow: 'MISSION PROGRESS',
      value: `${Math.round(taskThreshold * 100)}%`,
      subtitle: taskThreshold === 1 ? '组织任务整体达成，目标圆满完成' : '组织任务完成率跨越新阶段',
      accent: 'green',
      threshold: taskThreshold,
    })
  }
  for (const task of current.tasks) {
    const oldTask = previous.tasks.find((item) => item.taskId === task.taskId)
    if (!oldTask) continue
    const threshold = crossedThreshold(oldTask.completionRate, task.completionRate, [.25, .5, .75, 1])
    if (!threshold) continue
    events.push({
      id: eventId('taskMilestone', `${task.taskId}:${threshold}`),
      type: 'taskMilestone',
      priority: threshold === 1 ? 88 : 62,
      title: threshold === 1 ? '任务攻顶' : '任务进阶',
      eyebrow: 'MISSION ACCOMPLISHED',
      value: `${Math.round(threshold * 100)}%`,
      subtitle: `“${task.title}”完成率跨越新阶段`,
      accent: 'green',
      threshold,
      taskTitle: task.title,
    })
  }

  for (const metric of rankMetrics) {
    const before = sortedRanking(previous, metric.key)
    const after = sortedRanking(current, metric.key)
    const previousLeader = before[0]?.displayName
    const currentLeader = after[0]?.displayName
    const previousTopThree = new Set(before.slice(0, 3).map((item) => item.membershipId))
    const entrant = after.slice(0, 3).find((item) => !previousTopThree.has(item.membershipId))
    if (!currentLeader || (currentLeader === previousLeader && !entrant)) continue
    events.push({
      id: eventId('rankingChange', `${metric.key}:${currentLeader}:${entrant?.membershipId ?? ''}`),
      type: 'rankingChange',
      priority: currentLeader !== previousLeader ? 80 : 70,
      title: currentLeader !== previousLeader ? '榜首易主' : '冲入前三',
      eyebrow: 'RANKING OVERTAKE',
      value: currentLeader !== previousLeader ? currentLeader : entrant?.displayName ?? currentLeader,
      subtitle: `${metric.label}发生关键换位`,
      accent: metric.accent,
      metric: metric.label,
      previousLeader,
      currentLeader,
      names: after.slice(0, 3).map((item) => item.displayName),
    })
  }

  const growingMembers = current.rankings.filter((member) => {
    const old = previous.rankings.find((item) => item.membershipId === member.membershipId)
    if (!old) return false
    return member.totalCustomers + member.addedFriendCustomers + member.trustedFriendCustomers
      + member.qualifiedFriendCustomers + member.completedTasks
      > old.totalCustomers + old.addedFriendCustomers + old.trustedFriendCustomers
      + old.qualifiedFriendCustomers + old.completedTasks
  })
  if (growingMembers.length >= 3) {
    events.push({
      id: eventId('teamMomentum', `${current.generatedAt}:${growingMembers.map((item) => item.membershipId).join(',')}`),
      type: 'teamMomentum',
      priority: 40,
      title: '团队共振',
      eyebrow: 'COLLECTIVE MOMENTUM',
      value: `${growingMembers.length} 人`,
      subtitle: '本轮刷新，多名成员同时贡献正向增长',
      accent: 'cyan',
      memberCount: growingMembers.length,
      names: growingMembers.slice(0, 6).map((item) => item.displayName),
    })
  }

  events.push(...trendEvents(previous, current))
  return events.sort((left, right) => right.priority - left.priority)
}

export function wakeUpEvent(scopeName: string): LeadershipAnimationEvent {
  return {
    id: eventId('wakeUp', String(Date.now())),
    type: 'wakeUp',
    priority: 110,
    title: '经营脉搏已恢复',
    eyebrow: 'SYSTEM AWAKENING',
    value: 'LIVE',
    subtitle: `${scopeName}检测到新的业务数据，正在同步最新战报`,
    accent: 'cyan',
  }
}

export function previewLeadershipEvent(type: LeadershipEventType): LeadershipAnimationEvent {
  const samples: Record<LeadershipEventType, LeadershipAnimationEvent> = {
    zeroBreakthrough: {
      id: `preview:${type}:${Date.now()}`, type: 'zeroBreakthrough', priority: 100, title: '破零时刻',
      eyebrow: 'ZERO BREAKTHROUGH', value: '1', subtitle: '首个有效转化已经点亮',
      accent: 'cyan', delta: 1, metric: '筛选确认',
    },
    customerAdded: {
      id: `preview:${type}:${Date.now()}`, type: 'customerAdded', priority: 96, title: '新客户接入',
      eyebrow: 'NEW CUSTOMER CONNECTED', value: '+3', subtitle: '3 位新客户已同步进入经营体系',
      accent: 'cyan', customerCount: 3, names: ['陈同学', '林同学', '周同学'],
      customers: [
        { name: '陈同学', detail: '王顾问 · 营销一组 · 初次接触' },
        { name: '林同学', detail: '李顾问 · 营销二组 · 待核实' },
        { name: '周同学', detail: '赵顾问 · 营销一组 · 已沟通' },
      ],
    },
    customerUpdated: {
      id: `preview:${type}:${Date.now()}`, type: 'customerUpdated', priority: 94, title: '客户档案更新',
      eyebrow: 'CUSTOMER PROFILE SYNCHRONIZED', value: '4', subtitle: '4 份客户档案已完成实时同步',
      accent: 'blue', customerCount: 4, names: ['陈同学', '林同学', '周同学', '徐同学'],
      customers: [
        { name: '陈同学', detail: '王顾问 · 高意向 · 高意向' },
        { name: '林同学', detail: '李顾问 · 跟进中 · 中意向' },
        { name: '周同学', detail: '赵顾问 · 已沟通 · 中意向' },
        { name: '徐同学', detail: '王顾问 · 待决定 · 高意向' },
      ],
    },
    milestone: {
      id: `preview:${type}:${Date.now()}`, type: 'milestone', priority: 55, title: '数字跃迁',
      eyebrow: 'PERFORMANCE MILESTONE', value: '100', subtitle: '客户总数跨越百人阶段节点',
      accent: 'blue', delta: 12, metric: '客户总数', previousValue: 88, currentValue: 100,
      points: [42, 48, 55, 63, 72, 86, 100],
    },
    trendSurge: {
      id: `preview:${type}:${Date.now()}`, type: 'trendSurge', priority: 45, title: '趋势升空',
      eyebrow: 'TREND ACCELERATION', value: '+56%', subtitle: '今日新增显著高于近 7 日均值',
      accent: 'purple', metric: '添加好友', growthRate: .56, points: [4, 6, 5, 8, 7, 10, 16],
    },
    conversionStage: {
      id: `preview:${type}:${Date.now()}`, type: 'conversionStage', priority: 60, title: '转化贯通',
      eyebrow: 'FUNNEL ENERGY UNLOCKED', value: '50%', subtitle: '信任转化率正式跨越 50% 阶段',
      accent: 'gold', metric: '信任转化率', threshold: .5,
    },
    taskPublished: {
      id: `preview:${type}:${Date.now()}`, type: 'taskPublished', priority: 92, title: '新任务发布',
      eyebrow: 'NEW MISSION DEPLOYED', value: 'MISSION',
      subtitle: '“本周客户增长冲刺”已由 运营中心 发布 · 已覆盖 18 名成员',
      accent: 'gold', taskTitle: '本周客户增长冲刺', publisherName: '运营中心',
      dueAt: new Date(Date.now() + 3 * 86_400_000).toISOString(), assignmentCount: 18,
      distributionType: 'MANDATORY',
    },
    taskMilestone: {
      id: `preview:${type}:${Date.now()}`, type: 'taskMilestone', priority: 90, title: '任务攻顶',
      eyebrow: 'MISSION ACCOMPLISHED', value: '100%', subtitle: '组织重点任务圆满完成',
      accent: 'green', threshold: 1, taskTitle: '本周客户增长冲刺',
    },
    rankingChange: {
      id: `preview:${type}:${Date.now()}`, type: 'rankingChange', priority: 80, title: '榜首易主',
      eyebrow: 'RANKING OVERTAKE', value: '英格之星', subtitle: '总添加客户榜发生关键换位',
      accent: 'cyan', metric: '总添加客户榜', previousLeader: '昨日冠军', currentLeader: '英格之星',
      names: ['英格之星', '增长先锋', '客户专家'],
    },
    teamMomentum: {
      id: `preview:${type}:${Date.now()}`, type: 'teamMomentum', priority: 40, title: '团队共振',
      eyebrow: 'COLLECTIVE MOMENTUM', value: '5 人', subtitle: '多名成员同时贡献正向增长',
      accent: 'cyan', memberCount: 5, names: ['英格之星', '增长先锋', '客户专家', '转化能手', '行动派'],
    },
  }
  return samples[type]
}
