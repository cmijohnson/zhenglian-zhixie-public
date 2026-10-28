<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import type { EventTier, PerformanceActivity, PerformanceClaim, PerformanceDay, PerformanceLeaderboardRow, PerformanceParticipant, PerformanceRule } from '../types/performance'

interface Member { membershipId: string; displayName: string; status: string; roles: string[]; teams: string[]; teamIds?: string[] }
interface Team { id: string; name: string; active: boolean }

const rules = ref<PerformanceRule[]>([])
const participants = ref<PerformanceParticipant[]>([])
const members = ref<Member[]>([])
const teams = ref<Team[]>([])
const monthlySummary = ref<PerformanceLeaderboardRow[]>([])
const claims = ref<PerformanceClaim[]>([])
const activities = ref<PerformanceActivity[]>([])
const day = ref<PerformanceDay>()
const selectedDate = ref(localDate())
const selectedMonth = ref(localDate().slice(0, 7))
const selectedTeamId = ref('')
const selectedMemberId = ref('')
const error = ref('')
const success = ref('')
const busy = ref(false)
const claimReasons = reactive<Record<string, string>>({})
const claimTypes = reactive<Record<string, string>>({})
const activityReasons = reactive<Record<string, string>>({})
const verifiedCounts = reactive<Record<string, number>>({})
const allocationDrafts = reactive<Record<string, Record<string, number>>>({})
const reopenReason = ref('')
const manualEvidence = ref<File[]>([])

const participantForm = reactive({ membershipId: '', startsOn: localDate(), endsOn: '' })
const manualForm = reactive({ membershipId: '', performanceDate: localDate(), entryType: 'LOGISTICS', title: '', reason: '', points: 0, relatedLedgerId: '' })
const ruleForm = reactive({
  effectiveFrom: tomorrow(), dailyBaseline: 14, calendarMode: 'WEEKDAYS', weekdays: [1, 2, 3, 4, 5] as number[],
  includedDates: '', excludedDates: '', submissionOpensAt: '09:00', submissionClosesAt: '22:00',
  shortfallMode: 'STATUS_ONLY', ordinaryPoints: 1, deepPoints: 2,
  ordinaryDailyCap: '' as number | '', deepDailyCap: '' as number | '',
  eventTiers: [{ label: 'Pro', minParticipants: 50, maxParticipants: undefined, points: 8 }] as EventTier[],
})

const eligibleMembers = computed(() => members.value.filter((member) => member.status === 'ACTIVE'
  && member.roles.some((role) => ['ORG_ADMIN', 'TEAM_MANAGER', 'MEMBER'].includes(role))))
const activeParticipants = computed(() => participants.value.filter((participant) => participant.activeToday))
const proposedActivities = computed(() => activities.value.filter((item) => item.status === 'PROPOSED'))
const completedActivities = computed(() => activities.value.filter((item) => item.status === 'COMPLETION_SUBMITTED'))
const filteredDayMembers = computed(() => day.value?.members.filter((member) =>
  (!selectedTeamId.value || member.teamId === selectedTeamId.value)
  && (!selectedMemberId.value || member.membershipId === selectedMemberId.value)) ?? [])
const filteredMonthlySummary = computed(() => monthlySummary.value.filter((row) =>
  (!selectedTeamId.value || row.teamId === selectedTeamId.value)
  && (!selectedMemberId.value || row.membershipId === selectedMemberId.value)))

