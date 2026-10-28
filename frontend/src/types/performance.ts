export interface EventTier {
  label: string
  minParticipants: number
  maxParticipants?: number
  points: number
}

export interface PerformanceRule {
  id: string
  versionNo: number
  effectiveFrom: string
  dailyBaseline: number
  calendarMode: 'ALL_DAYS' | 'WEEKDAYS' | 'CUSTOM'
  weekdays: number[]
  includedDates: string[]
  excludedDates: string[]
  submissionOpensAt: string
  submissionClosesAt: string
  shortfallMode: 'STATUS_ONLY' | 'DEDUCT_GAP'
  ordinaryPoints: number
  deepPoints: number
  ordinaryDailyCap?: number
  deepDailyCap?: number
  eventTiers: EventTier[]
  createdAt: string
}

export interface PerformanceEvidence {
  id: string
  ownerType: string
  ownerId: string
  fileName: string
  contentType: string
  fileSize: number
  createdAt: string
}

export interface PerformanceClaim {
  id: string
  membershipId: string
  memberDisplayName: string
  interactionId: string
  customerId: string
  customerName: string
  performanceDate: string
  requestedType: 'ORDINARY' | 'DEEP'
  approvedType?: 'ORDINARY' | 'DEEP'
  status: 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'
  reviewReason?: string
  submittedAt?: string
  reviewedAt?: string
  evidence: PerformanceEvidence[]
  version: number
}

export interface PerformanceActivity {
  id: string
  organizerMembershipId: string
  organizerDisplayName: string
  eventDate: string
  title: string
  goal: string
  expectedParticipants: number
  description: string
  actualParticipants?: number
  verifiedParticipants?: number
  tierLabel?: string
  totalPoints?: number
  status: 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'COMPLETION_SUBMITTED' | 'ALLOCATED'
  reviewReason?: string
  allocations: { membershipId: string; points: number }[]
  evidence: PerformanceEvidence[]
  createdAt: string
  version: number
}

export interface ScoreBreakdown {
  businessPoints: number
  logisticsPoints: number
  correctionPoints: number
  earnedPoints: number
  baseline: number
  shortfall: number
  deduction: number
  finalPoints: number
}

export interface PerformanceToday {
  date: string
  participant: boolean
  dayStatus: 'OPEN' | 'REVIEWING' | 'SETTLED' | 'NON_SCORING'
  submissionOpen: boolean
  opensAt: string
  closesAt: string
  rule: PerformanceRule
  score: ScoreBreakdown
  pendingClaims: number
  pendingActivities: number
  approvedDeepCount: number
  pendingDeepCount: number
  availableInteractions: {
    interactionId: string
    customerId: string
    customerName: string
    contactedAt: string
    channel: string
    summary: string
  }[]
  claims: PerformanceClaim[]
  activities: PerformanceActivity[]
  ledger: {
    id: string
    membershipId: string
    memberDisplayName: string
    performanceDate: string
    category: string
    sourceId: string
    points: number
    description: string
    createdAt: string
  }[]
}

export interface PerformanceLeaderboardRow {
  rank: number
  membershipId: string
  displayName: string
  teamId?: string
  teamName: string
  earnedPoints: number
  finalPoints: number
  scoredDays: number
  achievedDays: number
}

export interface PerformanceHistoryPoint {
  date: string
  earnedPoints: number
  finalPoints: number
  baseline?: number
  settled: boolean
}

export interface PerformanceParticipant {
  id: string
  membershipId: string
  displayName: string
  teamId?: string
  teamName: string
  startsOn: string
  endsOn?: string
  activeToday: boolean
  version: number
}

export interface PerformanceDay {
  date: string
  status: 'OPEN' | 'REVIEWING' | 'SETTLED'
  rule: PerformanceRule
  pendingClaims: number
  pendingActivities: number
  members: {
    membershipId: string
    displayName: string
    teamId?: string
    teamName: string
    score: ScoreBreakdown
    settled: boolean
  }[]
  version: number
}
