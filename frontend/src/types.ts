export interface Me {
  userId: string
  membershipId: string
  organizationId: string
  username: string
  displayName: string
  roles: string[]
  passwordChangeRequired: boolean
}

export interface Tag {
  id: string
  name: string
  category: string
  color: string
  system: boolean
}

export interface DictionaryItem {
  id: string
  type: string
  code: string
  label: string
  sortOrder: number
}

export interface Customer {
  id: string
  ownerMembershipId: string
  ownerDisplayName: string
  createdByMembershipId: string
  creatorDisplayName: string
  teamId?: string
  visibility: 'PRIVATE' | 'TEAM' | 'ORGANIZATION'
  fullName: string
  nickname?: string
  phone?: string
  wechatId?: string
  qqId?: string
  school?: string
  major?: string
  grade?: string
  city?: string
  highSchool?: string
  sourceLocation?: string
  identityType: string
  focusDirections: string[]
  demandSummary?: string
  intentionLevel: string
  urgencyLevel: string
  verificationStatus: string
  sourceDictionaryId?: string
  sourceChannel?: string
  currentStatus: string
  ownerNotes?: string
  lastContactAt?: string
  nextFollowUpAt?: string
  friendAddedAt?: string
  friendTrustedAt?: string
  friendQualifiedAt?: string
  archivedAt?: string
  leadReviewStatus: 'PENDING' | 'VALID' | 'INVALID'
  leadReviewReason?: string
  leadReviewedByMembershipId?: string
  leadReviewerDisplayName?: string
  leadReviewedAt?: string
  createdAt: string
  updatedAt: string
  version: number
  tags: Tag[]
  dealState: 'NONE' | 'PENDING' | 'WON'
  canRequestDeal: boolean
  dealRequestedAt?: string
}

export interface Interaction {
  id: string
  customerId: string
  contactedAt: string
  channel: string
  summary: string
  details?: string
  outcome?: string
  verificationTodo?: string
  nextFollowUpAt?: string
  createdByMembershipId?: string
  createdByDisplayName?: string
  createdAt: string
  interactionType?: 'LEGACY' | 'CUSTOMER_REPLY' | 'OUTBOUND_NO_REPLY' | 'AGREED_LATER' | 'INTERNAL_NOTE'
  deadlineEffect?: string
  deadlineEffectReason?: string
}

export interface Reminder {
  id: string
  customerId: string
  remindAt: string
  title: string
  status: string
  deliveryStatus: string
  reminderType?: string
  sourceAnalysisVersionId?: string
  overdue: boolean
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ApiErrorBody {
  code: string
  message: string
  fieldErrors?: { field: string; message: string }[]
  traceId?: string
}

export interface AgentAuthorization {
  id: string
  clientId: string
  clientName: string
  displayName: string
  scopes: string[]
  status: 'ACTIVE' | 'REVOKED'
  createdAt: string
  expiresAt: string
  lastUsedAt?: string
  revokedAt?: string
}

export interface PersonalAgentToken {
  grantId: string
  clientId: string
  accessToken: string
  scopes: string[]
  resource: string
  expiresAt: string
}

export interface OAuthConsentRequest {
  requestId: string
  clientId: string
  clientName: string
  redirectUri: string
  requestedScopes: string[]
  grantableScopes: string[]
  expiresAt: string
}

export interface AgentApproval {
  id: string
  clientId: string
  toolName: string
  summary: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CONSUMED'
  createdAt: string
  expiresAt: string
  decidedAt?: string
  consumedAt?: string
}

export interface FollowUpRecruitment {
  id: string
  customerId: string
  fullName: string
  phone?: string
  wechatId?: string
  qqId?: string
  school?: string
  major?: string
  grade?: string
  highSchool?: string
  sourceLocation?: string
  demandSummary?: string
  ownerDisplayName: string
  requiredCount: number
  claimedCount: number
  openedAt: string
  recentTimeline: { contactedAt: string; channel: string; summary: string; details?: string; outcome?: string }[]
}

export interface ConsultationSettings {
  businessStart: string
  businessEnd: string
  minimumLeadMinutes: number
  allowedDurations: number[]
  dailyCapacity: number
  feedbackDueHours: number
  version: number
}

export interface ConsultantCandidate {
  membershipId: string
  displayName: string
  roles: string[]
  teams: string[]
}

export interface ConsultationFailureReason {
  id: string
  code: string
  label: string
  active: boolean
  sortOrder: number
}

export interface Consultation {
  id: string
  customerId: string
  customerName: string
  customerSnapshot?: Record<string, unknown>
  createdByMembershipId: string
  createdByName: string
  consultantMembershipId: string
  consultantName: string
  sourceInteractionIds: string[]
  consultationReason: string
  customerQuotes: string
  goalsMotivation: string
  actionsTaken: string
  mainConcerns: string
  decisionParticipants?: string
  decisionParticipantConsent: boolean
  productFit: string
  suggestedFocus: string
  explicitTaboos: string
  expectedResult: string
  scheduledStart: string
  scheduledEnd: string
  status: string
  rejectionReason?: string
  acceptedAt?: string
  actualEndedAt?: string
  feedbackDueAt?: string
  resultSummary?: string
  continueFollowUp?: boolean
  nextStep?: string
  nextOwnerMembershipId?: string
  nextFollowUpAt?: string
  failureReasonId?: string
  failureReasonOther?: string
  resultInteractionId?: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface QqConnection {
  id: string
  status: 'DISCONNECTED' | 'PAIRING' | 'CONNECTED' | 'ERROR' | 'REVOKED'
  maskedQq?: string
  qrCodeDataUrl?: string
  lastHeartbeatAt?: string
  createdAt: string
}

export interface QqFriend {
  uid: string
  uin: string
  nick: string
  remark?: string
}

export interface QqConversationDraft {
  summary: string
  customerQuotes: string[]
  goalsMotivation: string
  actionsTaken: string
  mainConcerns: string
  decisionParticipants: string
  productFit: string
  suggestedFocus: string
  explicitTaboos: string
  outcome: string
  nextStep: string
  nextFollowUpAt?: string
  uncertainties: string[]
  warnings?: string[]
}

export interface EnrichmentRow {
  rowNumber: number
  fullName: string
  major: string
  grade: string
  highSchool: string
  sourceLocation?: string
  mappingStatus: string
  customerId?: string
  status: string
  message?: string
  appliedHighSchool: boolean
  appliedSourceLocation: boolean
}

export interface EnrichmentJob {
  id: string
  filename: string
  fileSha256: string
  status: string
  totalRows: number
  readyRows: number
  updatedRows: number
  skippedRows: number
  conflictRows: number
  expiresAt: string
  createdAt: string
  completedAt?: string
}

export interface EnrichmentPreview { job: EnrichmentJob; rows: EnrichmentRow[] }

export interface ProductRelease {
  id: string
  version: string
  title: string
  summary: string
  sections: Record<string, string[]>
  releasedAt: string
  current: boolean
  acknowledged: boolean
}