function localDate() { return new Date().toLocaleDateString('sv-SE') }
function tomorrow() { const date = new Date(); date.setDate(date.getDate() + 1); return date.toLocaleDateString('sv-SE') }
function dates(value: string) { return value.split(/[，,\s]+/).map((item) => item.trim()).filter(Boolean) }
function selectedFiles(event: Event) { return Array.from((event.target as HTMLInputElement).files ?? []).slice(0, 3) }
function statusText(status: string) { return ({ OPEN: '申报中', REVIEWING: '审核中', SETTLED: '已结算', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', PROPOSED: '待立项', COMPLETION_SUBMITTED: '成果待核定', ALLOCATED: '已分配' } as Record<string, string>)[status] ?? status }
function ruleForActivity(activity: PerformanceActivity) { return rules.value.find((rule) => rule.effectiveFrom <= activity.eventDate) ?? rules.value.at(-1) }
function tierFor(activity: PerformanceActivity) {
  const count = verifiedCounts[activity.id] ?? activity.actualParticipants ?? 0
  return ruleForActivity(activity)?.eventTiers.find((tier) => count >= tier.minParticipants && (tier.maxParticipants == null || count <= tier.maxParticipants))
}
function allocationTotal(activity: PerformanceActivity) {
  return Object.values(allocationDrafts[activity.id] ?? {}).reduce((sum, value) => sum + (Number(value) || 0), 0)
}
function monthRange(date: string) {
  const [year, month] = date.split('-').map(Number)
  const end = new Date(year, month, 0).getDate()
  return { from: `${year}-${String(month).padStart(2, '0')}-01`, to: `${year}-${String(month).padStart(2, '0')}-${end}` }
}

async function load() {
  error.value = ''
  const range = monthRange(selectedMonth.value)
  const [ruleData, participantData, memberData, teamData, claimData, activityData, summaryData] = await Promise.all([
    api<PerformanceRule[]>('/api/v1/performance/admin/rules'),
    api<PerformanceParticipant[]>('/api/v1/performance/admin/participants'),
    api<Member[]>('/api/v1/admin/members'),
    api<Team[]>('/api/v1/admin/teams'),
    api<PerformanceClaim[]>('/api/v1/performance/claims?status=PENDING'),
    api<PerformanceActivity[]>('/api/v1/performance/activities'),
    api<PerformanceLeaderboardRow[]>(`/api/v1/performance/leaderboard?from=${range.from}&to=${range.to}`),
  ])
  rules.value = ruleData; participants.value = participantData; members.value = memberData; teams.value = teamData
  monthlySummary.value = summaryData
  claims.value = claimData; activities.value = activityData
  completedActivities.value.forEach((activity) => {
    allocationDrafts[activity.id] ??= {}
    verifiedCounts[activity.id] ??= activity.actualParticipants ?? 0
  })
  await loadDay()
}
async function loadDay() {
  try { day.value = await api<PerformanceDay>(`/api/v1/performance/admin/days/${selectedDate.value}`) }
  catch (reason) { day.value = undefined; if (reason instanceof ApiError && reason.body.code !== 'PERFORMANCE_NON_SCORING_DAY') throw reason }
}
async function execute(action: () => Promise<unknown>, message: string) {
  busy.value = true; error.value = ''; success.value = ''
  try { await action(); success.value = message; await load() }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '操作失败，请稍后重试' }
  finally { busy.value = false }
}

async function createRule() {
  await execute(() => api('/api/v1/performance/admin/rules', { method: 'POST', body: jsonBody({
    ...ruleForm,
    weekdays: ruleForm.weekdays,
    includedDates: dates(ruleForm.includedDates), excludedDates: dates(ruleForm.excludedDates),
    ordinaryDailyCap: ruleForm.ordinaryDailyCap || undefined, deepDailyCap: ruleForm.deepDailyCap || undefined,
    eventTiers: ruleForm.eventTiers.map((tier) => ({ ...tier, maxParticipants: tier.maxParticipants ?? undefined })),
  }) }), '新绩效规则已保存，将从生效日期开始使用。')
}
async function addParticipant() {
  await execute(() => api('/api/v1/performance/admin/participants', { method: 'POST', body: jsonBody({
    membershipId: participantForm.membershipId, startsOn: participantForm.startsOn, endsOn: participantForm.endsOn || undefined,
  }) }), '参与人员已添加。')
}
async function closeParticipant(participant: PerformanceParticipant) {
  await execute(() => api(`/api/v1/performance/admin/participants/${participant.id}`, { method: 'PATCH', body: jsonBody({ endsOn: localDate(), expectedVersion: participant.version }) }), '参与周期已结束，历史绩效不受影响。')
}
async function reviewClaim(claim: PerformanceClaim, decision: 'APPROVE' | 'REJECT') {
  await execute(() => api(`/api/v1/performance/claims/${claim.id}/review`, { method: 'POST', body: jsonBody({
    decision, approvedType: claimTypes[claim.id] || claim.requestedType, reason: claimReasons[claim.id] || undefined,
    expectedVersion: claim.version,
  }) }), decision === 'APPROVE' ? '交流绩效已审核入账。' : '交流绩效已驳回。')
}
async function reviewActivity(activity: PerformanceActivity, decision: 'APPROVE' | 'REJECT') {
  await execute(() => api(`/api/v1/performance/activities/${activity.id}/review`, { method: 'POST', body: jsonBody({
    decision, reason: activityReasons[activity.id] || undefined, expectedVersion: activity.version,
  }) }), decision === 'APPROVE' ? '活动已立项。' : '活动提案已驳回。')
}
async function allocateActivity(activity: PerformanceActivity) {
  const allocations = Object.entries(allocationDrafts[activity.id] ?? {})
    .filter(([, points]) => Number(points) > 0).map(([membershipId, points]) => ({ membershipId, points: Number(points) }))
  await execute(() => api(`/api/v1/performance/activities/${activity.id}/allocate`, { method: 'POST', body: jsonBody({
    verifiedParticipants: verifiedCounts[activity.id] ?? activity.actualParticipants ?? 0,
    allocations, expectedVersion: activity.version,
  }) }), '活动分数已分配并写入积分流水。')
}
async function createManual() {
  await execute(async () => {
    const ledger = await api<{ sourceId: string }>('/api/v1/performance/admin/manual-entries', { method: 'POST', body: jsonBody({
      ...manualForm, relatedLedgerId: manualForm.relatedLedgerId || undefined,
    }) })
    for (const file of manualEvidence.value) {
      const body = new FormData(); body.append('file', file)
      await api(`/api/v1/performance/admin/manual-entries/${ledger.sourceId}/evidence`, { method: 'POST', body })
    }
    manualEvidence.value = []
  }, manualForm.entryType === 'LOGISTICS' ? '基础后勤分已入账。' : '纠错分已入账。')
}
async function settle() {
  const current = day.value
  if (!current) return
  await execute(() => api(`/api/v1/performance/admin/days/${current.date}/settle?expectedVersion=${current.version}`, { method: 'POST' }), '当日绩效已结算锁定。')
}
async function reopen() {
  const current = day.value
  if (!current) return
  await execute(() => api(`/api/v1/performance/admin/days/${current.date}/reopen`, { method: 'POST', body: jsonBody({ reason: reopenReason.value, expectedVersion: current.version }) }), '当日绩效已重开。')
}
function exportData() {
  const range = monthRange(selectedMonth.value)
  window.location.href = `/api/v1/performance/admin/export?from=${range.from}&to=${range.to}`
}

onMounted(() => load().catch((reason) => { error.value = reason instanceof ApiError ? reason.message : '绩效管理暂时无法加载' }))
</script>

<template>
  <div class="page admin-performance-page">
    <header class="page-header"><div><p class="eyebrow">PERFORMANCE CONTROL</p><h1>绩效管理</h1><p class="muted">配置未来规则、审核业务证据、分配活动分并完成每日结算。</p></div><button class="soft-button" type="button" @click="exportData">导出本月明细</button></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>

    <section class="panel performance-admin-section performance-admin-overview">
      <div class="panel-title"><div><p class="eyebrow">MONTHLY OVERVIEW</p><h2>组织绩效汇总</h2></div><span>组织范围由当前登录身份确定</span></div>
      <div class="form-grid three"><label>团队<select v-model="selectedTeamId"><option value="">全部团队</option><option v-for="team in teams.filter((item) => item.active)" :key="team.id" :value="team.id">{{ team.name }}</option></select></label><label>成员<select v-model="selectedMemberId"><option value="">全部成员</option><option v-for="member in members.filter((item) => item.status === 'ACTIVE' && (!selectedTeamId || item.teamIds?.includes(selectedTeamId)))" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label><label>统计月份<input v-model="selectedMonth" type="month" @change="load"></label></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>排名</th><th>成员</th><th>团队</th><th>已得分</th><th>最终净分</th><th>达标天数</th></tr></thead><tbody><tr v-for="row in filteredMonthlySummary" :key="`${row.membershipId}-${row.teamId}`"><td data-label="排名">{{ row.rank }}</td><td data-label="成员">{{ row.displayName }}</td><td data-label="团队">{{ row.teamName }}</td><td data-label="已得分">{{ row.earnedPoints }}</td><td data-label="最终净分"><strong>{{ row.finalPoints }}</strong></td><td data-label="达标天数">{{ row.achievedDays }} / {{ row.scoredDays }}</td></tr></tbody></table></div>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">RULE VERSION</p><h2>创建未来规则版本</h2></div><span>当前 {{ rules.length }} 个版本</span></div>
      <form class="performance-rule-form" @submit.prevent="createRule">
        <div class="form-grid four"><label>生效日期<input v-model="ruleForm.effectiveFrom" type="date" :min="tomorrow()" required></label><label>每日 baseline<input v-model.number="ruleForm.dailyBaseline" type="number" min="0" step="0.5" required></label><label>计分日历<select v-model="ruleForm.calendarMode"><option value="WEEKDAYS">工作日</option><option value="ALL_DAYS">每天</option><option value="CUSTOM">自定义星期</option></select></label><label>未达标处理<select v-model="ruleForm.shortfallMode"><option value="STATUS_ONLY">只显示差额</option><option value="DEDUCT_GAP">扣除差额</option></select></label></div>
        <div class="weekday-picker"><label v-for="(name, index) in ['一','二','三','四','五','六','日']" :key="name"><input v-model="ruleForm.weekdays" type="checkbox" :value="index + 1"><span>周{{ name }}</span></label></div>
        <div class="form-grid four"><label>开放时间<input v-model="ruleForm.submissionOpensAt" type="time" required></label><label>截止时间<input v-model="ruleForm.submissionClosesAt" type="time" required></label><label>普通交流分<input v-model.number="ruleForm.ordinaryPoints" type="number" min="0" step="0.5" required></label><label>深度交流分<input v-model.number="ruleForm.deepPoints" type="number" min="0" step="0.5" required></label><label>普通每日上限<input v-model.number="ruleForm.ordinaryDailyCap" type="number" min="1" placeholder="留空不限"></label><label>深度每日上限<input v-model.number="ruleForm.deepDailyCap" type="number" min="1" placeholder="留空不限"></label><label>补充计分日期<input v-model.trim="ruleForm.includedDates" placeholder="2026-08-08, ..."></label><label>排除日期<input v-model.trim="ruleForm.excludedDates" placeholder="2026-10-01, ..."></label></div>
        <div class="performance-tier-editor"><header><strong>活动人数档次</strong><button type="button" class="text-button" @click="ruleForm.eventTiers.push({ label: '', minParticipants: 0, maxParticipants: undefined, points: 0 })">+ 添加档次</button></header><div v-for="(tier, index) in ruleForm.eventTiers" :key="index" class="form-grid four"><input v-model.trim="tier.label" placeholder="档次名称" required><input v-model.number="tier.minParticipants" type="number" min="0" placeholder="最少人数" required><input v-model.number="tier.maxParticipants" type="number" min="0" placeholder="最多人数，留空无上限"><div class="inline-action"><input v-model.number="tier.points" type="number" min="0" step="0.5" placeholder="总分" required><button type="button" class="danger-link" @click="ruleForm.eventTiers.splice(index, 1)">删除</button></div></div></div>
        <button class="primary-button" :disabled="busy">保存新版本</button>
      </form>
      <div class="rule-version-list"><article v-for="rule in rules" :key="rule.id"><strong>V{{ rule.versionNo }} · {{ rule.effectiveFrom }}</strong><span>baseline {{ rule.dailyBaseline }} · {{ rule.submissionOpensAt.slice(0,5) }}–{{ rule.submissionClosesAt.slice(0,5) }} · 普通 {{ rule.ordinaryPoints }} / 深度 {{ rule.deepPoints }}</span></article></div>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">PARTICIPANTS</p><h2>参与绩效人员</h2></div><span>{{ activeParticipants.length }} 人当前参与</span></div>
      <form class="form-grid four" @submit.prevent="addParticipant"><label>成员<select v-model="participantForm.membershipId" required><option value="">请选择</option><option v-for="member in eligibleMembers" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }} · {{ member.teams.join('、') || '未分组' }}</option></select></label><label>开始日期<input v-model="participantForm.startsOn" type="date" required></label><label>结束日期<input v-model="participantForm.endsOn" type="date"></label><button class="soft-button" :disabled="busy">加入绩效</button></form>
      <div class="participant-grid"><article v-for="participant in participants" :key="participant.id"><div><strong>{{ participant.displayName }}</strong><small>{{ participant.teamName }} · {{ participant.startsOn }} 至 {{ participant.endsOn || '持续' }}</small></div><span :class="['table-status', participant.activeToday ? 'ready' : 'failed']">{{ participant.activeToday ? '参与中' : '已结束' }}</span><button v-if="participant.activeToday" type="button" class="text-button" @click="closeParticipant(participant)">结束参与</button></article></div>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">INTERACTION REVIEW</p><h2>交流绩效审核</h2></div><span>{{ claims.length }} 条待审核</span></div>
      <div class="performance-review-list"><article v-for="claim in claims" :key="claim.id"><div><strong>{{ claim.memberDisplayName }} · {{ claim.customerName }}</strong><small>{{ claim.performanceDate }} · 申报 {{ claim.requestedType === 'DEEP' ? '深度交流' : '普通交流' }}</small><div class="evidence-links"><a v-for="file in claim.evidence" :key="file.id" :href="`/api/v1/performance/evidence/${file.id}`" target="_blank">查看证据</a></div></div><select v-model="claimTypes[claim.id]"><option value="">按申报类型</option><option value="ORDINARY">认定普通交流</option><option value="DEEP">认定深度交流</option></select><input v-model.trim="claimReasons[claim.id]" placeholder="审核意见；驳回时必填"><div><button class="soft-button" type="button" @click="reviewClaim(claim, 'APPROVE')">通过入账</button><button class="danger-link" type="button" @click="reviewClaim(claim, 'REJECT')">驳回</button></div></article><p v-if="!claims.length" class="empty-state">暂无待审核交流。</p></div>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">ACTIVITY REVIEW</p><h2>活动立项与分配</h2></div></div>
      <div class="performance-review-list"><article v-for="activity in proposedActivities" :key="activity.id"><div><strong>{{ activity.organizerDisplayName }} · {{ activity.title }}</strong><small>{{ activity.eventDate }} · 预计 {{ activity.expectedParticipants }} 人</small><p>{{ activity.goal }}</p></div><input v-model.trim="activityReasons[activity.id]" placeholder="驳回原因"><div><button class="soft-button" type="button" @click="reviewActivity(activity, 'APPROVE')">批准立项</button><button class="danger-link" type="button" @click="reviewActivity(activity, 'REJECT')">驳回</button></div></article></div>
      <div class="activity-allocation-list"><article v-for="activity in completedActivities" :key="activity.id"><header><div><strong>{{ activity.title }}</strong><small>{{ activity.organizerDisplayName }} · 实际 {{ activity.actualParticipants }} 人</small></div><div class="evidence-links"><a v-for="file in activity.evidence" :key="file.id" :href="`/api/v1/performance/evidence/${file.id}`" target="_blank">查看证据</a></div></header><div class="form-grid three"><label>核定人数<input v-model.number="verifiedCounts[activity.id]" type="number" min="0"></label><span>匹配档次 <b>{{ tierFor(activity)?.label || '无' }}</b></span><span>可分总分 <b>{{ tierFor(activity)?.points ?? 0 }}</b></span></div><div class="allocation-member-grid"><label v-for="participant in activeParticipants" :key="participant.id"><span>{{ participant.displayName }}</span><input v-model.number="allocationDrafts[activity.id][participant.membershipId]" type="number" min="0" step="0.5" placeholder="0"></label></div><footer><span>当前分配 {{ allocationTotal(activity) }} / {{ tierFor(activity)?.points ?? 0 }}</span><button class="primary-button" type="button" @click="allocateActivity(activity)">确认分配并入账</button></footer></article><p v-if="!proposedActivities.length && !completedActivities.length" class="empty-state">暂无待处理活动。</p></div>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">MANUAL LEDGER</p><h2>后勤分与纠错</h2></div><span>只追加，不改写旧流水</span></div>
      <form class="form-grid four" @submit.prevent="createManual"><label>成员<select v-model="manualForm.membershipId" required><option value="">请选择</option><option v-for="participant in activeParticipants" :key="participant.id" :value="participant.membershipId">{{ participant.displayName }}</option></select></label><label>日期<input v-model="manualForm.performanceDate" type="date" required></label><label>类型<select v-model="manualForm.entryType"><option value="LOGISTICS">基础后勤</option><option value="CORRECTION">纠错</option></select></label><label>分数<input v-model.number="manualForm.points" type="number" step="0.5" :min="manualForm.entryType === 'LOGISTICS' ? 0 : undefined" required></label><label>事项<input v-model.trim="manualForm.title" maxlength="200" required></label><label class="span-2">原因<input v-model.trim="manualForm.reason" maxlength="2000" required></label><label v-if="manualForm.entryType === 'CORRECTION'">原积分流水 ID<input v-model.trim="manualForm.relatedLedgerId" required></label><label>可选证据<input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="manualEvidence = selectedFiles($event)"></label><button class="soft-button" :disabled="busy">确认入账</button></form>
    </section>

    <section class="panel performance-admin-section">
      <div class="panel-title"><div><p class="eyebrow">DAILY SETTLEMENT</p><h2>每日结算</h2></div><label>查看日期<input v-model="selectedDate" type="date" @change="loadDay"></label></div>
      <template v-if="day"><div class="settlement-summary"><strong>{{ day.date }} · {{ statusText(day.status) }}</strong><span>待审核交流 {{ day.pendingClaims }}</span><span>待处理活动 {{ day.pendingActivities }}</span><button v-if="day.status === 'REVIEWING'" class="primary-button" type="button" :disabled="busy || day.pendingClaims > 0 || day.pendingActivities > 0" @click="settle">完成结算</button></div><div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>成员</th><th>团队</th><th>业务</th><th>后勤</th><th>纠错</th><th>已得</th><th>差额</th><th>最终净分</th></tr></thead><tbody><tr v-for="member in filteredDayMembers" :key="member.membershipId"><td data-label="成员">{{ member.displayName }}</td><td data-label="团队">{{ member.teamName }}</td><td data-label="业务">{{ member.score.businessPoints }}</td><td data-label="后勤">{{ member.score.logisticsPoints }}</td><td data-label="纠错">{{ member.score.correctionPoints }}</td><td data-label="已得">{{ member.score.earnedPoints }}</td><td data-label="差额">{{ member.score.shortfall }}</td><td data-label="最终净分"><strong>{{ member.score.finalPoints }}</strong></td></tr></tbody></table></div><form v-if="day.status === 'SETTLED'" class="reopen-form" @submit.prevent="reopen"><input v-model.trim="reopenReason" required maxlength="2000" placeholder="填写重开原因"><button class="danger-link" :disabled="busy">重开该日</button></form></template><p v-else class="empty-state">该日期不是计分日。</p>
    </section>
  </div>
