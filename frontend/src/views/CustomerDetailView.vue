<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusBadge from '../components/StatusBadge.vue'
import CustomerClassificationPanel from '../components/CustomerClassificationPanel.vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { formatCustomerGrade, isGradeTag } from '../lib/customerProfile'
import { customerQualityCode, customerQualityDescription, isCustomerQualityTag } from '../lib/customerQuality'
import { dateTime, localInput, toIso } from '../lib/format'
import { safeTeamDataReturnTo } from '../lib/teamDataNavigation'
import type { Customer, Interaction, Reminder, Tag } from '../types'

interface CollaborationMember { membershipId: string; displayName: string; teamNames: string }
interface CollaborationOverview {
  ownerMembershipId: string
  canManage: boolean
  canTransferOwnership: boolean
  canEditProfile: boolean
  canArchive: boolean
  canEditTimeline: boolean
  collaborators: CollaborationMember[]
  candidates: CollaborationMember[]
  transferCandidates: CollaborationMember[]
}
interface IntelligenceEvidence { sourceType: string; sourceKey: string; sourceAt?: string; excerpt: string }
interface IntelligenceVersion {
  id: string; versionNumber: number; triggerType: string; status: string
  result?: Record<string, unknown>; narrative?: string; evidence: IntelligenceEvidence[]
  provider?: string; model?: string; sourceWatermark?: string; createdAt: string
}
interface IntelligenceOverview {
  customerId: string; enabled: boolean; processing: boolean; processingStatus?: string
  latest?: IntelligenceVersion
}
interface DealParticipant { membershipId: string; displayName: string; role: 'PRIMARY' | 'ASSISTANT'; bonusCents: number }
interface DealSummary {
  requestId: string; customerId: string; status: 'PENDING' | 'APPROVED'; confirmationChannel?: string
  confirmedAt?: string; orderAmountCents?: number; totalBonusCents: number; participants: DealParticipant[]
}

const route = useRoute()
const router = useRouter()
const id = String(route.params.id)
const customer = ref<Customer>()
const interactions = ref<Interaction[]>([])
const tags = ref<Tag[]>([])
const collaboration = ref<CollaborationOverview>()
const intelligence = ref<IntelligenceOverview>()
const intelligenceBusy = ref(false)
const intelligenceReminderOpen = ref(false)
const intelligenceReminderBusy = ref(false)
const intelligenceReminderCreated = ref<Reminder>()
const dealBusy = ref(false)
const dealSummary = ref<DealSummary>()
const panelOpen = ref(false)
const sharingOpen = ref(false)
const transferOpen = ref(false)
const selectedCollaborators = ref<string[]>([])
const busy = ref(false)
const sharingBusy = ref(false)
const transferBusy = ref(false)
const transferCandidatesBusy = ref(false)
const error = ref('')
const mobileActionsOpen = ref(false)
const intelligenceSummaryExpanded = ref(false)
const expandedIntelligenceSections = ref<Record<string, boolean>>({})
const expandedTimelineItems = ref<Record<string, boolean>>({})
const transfer = reactive({ targetMembershipId: '', reason: '' })
const intelligenceReminder = reactive({
  title: '', remindAt: '', sourceAnalysisVersionId: '', sourceVersionNumber: 0,
})
const record = reactive({ contactedAt: localInput(), channel: '微信', interactionType: 'CUSTOMER_REPLY', summary: '', details: '', outcome: '', verificationTodo: '', nextFollowUpAt: '', currentStatus: '', intentionLevel: '', verificationStatus: '', tagIds: [] as string[], tagUpdateRequested: false })
const contact = computed(() => [customer.value?.phone, customer.value?.wechatId, customer.value?.qqId].filter(Boolean).join(' · '))
const regularTags = computed(() => tags.value.filter((tag) => !isCustomerQualityTag(tag)))
const customerQualityTag = computed(() => customer.value?.tags.find(isCustomerQualityTag))
const visibleCustomerTags = computed(() => customer.value?.tags
  .filter((tag) => !isGradeTag(tag) && !isCustomerQualityTag(tag)) ?? [])
const friendStage = computed(() => {
  if (customer.value?.friendQualifiedAt) return '已筛选'
  if (customer.value?.friendTrustedAt) return '已信任'
  if (customer.value?.friendAddedAt) return '已添加'
  return '待添加'
})
const teamDataReturnTo = computed(() => safeTeamDataReturnTo(route.query.returnTo))
const parentLabel = computed(() => teamDataReturnTo.value ? '返回团队数据' : '返回客户列表')
const editLocation = computed(() => ({
  path: `/customers/${id}/edit`,
  query: teamDataReturnTo.value ? { returnTo: teamDataReturnTo.value } : {},
}))
const selectedTransferTarget = computed(() => collaboration.value?.transferCandidates
  .find((member) => member.membershipId === transfer.targetMembershipId))
