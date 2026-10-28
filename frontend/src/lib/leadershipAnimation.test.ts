import { describe, expect, it } from 'vitest'
import {
  defaultLeadershipScreenSettings,
  detectLeadershipEvents,
  type LeadershipDashboardData,
} from './leadershipAnimation'

function dashboard(): LeadershipDashboardData {
  return {
    generatedAt: '2026-07-26T02:00:00Z',
    lastBusinessActivityAt: '2026-07-26T01:59:00Z',
    startDate: '2026-07-20',
    endDate: '2026-07-26',
    days: 7,
    administrator: true,
    teams: [],
    members: [],
    headline: {
      totalCustomers: 9,
      addedFriendCustomers: 0,
      trustedFriendCustomers: 9,
      qualifiedFriendCustomers: 4,
      todayNewCustomers: 1,
      todayQualifiedCustomers: 1,
      addedRate: .24,
      trustRate: .49,
      qualifiedRate: .74,
    },
    recentCustomerActivities: [{
      customerId: 'customer-1', fullName: '陈同学', ownerName: '甲',
      teamName: '一组', currentStatus: '跟进中', intentionLevel: '中',
      createdAt: '2026-07-25T01:00:00Z', updatedAt: '2026-07-25T01:30:00Z',
    }],
    rankings: [
      { membershipId: 'a', displayName: '甲', teamNames: '一组', totalCustomers: 8, addedFriendCustomers: 5, trustedFriendCustomers: 3, qualifiedFriendCustomers: 2, qualityACustomers: 2, todayCustomers: 1, assignedTasks: 4, completedTasks: 1, taskCompletionRate: .25 },
      { membershipId: 'b', displayName: '乙', teamNames: '一组', totalCustomers: 7, addedFriendCustomers: 4, trustedFriendCustomers: 2, qualifiedFriendCustomers: 1, qualityACustomers: 1, todayCustomers: 0, assignedTasks: 4, completedTasks: 1, taskCompletionRate: .25 },
      { membershipId: 'c', displayName: '丙', teamNames: '二组', totalCustomers: 6, addedFriendCustomers: 3, trustedFriendCustomers: 1, qualifiedFriendCustomers: 0, qualityACustomers: 0, todayCustomers: 0, assignedTasks: 4, completedTasks: 1, taskCompletionRate: .25 },
    ],
    dailyConversions: [
      { date: '2026-07-20', addedFriendCustomers: 2, trustedFriendCustomers: 1, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 1 },
      { date: '2026-07-21', addedFriendCustomers: 2, trustedFriendCustomers: 2, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 2 },
      { date: '2026-07-22', addedFriendCustomers: 3, trustedFriendCustomers: 1, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 3 },
      { date: '2026-07-23', addedFriendCustomers: 2, trustedFriendCustomers: 2, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 4 },
      { date: '2026-07-24', addedFriendCustomers: 3, trustedFriendCustomers: 2, qualifiedFriendCustomers: 1, cumulativeQualifiedCustomers: 5 },
      { date: '2026-07-25', addedFriendCustomers: 2, trustedFriendCustomers: 1, qualifiedFriendCustomers: 2, cumulativeQualifiedCustomers: 7 },
      { date: '2026-07-26', addedFriendCustomers: 0, trustedFriendCustomers: 0, qualifiedFriendCustomers: 0, cumulativeQualifiedCustomers: 7 },
    ],
    customerQuality: {
      classifiedCustomers: 6,
      unclassifiedCustomers: 3,
      levels: [
        { code: 'A', label: 'A类用户', description: '高质量', color: '#16A34A', customerCount: 2, share: .2222 },
        { code: 'B', label: 'B类用户', description: '一般质量', color: '#F59E0B', customerCount: 3, share: .3333 },
        { code: 'C', label: 'C类用户', description: '低需求', color: '#64748B', customerCount: 1, share: .1111 },
      ],
    },
    taskSummary: { assignmentCount: 12, pendingCount: 8, inProgressCount: 1, submittedCount: 0, completedCount: 3, returnedCount: 0, overdueCount: 0, completionRate: .24 },
    tasks: [{ taskId: 'task-1', title: '阶段冲刺', dueAt: '2026-07-28T10:00:00Z', status: 'PUBLISHED', assignmentCount: 4, completedCount: 1, submittedCount: 0, completionRate: .24, overdue: false }],
    recentTaskPublications: [{
      taskId: 'task-1', title: '阶段冲刺', distributionType: 'ASSIGNED',
      startAt: '2026-07-25T02:00:00Z', dueAt: '2026-07-28T10:00:00Z',
      createdAt: '2026-07-25T01:00:00Z', publisherName: '运营中心', assignmentCount: 4,
    }],
    sources: [],
    displayMessages: [],
    featuredGrade: '2026',
    aClassStale: { enabled: true, thresholdHours: 72, customerCount: 1, unassignedCount: 0, owners: [{ membershipId: 'a', displayName: '甲', customerCount: 1 }], calculatedAt: '2026-07-26T02:00:00Z' },
    schoolComparison: { featuredGrade: '2026', jiangsuUniversity: 5, jiangsuUniversityOfScienceAndTechnology: 4, difference: 1, leader: 'JIANGSU_UNIVERSITY' },
    tickerItems: [],
    screenSettings: { ...defaultLeadershipScreenSettings },
  }
}