</template>

<style scoped>
.performance-admin-page { gap: 20px; }
@media (max-width: 900px) {
  .admin-performance-page .page-header { align-items: stretch; }
  .admin-performance-page .page-header > button { width: 100%; min-height: 48px; }
  .performance-admin-section { padding: 18px 14px; }
  .performance-admin-section .panel-title { align-items: flex-start; }
  .performance-admin-section .form-grid,
  .performance-rule-form .form-grid,
  .activity-allocation-list .form-grid { grid-template-columns: 1fr; }
  .performance-rule-form > .primary-button,
  .performance-admin-section form > button { width: 100%; min-height: 48px; }
  .weekday-picker { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
  .weekday-picker label { min-height: 44px; justify-content: center; }
  .performance-tier-editor .inline-action { display: grid; grid-template-columns: 1fr auto; gap: 8px; }
  .participant-grid article,
  .performance-review-list article,
  .activity-allocation-list article { grid-template-columns: 1fr; gap: 12px; }
  .performance-review-list article > div:last-child { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .performance-review-list button,
  .activity-allocation-list button { min-height: 44px; }
  .allocation-member-grid { grid-template-columns: 1fr; }
  .activity-allocation-list footer,
  .settlement-summary,
  .reopen-form { display: grid; grid-template-columns: 1fr; gap: 10px; }
  .activity-allocation-list footer button,
  .settlement-summary button,
  .reopen-form button { width: 100%; min-height: 48px; }
}
@media (max-width: 430px) {
  .weekday-picker { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
</style>