const intelligenceResult = computed(() => intelligence.value?.latest?.result ?? {})
const intelligenceFollowUpSuggestion = computed(() => {
  const direct = intelligenceResult.value.nextFollowUpSuggestion
  if (typeof direct === 'string' && direct.trim() && direct.trim() !== '未确认') return direct.trim()
  return intelligenceList('recommendedActions')[0] ?? '核实客户智能分析中的待确认事项'
})
const intelligenceSections = [
  { key: 'goals', title: '目标与动机' },
  { key: 'progress', title: '已有进展' },
  { key: 'concerns', title: '主要顾虑' },
  { key: 'risks', title: '转化风险' },
  { key: 'recommendedActions', title: '建议动作' },
  { key: 'unresolvedQuestions', title: '待核实事项' },
]

function intelligenceList(key: string) {
  const value = intelligenceResult.value[key]
  if (Array.isArray(value)) return value.map(String).filter(Boolean)
  if (typeof value === 'string' && value.trim()) return [value]
  return []
}

function displayedIntelligenceItems(key: string) {
  const items = intelligenceList(key)
  return expandedIntelligenceSections.value[key] ? items : items.slice(0, 3)
}

function timelineNeedsExpansion(item: Interaction) {
  return `${item.details ?? ''}${item.outcome ?? ''}`.length > 140
}

function interactionTypeLabel(value?: Interaction['interactionType']) {
  return ({
    CUSTOMER_REPLY: '客户已回复',
    OUTBOUND_NO_REPLY: '真实触达未回复',
    AGREED_LATER: '客户约定以后联系',
    INTERNAL_NOTE: '内部备注',
    LEGACY: '历史记录',
  } as const)[value ?? 'CUSTOMER_REPLY']
}

async function loadIntelligence() {
  try { intelligence.value = await api<IntelligenceOverview>(`/api/v1/customers/${id}/intelligence`) }
  catch { intelligence.value = undefined }
}

async function load() {
  const [loadedCustomer, loadedInteractions, loadedTags, loadedCollaboration] = await Promise.all([
    api<Customer>(`/api/v1/customers/${id}`), api<Interaction[]>(`/api/v1/customers/${id}/interactions`), api<Tag[]>('/api/v1/tags'),
    api<CollaborationOverview>(`/api/v1/customers/${id}/collaboration`),
  ])
  customer.value = loadedCustomer
  interactions.value = loadedInteractions
  tags.value = loadedTags.filter((tag) => !isGradeTag(tag))
  collaboration.value = loadedCollaboration
  selectedCollaborators.value = loadedCollaboration.collaborators.map((member) => member.membershipId)
  dealSummary.value = undefined
  if (loadedCustomer.dealState === 'WON' || loadedCustomer.dealState === 'PENDING') {
    try { dealSummary.value = await api<DealSummary>(`/api/v1/customers/${id}/deal-summary`) }
    catch { dealSummary.value = undefined }
  }
  await loadIntelligence()
}

function yuan(cents?: number) {
  if (cents == null) return '未补录'
  return `¥${(cents / 100).toFixed(2).replace(/\.00$/, '')}`
}

function openInteraction() {
  record.tagIds = customer.value?.tags
    .filter((tag) => !isCustomerQualityTag(tag) && tags.value.some((option) => option.id === tag.id))
    .map((tag) => tag.id) ?? []
  record.tagUpdateRequested = false
  error.value = ''
  panelOpen.value = true
}

async function reanalyze() {
  intelligenceBusy.value = true
  error.value = ''
  try {
    intelligence.value = await api<IntelligenceOverview>(`/api/v1/customers/${id}/intelligence/reanalyze`, { method: 'POST' })
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '无法发起客户智能分析'
  } finally { intelligenceBusy.value = false }
}

function openIntelligenceReminder() {
  const latest = intelligence.value?.latest
  if (!latest || !collaboration.value?.canEditTimeline || customer.value?.archivedAt) return
  const customerFollowUp = customer.value?.nextFollowUpAt ? new Date(customer.value.nextFollowUpAt) : undefined
  const defaultAt = customerFollowUp && !Number.isNaN(customerFollowUp.getTime()) && customerFollowUp.getTime() > Date.now()
    ? customerFollowUp
    : new Date(Date.now() + 86_400_000)
  intelligenceReminder.title = intelligenceFollowUpSuggestion.value.slice(0, 240)
  intelligenceReminder.remindAt = localInput(defaultAt.toISOString())
  intelligenceReminder.sourceAnalysisVersionId = latest.id
  intelligenceReminder.sourceVersionNumber = latest.versionNumber
  error.value = ''
  intelligenceReminderOpen.value = true
}

async function saveIntelligenceReminder() {
  if (!intelligenceReminder.sourceAnalysisVersionId) return
  intelligenceReminderBusy.value = true
  error.value = ''
  try {
    intelligenceReminderCreated.value = await api<Reminder>('/api/v1/reminders', {
      method: 'POST',
      body: jsonBody({
        customerId: id,
        remindAt: toIso(intelligenceReminder.remindAt),
        title: intelligenceReminder.title,
        sourceAnalysisVersionId: intelligenceReminder.sourceAnalysisVersionId,
      }),
    })
    intelligenceReminderOpen.value = false
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '跟进提醒创建失败'
  } finally { intelligenceReminderBusy.value = false }
}

function openSharing() {
  selectedCollaborators.value = collaboration.value?.collaborators.map((member) => member.membershipId) ?? []
  error.value = ''
  sharingOpen.value = true
}