describe('leadership animation event detection', () => {
  it('prioritizes a zero breakthrough over crossed numeric milestones', () => {
    const previous = dashboard()
    const current = structuredClone(previous)
    current.generatedAt = '2026-07-26T02:01:00Z'
    current.headline.totalCustomers = 10
    current.headline.addedFriendCustomers = 2

    const events = detectLeadershipEvents(previous, current)

    expect(events[0]).toMatchObject({ type: 'zeroBreakthrough', metric: '添加好友' })
    expect(events).toContainEqual(expect.objectContaining({ type: 'milestone', metric: '客户总数', currentValue: 10 }))
  })

  it('detects conversion, task, ranking and collective momentum changes', () => {
    const previous = dashboard()
    const current = structuredClone(previous)
    current.generatedAt = '2026-07-26T02:01:00Z'
    current.headline.addedRate = .25
    current.headline.trustRate = .5
    current.headline.qualifiedRate = .75
    current.taskSummary.completionRate = .5
    current.tasks[0].completionRate = 1
    current.tasks[0].completedCount = 4
    current.rankings[0].totalCustomers = 9
    current.rankings[1].totalCustomers = 12
    current.rankings[2].totalCustomers = 8

    const events = detectLeadershipEvents(previous, current)

    expect(events.filter((event) => event.type === 'conversionStage')).toHaveLength(3)
    expect(events).toContainEqual(expect.objectContaining({ type: 'taskMilestone', threshold: 1, taskTitle: '阶段冲刺' }))
    expect(events).toContainEqual(expect.objectContaining({ type: 'rankingChange', metric: '总添加客户榜', currentLeader: '乙' }))
    expect(events).toContainEqual(expect.objectContaining({ type: 'teamMomentum', memberCount: 3 }))
  })

  it('detects a daily surge only after the minimum volume and growth threshold', () => {
    const previous = dashboard()
    const current = structuredClone(previous)
    current.generatedAt = '2026-07-26T02:01:00Z'
    current.dailyConversions.at(-1)!.addedFriendCustomers = 6

    const events = detectLeadershipEvents(previous, current)

    expect(events).toContainEqual(expect.objectContaining({
      type: 'trendSurge',
      metric: '添加好友',
      value: expect.stringMatching(/^\+\d+%$/),
    }))
  })

  it('detects a newly published task without replaying edits to an existing task', () => {
    const previous = dashboard()
    const current = structuredClone(previous)
    current.generatedAt = '2026-07-26T02:01:00Z'
    current.recentTaskPublications.unshift({
      taskId: 'task-2',
      title: '月底重点客户回访',
      distributionType: 'MANDATORY',
      startAt: '2026-07-26T02:00:30Z',
      dueAt: '2026-07-30T10:00:00Z',
      createdAt: '2026-07-26T02:00:30Z',
      publisherName: '王主管',
      assignmentCount: 18,
    })
    current.recentTaskPublications[1].title = '阶段冲刺（已更新）'

    const events = detectLeadershipEvents(previous, current)

    expect(events).toContainEqual(expect.objectContaining({
      id: 'taskPublished:task-2',
      type: 'taskPublished',
      taskTitle: '月底重点客户回访',
      publisherName: '王主管',
      assignmentCount: 18,
    }))
    expect(events.filter((event) => event.type === 'taskPublished')).toHaveLength(1)
  })

  it('batches every new customer and existing customer update into dedicated animations', () => {
    const previous = dashboard()
    const current = structuredClone(previous)
    current.generatedAt = '2026-07-26T02:01:00Z'
    current.headline.totalCustomers += 2
    current.recentCustomerActivities = [
      {
        customerId: 'customer-2', fullName: '林同学', ownerName: '乙',
        teamName: '一组', currentStatus: '初次接触', intentionLevel: '中',
        createdAt: '2026-07-26T02:00:20Z', updatedAt: '2026-07-26T02:00:20Z',
      },
      {
        customerId: 'customer-3', fullName: '周同学', ownerName: '丙',
        teamName: '二组', currentStatus: '待核实', intentionLevel: '高',
        createdAt: '2026-07-26T02:00:35Z', updatedAt: '2026-07-26T02:00:40Z',
      },
      {
        ...previous.recentCustomerActivities[0],
        currentStatus: '高意向',
        intentionLevel: '高',
        updatedAt: '2026-07-26T02:00:50Z',
      },
    ]

    const events = detectLeadershipEvents(previous, current)

    expect(events[0]).toMatchObject({
      type: 'customerAdded',
      customerCount: 2,
      names: ['林同学', '周同学'],
    })
    expect(events[1]).toMatchObject({
      type: 'customerUpdated',
      customerCount: 1,
      names: ['陈同学'],
    })
  })
})