async function openTransfer() {
  transfer.targetMembershipId = ''
  transfer.reason = ''
  error.value = ''
  transferOpen.value = true
  transferCandidatesBusy.value = true
  try {
    collaboration.value = await api<CollaborationOverview>(`/api/v1/customers/${id}/collaboration`)
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '接收人列表刷新失败，请稍后重试'
  } finally {
    transferCandidatesBusy.value = false
  }
}

async function returnToParent() {
  const target = teamDataReturnTo.value ?? '/customers'
  if (router.options.history.state.back === target) {
    router.back()
    return
  }
  await router.replace(target)
}

async function saveSharing() {
  sharingBusy.value = true
  error.value = ''
  try {
    collaboration.value = await api<CollaborationOverview>(`/api/v1/customers/${id}/collaborators`, {
      method: 'PUT', body: jsonBody({ membershipIds: selectedCollaborators.value }),
    })
    selectedCollaborators.value = collaboration.value.collaborators.map((member) => member.membershipId)
    sharingOpen.value = false
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '客户共享保存失败'
  } finally { sharingBusy.value = false }
}

async function saveInteraction() {
  busy.value = true; error.value = ''
  try {
    await api(`/api/v1/customers/${id}/interactions`, { method: 'POST', body: jsonBody({
      ...record,
      tagIds: record.tagUpdateRequested ? record.tagIds : undefined,
      contactedAt: toIso(record.contactedAt),
      nextFollowUpAt: toIso(record.nextFollowUpAt),
    }) })
    panelOpen.value = false
    Object.assign(record, { contactedAt: localInput(), interactionType: 'CUSTOMER_REPLY', summary: '', details: '', outcome: '', verificationTodo: '', nextFollowUpAt: '', currentStatus: '', intentionLevel: '', verificationStatus: '', tagIds: [], tagUpdateRequested: false })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '沟通记录保存失败' }
  finally { busy.value = false }
}

async function transferCustomer() {
  if (!customer.value || !selectedTransferTarget.value) {
    error.value = '请选择客户接收人'
    return
  }
  const target = selectedTransferTarget.value
  if (!window.confirm(`确认把“${customer.value.fullName}”从“${customer.value.ownerDisplayName}”转接给“${target.displayName}”？待办提醒将同时移交。`)) return
  transferBusy.value = true
  error.value = ''
  try {
    await api<Customer>(`/api/v1/customers/${id}/transfer`, {
      method: 'POST',
      body: jsonBody({
        targetMembershipId: target.membershipId,
        expectedVersion: customer.value.version,
        reason: transfer.reason || undefined,
      }),
    })
    transferOpen.value = false
    await returnToParent()
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '客户转接失败'
  } finally { transferBusy.value = false }
}

async function archiveCustomer() {
  if (!window.confirm('确认归档该客户？所有未完成提醒将同时取消。')) return
  await api(`/api/v1/customers/${id}/archive`, { method: 'POST' })
  await returnToParent()
}

async function requestDeal() {
  if (!customer.value?.canRequestDeal || dealBusy.value) return
  if (!window.confirm(`确认提交“${customer.value.fullName}”的成单申请？管理员审核后将标记为已成单。`)) return
  dealBusy.value = true
  error.value = ''
  try {
    await api(`/api/v1/customers/${id}/deal-requests`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    await load()
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '成单申请提交失败'
  } finally { dealBusy.value = false }
}

onMounted(load)
</script>

<template>
  <div v-if="customer" class="page">
    <header class="profile-header panel">
      <div class="profile-identity"><span class="avatar large">{{ customer.fullName.slice(0, 1) }}</span><div><div class="title-line"><h1>{{ customer.fullName }}</h1><span v-if="customerQualityTag" :class="['customer-quality-badge', `quality-${customerQualityCode(customerQualityTag).toLowerCase()}`]"><b>{{ customerQualityCode(customerQualityTag) }}</b>{{ customerQualityTag.name }}</span><StatusBadge v-else value="未评级" tone="gray" /><StatusBadge :value="customer.intentionLevel + '意向'" :tone="customer.intentionLevel === '高' ? 'green' : 'blue'" /><StatusBadge :value="customer.currentStatus" /><StatusBadge :value="friendStage" :tone="customer.friendQualifiedAt ? 'green' : customer.friendAddedAt ? 'blue' : 'gray'" /></div><p>{{ contact || '无联系方式' }}</p><p class="muted">{{ customer.school || '学校未补充' }} · {{ customer.major || '专业未补充' }} · {{ formatCustomerGrade(customer.grade) || '年级未补充' }} · {{ customer.focusDirections.join(' / ') }}</p></div></div>
      <div class="customer-detail-actions">
        <button v-if="customer.dealState === 'NONE' && customer.canRequestDeal" class="deal-button" :disabled="dealBusy" data-testid="request-deal" @click="requestDeal">{{ dealBusy ? '提交中…' : '成单' }}</button>
        <span v-else-if="customer.dealState === 'PENDING'" class="deal-state pending">成单审核中</span>
        <span v-else-if="customer.dealState === 'WON'" class="deal-state won">已成单</span>
        <button v-if="collaboration?.canEditTimeline && !customer.archivedAt" class="primary-button customer-primary-action" @click="openInteraction">＋ 记录沟通</button>
        <button type="button" class="ghost-button mobile-more-action" :aria-expanded="mobileActionsOpen" @click="mobileActionsOpen = !mobileActionsOpen">{{ mobileActionsOpen ? '收起操作' : '更多操作' }}</button>
        <div :class="['header-actions customer-secondary-actions', { open: mobileActionsOpen }]">
          <button class="ghost-button" data-testid="customer-parent-return" @click="returnToParent">{{ parentLabel }}</button><button v-if="collaboration?.canTransferOwnership && !customer.archivedAt" class="ghost-button" data-testid="transfer-customer" @click="openTransfer">转接客户</button><button v-if="collaboration?.canManage && !customer.archivedAt" class="ghost-button" data-testid="share-customer" @click="openSharing">共享跟进</button><button v-if="collaboration?.canEditTimeline && !customer.archivedAt" class="ghost-button" @click="router.push({ path: '/consultations', query: { customerId: id } })">发起咨询交接</button><button v-if="collaboration?.canEditTimeline && !customer.archivedAt" class="ghost-button" @click="router.push({ path: '/qq-chat', query: { customerId: id } })">导入 QQ 对话</button><button v-if="collaboration?.canEditProfile && !customer.archivedAt" class="ghost-button" data-testid="edit-customer" @click="router.push(editLocation)">编辑画像</button><a :href="`/api/v1/exports/customers/${id}/timeline?format=xlsx`" class="ghost-button">导出时间线</a>
        </div>
      </div>
    </header>
    <section v-if="dealSummary?.status === 'APPROVED'" class="panel deal-summary-panel">
      <div><span>本次成单金额</span><strong>{{ yuan(dealSummary.orderAmountCents) }}</strong><small>{{ dealSummary.confirmedAt ? dateTime(dealSummary.confirmedAt) : '已确认' }}</small></div>
      <div><span>主成单人</span><strong>{{ dealSummary.participants.find((item) => item.role === 'PRIMARY')?.displayName || '待补录' }}</strong><small>客户负责人保持原归属</small></div>
      <div><span>协助人</span><strong>{{ dealSummary.participants.filter((item) => item.role === 'ASSISTANT').map((item) => item.displayName).join('、') || '无' }}</strong><small>奖金合计 {{ yuan(dealSummary.totalBonusCents) }}</small></div>
    </section>
    <section v-if="intelligence?.enabled" class="panel customer-intelligence-panel" data-testid="customer-intelligence">
      <header class="panel-title">
        <div><p class="eyebrow">CUSTOMER INTELLIGENCE</p><h2>AI 客户洞察</h2><p class="muted">仅根据当前画像和已记录时间线分析，不会自动改写正式档案。</p></div>
        <div class="header-actions">
          <span v-if="intelligence.processing" class="intelligence-processing"><i></i>分析队列中</span>
          <button v-if="intelligence.latest && collaboration?.canEditTimeline && !customer.archivedAt" class="primary-button" type="button" data-testid="create-intelligence-reminder" @click="openIntelligenceReminder">生成跟进提醒</button>
          <button v-if="collaboration?.canEditTimeline && !customer.archivedAt" class="ghost-button" type="button" data-testid="reanalyze-customer" :disabled="intelligenceBusy || intelligence.processing" @click="reanalyze">{{ intelligenceBusy ? '正在发起…' : '重新分析' }}</button>
        </div>
      </header>
      <div v-if="intelligenceReminderCreated" class="intelligence-reminder-success" data-testid="intelligence-reminder-created">
        <strong>跟进提醒已创建</strong><span>{{ intelligenceReminderCreated.title }} · {{ dateTime(intelligenceReminderCreated.remindAt) }}</span>
      </div>
      <template v-if="intelligence.latest">
        <div class="intelligence-summary">
          <span>EXECUTIVE SUMMARY</span><p :class="{ expanded: intelligenceSummaryExpanded }">{{ intelligence.latest.narrative }}</p>
          <button v-if="(intelligence.latest.narrative?.length ?? 0) > 180" type="button" class="content-toggle" :aria-expanded="intelligenceSummaryExpanded" @click="intelligenceSummaryExpanded = !intelligenceSummaryExpanded">{{ intelligenceSummaryExpanded ? '收起摘要' : '展开完整摘要' }}</button>
          <small>版本 {{ intelligence.latest.versionNumber }} · {{ dateTime(intelligence.latest.createdAt) }} · {{ intelligence.latest.model }}</small>
        </div>
        <div class="intelligence-grid">
          <article v-for="section in intelligenceSections" :key="section.key">
            <h3>{{ section.title }}</h3>
            <ul v-if="intelligenceList(section.key).length"><li v-for="item in displayedIntelligenceItems(section.key)" :key="item">{{ item }}</li></ul>
            <p v-else>未确认</p>
            <button v-if="intelligenceList(section.key).length > 3" type="button" class="content-toggle" :aria-expanded="Boolean(expandedIntelligenceSections[section.key])" @click="expandedIntelligenceSections[section.key] = !expandedIntelligenceSections[section.key]">{{ expandedIntelligenceSections[section.key] ? '收起' : `展开其余 ${intelligenceList(section.key).length - 3} 项` }}</button>
          </article>
        </div>
        <details class="intelligence-evidence"><summary>查看分析证据 · {{ intelligence.latest.evidence.length }} 条</summary><div><article v-for="source in intelligence.latest.evidence" :key="source.sourceKey"><b>{{ source.sourceType }}</b><span>{{ dateTime(source.sourceAt) }}</span><p>{{ source.excerpt }}</p></article></div></details>
      </template>
      <div v-else class="empty-state">
        {{ intelligence.processing ? '首次分析正在队列中，结果将以可追溯版本保存。' : '新增客户画像或沟通时间线后，系统将自动生成首次洞察。' }}
      </div>
    </section>
    <CustomerClassificationPanel :customer-id="id" :interactions="interactions" :can-edit="Boolean(collaboration?.canEditTimeline && !customer.archivedAt)" @changed="load" />
    <div class="profile-grid">
      <aside class="panel profile-facts">
        <div class="panel-title"><div><p class="eyebrow">PROFILE</p><h2>客户画像</h2></div></div>
        <div class="profile-friend-funnel">
          <div :class="{ reached: customer.friendAddedAt }"><b>01</b><span>添加好友</span><small>{{ dateTime(customer.friendAddedAt) }}</small></div>
          <i></i>
          <div :class="{ reached: customer.friendTrustedAt }"><b>02</b><span>建立信任</span><small>{{ dateTime(customer.friendTrustedAt) }}</small></div>
          <i></i>
          <div :class="{ reached: customer.friendQualifiedAt }"><b>03</b><span>筛选确认</span><small>{{ dateTime(customer.friendQualifiedAt) }}</small></div>
        </div>
        <div :class="['profile-quality-card', customerQualityTag ? `quality-${customerQualityCode(customerQualityTag).toLowerCase()}` : 'quality-unset']">
          <span>客户质量</span>
          <strong>{{ customerQualityTag?.name || '未评级' }}</strong>
          <small>{{ customerQualityTag ? customerQualityDescription(customerQualityTag) : '等待团队进一步核实客户需求' }}</small>
        </div>
        <dl><div><dt>主负责人</dt><dd>{{ customer.ownerDisplayName || '未知' }}</dd></div><div><dt>协作跟进</dt><dd class="collaborator-names">{{ collaboration?.collaborators.map((member) => member.displayName).join('、') || '暂无' }}</dd></div><div><dt>录入人</dt><dd>{{ customer.creatorDisplayName || '未知' }}</dd></div><div><dt>身份</dt><dd>{{ customer.identityType }}</dd></div><div><dt>高中学校</dt><dd>{{ customer.highSchool || '—' }}</dd></div><div><dt>学生生源地</dt><dd>{{ customer.sourceLocation || '—' }}</dd></div><div><dt>核实状态</dt><dd>{{ customer.verificationStatus }}</dd></div><div><dt>紧急程度</dt><dd>{{ customer.urgencyLevel }}</dd></div><div><dt>来源</dt><dd>{{ customer.sourceChannel || '—' }}</dd></div><div><dt>下次跟进</dt><dd>{{ dateTime(customer.nextFollowUpAt) }}</dd></div><div><dt>最近沟通</dt><dd>{{ dateTime(customer.lastContactAt) }}</dd></div></dl>
        <div class="fact-block"><span>需求摘要</span><p>{{ customer.demandSummary || '尚未补充需求摘要。' }}</p></div>
        <div class="fact-block"><span>标签</span><div class="tag-list"><i v-for="tag in visibleCustomerTags" :key="tag.id" :style="{ borderColor: tag.color }">{{ tag.name }}</i><small v-if="!visibleCustomerTags.length">暂无标签</small></div></div>
        <div class="fact-block"><span>内部备注</span><p>{{ customer.ownerNotes || '—' }}</p></div>
        <button v-if="collaboration?.canArchive && !customer.archivedAt" class="danger-link" @click="archiveCustomer">归档客户</button>
      </aside>
      <section class="panel timeline-panel">
        <div class="panel-title"><div><p class="eyebrow">TIMELINE</p><h2>沟通时间线</h2></div><span>{{ interactions.length }} 条记录</span></div>
        <div v-if="!interactions.length" class="empty-state">还没有沟通记录。</div>
        <article v-for="item in interactions" :key="item.id" class="timeline-item">
          <div class="timeline-dot"></div><div class="timeline-content"><header><strong>{{ item.channel }} · {{ item.summary }}</strong><time>{{ dateTime(item.contactedAt) }}</time></header><small class="timeline-author">记录人：{{ item.createdByDisplayName || '历史成员' }}<template v-if="item.interactionType"> · {{ interactionTypeLabel(item.interactionType) }}</template></small><p v-if="item.details" :class="['timeline-long-text', { expanded: expandedTimelineItems[item.id] }]">{{ item.details }}</p><div v-if="item.outcome" :class="['timeline-note timeline-long-text', { expanded: expandedTimelineItems[item.id] }]"><span>沟通结果</span>{{ item.outcome }}</div><button v-if="timelineNeedsExpansion(item)" type="button" class="content-toggle" :aria-expanded="Boolean(expandedTimelineItems[item.id])" @click="expandedTimelineItems[item.id] = !expandedTimelineItems[item.id]">{{ expandedTimelineItems[item.id] ? '收起详情' : '展开完整记录' }}</button><div v-if="item.deadlineEffectReason" class="timeline-next">归属期限：{{ item.deadlineEffect === 'APPLIED' ? '已生效' : '未续期' }} · {{ item.deadlineEffectReason }}</div><div v-if="item.nextFollowUpAt" class="timeline-next">下一次跟进：{{ dateTime(item.nextFollowUpAt) }}</div></div>
        </article>
      </section>
    </div>
    <div v-if="panelOpen" class="drawer-backdrop" @click.self="panelOpen = false">
      <aside class="drawer">
        <header><div><p class="eyebrow">NEW INTERACTION</p><h2>记录沟通</h2></div><button class="icon-button" aria-label="关闭记录沟通" @click="panelOpen = false">×</button></header>
        <form @submit.prevent="saveInteraction">
          <div class="form-grid two"><label>实际发生时间 *<input v-model="record.contactedAt" type="datetime-local" required></label><label>沟通渠道 *<select v-model="record.channel"><option>微信</option><option>电话</option><option>QQ</option><option>面谈</option><option>其他</option></select></label></div>
          <label>记录类型 *<select v-model="record.interactionType" required><option value="CUSTOMER_REPLY">客户已回复（有效跟进）</option><option value="OUTBOUND_NO_REPLY">真实触达但客户未回复</option><option value="AGREED_LATER">客户约定以后联系</option><option value="INTERNAL_NOTE">普通内部备注（不续期）</option></select><small>系统分别保存实际发生与录入时间；超过 48 小时补录仅保留历史，不刷新归属期限。</small></label>
          <label>摘要 *<input v-model.trim="record.summary" required maxlength="500" placeholder="一句话记录本次沟通重点"></label>
          <label>详细记录<textarea v-model.trim="record.details" rows="5"></textarea></label>
          <label>沟通结果<textarea v-model.trim="record.outcome" rows="2"></textarea></label>
          <label>下次跟进<input v-model="record.nextFollowUpAt" type="datetime-local"></label>
          <details><summary><span>↻</span><div><strong>同步客户信息</strong><small>状态、意向、核实状态与普通标签</small></div></summary><div class="details-body form-grid two"><label>当前状态<select v-model="record.currentStatus"><option value="">保持不变</option><option v-for="value in ['初次接触','待核实','已沟通','跟进中','高意向','待决定','已转化','暂缓','无效']" :key="value">{{ value }}</option></select></label><label>报名意向<select v-model="record.intentionLevel"><option value="">保持不变</option><option>高</option><option>中</option><option>低</option></select></label><label>核实状态<select v-model="record.verificationStatus"><option value="">保持不变</option><option>未核实</option><option>部分待确认</option><option>已核实</option></select></label><div class="span-2 current-quality-readonly"><span>当前客户等级</span><strong>{{ customerQualityTag?.name || '未评级' }}</strong><small>A/B/C 等级需在“客户分级与归属期限”模块中提交事实依据后调整，本次沟通不会改变等级。</small></div><label class="span-2">其他标签<div class="chip-picker"><label v-for="tag in regularTags" :key="tag.id"><input v-model="record.tagIds" type="checkbox" :value="tag.id" @change="record.tagUpdateRequested = true"><span>{{ tag.name }}</span></label></div></label></div></details>
          <p v-if="error" class="form-error">{{ error }}</p><footer class="form-actions"><button type="button" class="ghost-button" @click="panelOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '完成记录' }}</button></footer>
        </form>
      </aside>
    </div>
    <div v-if="intelligenceReminderOpen" class="drawer-backdrop" @click.self="intelligenceReminderOpen = false">
      <aside class="drawer" data-testid="intelligence-reminder-drawer">
        <header><div><p class="eyebrow">HUMAN-CONFIRMED ACTION</p><h2>从 AI 洞察创建跟进提醒</h2><p class="muted">AI 只预填建议；标题和时间由你编辑并确认后才会写入。</p></div><button class="icon-button" type="button" aria-label="关闭跟进提醒" @click="intelligenceReminderOpen = false">×</button></header>
        <form @submit.prevent="saveIntelligenceReminder">
          <div class="intelligence-reminder-source"><span>来源分析版本</span><strong>v{{ intelligenceReminder.sourceVersionNumber }}</strong><small>创建后保留版本关联，便于审计建议来源。</small></div>
          <label>提醒标题 *<textarea v-model.trim="intelligenceReminder.title" rows="4" required maxlength="240" data-testid="intelligence-reminder-title"></textarea></label>
          <label>提醒时间 *<input v-model="intelligenceReminder.remindAt" type="datetime-local" required data-testid="intelligence-reminder-at"></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <footer class="form-actions"><button type="button" class="ghost-button" @click="intelligenceReminderOpen = false">取消</button><button class="primary-button" data-testid="save-intelligence-reminder" :disabled="intelligenceReminderBusy">{{ intelligenceReminderBusy ? '创建中…' : '确认创建' }}</button></footer>
        </form>
      </aside>
    </div>
    <div v-if="sharingOpen" class="drawer-backdrop" @click.self="sharingOpen = false">
      <aside class="drawer collaboration-drawer" data-testid="collaboration-drawer">
        <header><div><p class="eyebrow">SHARED FOLLOW-UP</p><h2>共享客户跟进</h2><p class="muted">协作者共用这一份客户档案，可编辑客户画像并新增沟通时间线；主负责人保持不变。</p></div><button class="icon-button" aria-label="关闭共享跟进" @click="sharingOpen = false">×</button></header>
        <div class="collaboration-owner"><span>主负责人</span><strong>{{ customer.ownerDisplayName || '未知' }}</strong></div>
        <div class="collaboration-options">
          <label v-for="member in collaboration?.candidates" :key="member.membershipId">
            <input v-model="selectedCollaborators" type="checkbox" :value="member.membershipId">
            <span><strong>{{ member.displayName }}</strong><small>{{ member.teamNames || '未分组' }}</small></span>
          </label>
          <div v-if="!collaboration?.candidates.length" class="empty-state small">暂无可共享的启用成员。</div>
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
        <footer class="form-actions"><button type="button" class="ghost-button" @click="sharingOpen = false">取消</button><button type="button" class="primary-button" data-testid="save-collaborators" :disabled="sharingBusy" @click="saveSharing">{{ sharingBusy ? '保存中…' : '保存共享' }}</button></footer>
      </aside>
    </div>
    <div v-if="transferOpen" class="drawer-backdrop" @click.self="transferOpen = false">
      <aside class="drawer collaboration-drawer" data-testid="ownership-transfer-drawer">
        <header><div><p class="eyebrow">OWNERSHIP TRANSFER</p><h2>转接客户所有权</h2><p class="muted">主负责人和待办提醒将移交给接收人；录入人、历史时间线和现有协作者保持不变。</p></div><button class="icon-button" aria-label="关闭客户转接" @click="transferOpen = false">×</button></header>
        <div class="collaboration-owner"><span>当前主负责人</span><strong>{{ customer.ownerDisplayName || '未知' }}</strong></div>
        <label>客户接收人 *
          <select v-model="transfer.targetMembershipId" data-testid="ownership-transfer-target">
            <option value="">请选择接收人</option>
            <option v-for="member in collaboration?.transferCandidates" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}{{ member.teamNames ? ` · ${member.teamNames}` : ' · 未分组' }}</option>
          </select>
        </label>
        <label>转接说明（选填）
          <textarea v-model.trim="transfer.reason" rows="3" maxlength="500" placeholder="例如：客户交接、排班调整或团队协同"></textarea>
        </label>
        <div class="notice"><strong>转接影响</strong><span>私有客户转接后，原负责人可能不再能查看该档案。接收人所在团队将成为客户归属团队。</span></div>
        <div v-if="transferCandidatesBusy" class="empty-state small">正在刷新可用接收人…</div>
        <div v-else-if="!collaboration?.transferCandidates.length" class="empty-state small">暂无符合当前权限范围的启用接收人。</div>
        <p v-if="error" class="form-error">{{ error }}</p>
        <footer class="form-actions"><button type="button" class="ghost-button" @click="transferOpen = false">取消</button><button type="button" class="primary-button" data-testid="confirm-ownership-transfer" :disabled="transferBusy || transferCandidatesBusy || !transfer.targetMembershipId" @click="transferCustomer">{{ transferBusy ? '转接中…' : '确认转接' }}</button></footer>
      </aside>
    </div>
  </div>
  <div v-else class="loading-state">正在加载客户档案…</div>
</template>

<style scoped>
.customer-detail-actions{display:flex;align-items:center;justify-content:flex-end;gap:9px;flex-wrap:wrap}
.deal-button{min-height:42px;padding:0 22px;border:0;border-radius:14px;background:linear-gradient(135deg,#185bd7,#314fc4);color:#fff;font-weight:800;box-shadow:0 10px 24px rgba(38,79,190,.24);cursor:pointer}.deal-button:disabled{opacity:.62;cursor:wait}.deal-state{display:inline-flex;align-items:center;min-height:42px;padding:0 16px;border-radius:999px;font-size:13px;font-weight:800}.deal-state.pending{background:#fff4d7;color:#8a6010}.deal-state.won{background:#dff7e9;color:#187044}
.deal-summary-panel{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1px;padding:0;overflow:hidden;background:#e3e9f4}.deal-summary-panel>div{display:grid;gap:7px;padding:18px 20px;background:#fff}.deal-summary-panel span,.deal-summary-panel small{font-size:12px;color:#748199}.deal-summary-panel strong{color:#15233d;font-size:18px;overflow-wrap:anywhere}
.customer-secondary-actions{display:flex;flex-wrap:wrap;justify-content:flex-end}
.mobile-more-action{display:none}
.content-toggle{width:max-content;min-height:34px;padding:4px 0;border:0;background:transparent;color:#3159c7;font-size:12px;font-weight:750;cursor:pointer}
.current-quality-readonly{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:6px 12px;padding:14px;border:1px solid #dfe6f2;border-radius:14px;background:#f7f9fd}.current-quality-readonly>span{font-size:12px;color:#78849a}.current-quality-readonly>strong{justify-self:end;color:#243352}.current-quality-readonly>small{grid-column:1/-1;color:#6f7d95;line-height:1.55}
.intelligence-summary p:not(.expanded),.timeline-long-text:not(.expanded){display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:4}
.intelligence-grid li,.intelligence-summary p,.timeline-content p,.timeline-note{overflow-wrap:anywhere}
.intelligence-reminder-success{display:flex;align-items:center;gap:10px;margin:12px 0;padding:12px 14px;border:1px solid #bfe6cf;border-radius:12px;background:#edf9f2;color:#237a4b}.intelligence-reminder-success span{font-size:13px}.intelligence-reminder-source{display:grid;grid-template-columns:1fr auto;gap:5px 12px;margin-bottom:16px;padding:14px;border:1px solid #dfe6f2;border-radius:14px;background:#f7f9fd}.intelligence-reminder-source span,.intelligence-reminder-source small{color:#6f7d95}.intelligence-reminder-source small{grid-column:1/-1;line-height:1.5}
@media(max-width:760px){
  .page{padding-inline:14px}
  .profile-header{padding:18px}
  .profile-identity{gap:12px}
  .profile-identity .avatar.large{width:56px;height:56px;flex:0 0 56px}
  .title-line{align-items:flex-start}
  .title-line h1{width:100%;font-size:26px;overflow-wrap:anywhere}
  .customer-detail-actions{width:100%;display:grid;grid-template-columns:1.35fr 1fr}
  .deal-button,.deal-state{grid-column:1/-1;width:100%;min-height:48px;justify-content:center}
  .deal-summary-panel{grid-template-columns:1fr}.deal-summary-panel>div{padding:15px 18px}
  .customer-primary-action,.mobile-more-action{display:flex;width:100%;min-height:48px;justify-content:center}
  .customer-secondary-actions{display:none;grid-column:1/-1;grid-template-columns:1fr 1fr;gap:8px;padding:10px;border:1px solid #e2e8f3;border-radius:16px;background:#f8faff}
  .customer-secondary-actions.open{display:grid}
  .customer-secondary-actions>*{width:100%;min-height:44px;justify-content:center;text-align:center}
  .customer-intelligence-panel,.profile-facts,.timeline-panel{padding:18px}
  .customer-intelligence-panel .panel-title{align-items:flex-start;gap:14px}
  .customer-intelligence-panel .panel-title>.header-actions{width:100%;justify-content:space-between}
  .intelligence-summary{padding:16px}
  .intelligence-summary p{line-height:1.7}
  .intelligence-grid{grid-template-columns:1fr}
  .intelligence-grid article{padding:16px}
  .intelligence-grid li{line-height:1.65}
  .profile-grid{grid-template-columns:1fr}
  .profile-friend-funnel{overflow-x:auto;overscroll-behavior-inline:contain}
  .profile-friend-funnel>div{min-width:92px}
  .profile-facts dl>div{gap:14px}
  .profile-facts dt{flex:0 0 82px}
  .profile-facts dd,.collaborator-names{overflow-wrap:anywhere;text-align:right}
  .timeline-item{grid-template-columns:14px minmax(0,1fr);gap:9px}
  .timeline-item:not(:last-child)::before{left:5px}
  .timeline-content header{display:grid;gap:5px}
  .timeline-content header time{white-space:normal}
  .timeline-long-text:not(.expanded){-webkit-line-clamp:4}
  .drawer-backdrop{z-index:100;align-items:stretch}
  .drawer{width:100%;height:100dvh;padding:0;display:flex;flex-direction:column;overflow:hidden;border-radius:0;box-shadow:none}
  .drawer>header{position:sticky;z-index:3;top:0;flex:0 0 auto;margin:0;padding:16px 16px 13px;border-bottom:1px solid #e3e8f2;background:#fffffff2;backdrop-filter:blur(16px)}
  .drawer>header h2{font-size:21px}
  .drawer>form{min-height:0;padding:0 16px calc(84px + env(safe-area-inset-bottom));overflow-y:auto;overscroll-behavior:contain}
  .drawer input,.drawer select,.drawer textarea{font-size:16px}
  .drawer input,.drawer select{min-height:46px}
  .drawer .form-grid.two{grid-template-columns:1fr}
  .drawer .form-actions{position:sticky;z-index:4;bottom:0;display:grid;grid-template-columns:1fr 1.2fr;margin:20px -16px 0;padding:12px 16px calc(12px + env(safe-area-inset-bottom));border-top:1px solid #e3e8f2;background:#fffffff2;backdrop-filter:blur(16px)}
  .drawer .form-actions button{width:100%;min-height:48px}
  .collaboration-drawer{padding:0 16px calc(84px + env(safe-area-inset-bottom));overflow-y:auto}
  .collaboration-drawer>header{margin-inline:-16px}
  .collaboration-drawer>.form-actions{position:fixed;right:0;bottom:0;left:0;margin:0;padding:12px 16px calc(12px + env(safe-area-inset-bottom))}
  .collaboration-options label{min-height:52px}
}
</style>
