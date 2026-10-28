<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime, localInput, toIso } from '../lib/format'
import type { Consultation, ConsultationFailureReason, ConsultationSettings, ConsultantCandidate, Customer, Interaction, PageResponse } from '../types'

const route = useRoute()
const auth = useAuthStore()
const rows = ref<Consultation[]>([])
const customers = ref<Customer[]>([])
const candidates = ref<ConsultantCandidate[]>([])
const failureReasons = ref<ConsultationFailureReason[]>([])
const settings = ref<ConsultationSettings>()
const sourceInteractions = ref<Interaction[]>([])
const formOpen = ref(typeof route.query.customerId === 'string')
const busy = ref(false)
const draftBusy = ref(false)
const error = ref('')
const success = ref('')
const rejectionReasons = reactive<Record<string, string>>({})
const completionOpen = ref<string>()
const result = reactive({ resultSummary: '', continueFollowUp: true, nextStep: '', nextOwnerMembershipId: '', nextFollowUpAt: '', failureReasonId: '', failureReasonOther: '' })
const draftUncertainties = ref<string[]>([])
const form = reactive({
  customerId: typeof route.query.customerId === 'string' ? route.query.customerId : '', consultantMembershipId: '',
  sourceInteractionIds: [] as string[], consultationReason: '', customerQuotes: '', goalsMotivation: '',
  actionsTaken: '', mainConcerns: '', decisionParticipants: '', decisionParticipantConsent: false,
  productFit: '', suggestedFocus: '', explicitTaboos: '', expectedResult: '', scheduledStart: '', duration: 60,
})

const selectedFailureReason = computed(() => failureReasons.value.find((item) => item.id === result.failureReasonId))
const selectedConsultation = computed(() => rows.value.find((item) => item.id === completionOpen.value))
const statusLabels: Record<string, string> = { PENDING_ACCEPTANCE: '待顾问接受', SCHEDULED: '已排期', AWAITING_FEEDBACK: '待结果回填', COMPLETED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消' }

async function load() {
  error.value = ''
  try {
    const [loadedRows, loadedCustomers, loadedCandidates, loadedSettings, loadedReasons] = await Promise.all([
      api<Consultation[]>('/api/v1/consultations'),
      api<PageResponse<Customer>>('/api/v1/customers?size=100&sort=updatedAt,desc'),
      api<ConsultantCandidate[]>('/api/v1/consultations/candidates'),
      api<ConsultationSettings>('/api/v1/consultation-settings'),
      api<ConsultationFailureReason[]>('/api/v1/consultation-failure-reasons'),
    ])
    rows.value = loadedRows; customers.value = loadedCustomers.items; candidates.value = loadedCandidates
    settings.value = loadedSettings; failureReasons.value = loadedReasons
    if (!form.consultantMembershipId) form.consultantMembershipId = auth.user?.membershipId ?? loadedCandidates[0]?.membershipId ?? ''
    if (!form.duration || !loadedSettings.allowedDurations.includes(form.duration)) form.duration = loadedSettings.allowedDurations[0]
    if (form.customerId) await loadTimeline(form.customerId)
  } catch (reason) { error.value = message(reason, '咨询交接加载失败') }
}

async function loadTimeline(customerId: string) {
  form.sourceInteractionIds = []
  sourceInteractions.value = customerId ? await api<Interaction[]>(`/api/v1/customers/${customerId}/interactions`) : []
}
watch(() => form.customerId, (id) => { if (id) loadTimeline(id) })

function scheduledEnd() {
  const start = toIso(form.scheduledStart)
  return start ? new Date(new Date(start).getTime() + form.duration * 60000).toISOString() : undefined
}

async function create() {
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api('/api/v1/consultations', { method: 'POST', body: jsonBody({ ...form,
      scheduledStart: toIso(form.scheduledStart), scheduledEnd: scheduledEnd(), duration: undefined,
      decisionParticipants: form.decisionParticipants || undefined,
    }) })
    formOpen.value = false; success.value = '咨询交接已提交，等待顾问接受。'; await load()
  } catch (reason) { error.value = message(reason, '咨询交接提交失败') }
  finally { busy.value = false }
}

async function generateDraft() {
  if (!form.customerId) { error.value = '请先选择客户'; return }
  draftBusy.value = true; error.value = ''
  try {
    const generated = await api<{ consultationReason?: string; customerQuotes?: string; goalsMotivation?: string;
      actionsTaken?: string; mainConcerns?: string; decisionParticipants?: string; productFit?: string;
      suggestedFocus?: string; explicitTaboos?: string; expectedResult?: string;
      citations?: { interactionId: string; evidence: string }[]; uncertainties?: string[] }>(`/api/v1/consultations/draft?customerId=${form.customerId}`)
    Object.assign(form, {
      consultationReason: generated.consultationReason || '', customerQuotes: generated.customerQuotes || '',
      goalsMotivation: generated.goalsMotivation || '', actionsTaken: generated.actionsTaken || '',
      mainConcerns: generated.mainConcerns || '', decisionParticipants: generated.decisionParticipants === '未确认' ? '' : generated.decisionParticipants || '',
      productFit: generated.productFit || '', suggestedFocus: generated.suggestedFocus || '',
      explicitTaboos: generated.explicitTaboos || '', expectedResult: generated.expectedResult || '',
      sourceInteractionIds: [...new Set((generated.citations || []).map((item) => item.interactionId))],
    })
    draftUncertainties.value = generated.uncertainties || []
  } catch (reason) { error.value = message(reason, 'AI 交接草稿生成失败') }
  finally { draftBusy.value = false }
}

async function action(row: Consultation, name: 'accept' | 'reject' | 'end') {
  busy.value = true; error.value = ''
  try {
    const body = name === 'reject' ? { expectedVersion: row.version, reason: rejectionReasons[row.id] }
      : name === 'end' ? { expectedVersion: row.version, actualEndedAt: new Date().toISOString() }
        : { expectedVersion: row.version }
    await api(`/api/v1/consultations/${row.id}/${name}`, { method: 'POST', body: jsonBody(body) })
    await load()
  } catch (reason) { error.value = message(reason, '操作失败') }
  finally { busy.value = false }
}

async function cancel(row: Consultation) {
  const reason = window.prompt('请填写取消原因')?.trim()
  if (!reason) return
  busy.value = true; error.value = ''
  try { await api(`/api/v1/consultations/${row.id}/cancel`, { method: 'POST', body: jsonBody({ expectedVersion: row.version, reason }) }); await load() }
  catch (failure) { error.value = message(failure, '取消咨询失败') }
  finally { busy.value = false }
}

function openCompletion(row: Consultation) {
  completionOpen.value = row.id
  Object.assign(result, { resultSummary: '', continueFollowUp: true, nextStep: '',
    nextOwnerMembershipId: row.consultantMembershipId, nextFollowUpAt: '', failureReasonId: '', failureReasonOther: '' })
}

async function complete() {
  const row = selectedConsultation.value
  if (!row) return
  busy.value = true; error.value = ''
  try {
    await api(`/api/v1/consultations/${row.id}/complete`, { method: 'POST', body: jsonBody({
      ...result, expectedVersion: row.version, nextFollowUpAt: toIso(result.nextFollowUpAt),
      failureReasonId: result.continueFollowUp ? undefined : result.failureReasonId,
      failureReasonOther: result.continueFollowUp ? undefined : result.failureReasonOther,
    }) })
    completionOpen.value = undefined; success.value = '咨询结果已写入客户时间线。'; await load()
  } catch (reason) { error.value = message(reason, '结果回填失败') }
  finally { busy.value = false }
}

function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? `${reason.message}${reason.body.traceId ? `（${reason.body.traceId}）` : ''}` : fallback }
onMounted(load)
</script>

<template>
  <div class="page consultation-page">
    <header class="page-header hero-header"><div><p class="eyebrow">CONSULTATION HANDOFF</p><h1>咨询交接</h1><p class="muted">带着客户画像交接：引用原时间线、明确咨询重点，并在结束后 {{ settings?.feedbackDueHours || 48 }} 小时内闭环。</p></div><button class="primary-button" @click="formOpen = true">＋ 发起交接</button></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <section class="panel"><div class="panel-title"><div><p class="eyebrow">PIPELINE</p><h2>咨询进度</h2></div><span>{{ rows.length }} 条</span></div>
      <div v-if="!rows.length" class="empty-state">暂无咨询交接。</div>
      <article v-for="row in rows" :key="row.id" class="consultation-row">
        <div class="consultation-copy"><span class="status-pill">{{ statusLabels[row.status] || row.status }}</span><h3>{{ row.customerName }} · {{ row.consultationReason }}</h3><p>顾问：{{ row.consultantName }} · {{ dateTime(row.scheduledStart) }}—{{ dateTime(row.scheduledEnd) }}</p><details class="consultation-details"><summary>查看交接重点与禁忌</summary><small><b>重点</b>{{ row.suggestedFocus }}</small><small><b>禁忌</b>{{ row.explicitTaboos }}</small></details></div>
        <div class="row-actions">
          <template v-if="row.status === 'PENDING_ACCEPTANCE' && (row.consultantMembershipId === auth.user?.membershipId || auth.isAdmin)"><button class="soft-button" :disabled="busy" @click="action(row, 'accept')">接受</button><input v-model.trim="rejectionReasons[row.id]" maxlength="2000" placeholder="拒绝原因"><button class="ghost-button" :disabled="busy || !rejectionReasons[row.id]" @click="action(row, 'reject')">拒绝</button></template>
          <button v-if="row.status === 'SCHEDULED' && (row.consultantMembershipId === auth.user?.membershipId || auth.isAdmin)" class="soft-button" @click="action(row, 'end')">咨询已结束</button>
          <button v-if="['SCHEDULED','AWAITING_FEEDBACK'].includes(row.status) && (row.consultantMembershipId === auth.user?.membershipId || auth.isAdmin)" class="primary-button" @click="openCompletion(row)">回填结果</button>
          <button v-if="['PENDING_ACCEPTANCE','SCHEDULED'].includes(row.status) && (row.createdByMembershipId === auth.user?.membershipId || row.consultantMembershipId === auth.user?.membershipId || auth.isAdmin)" class="danger-link" @click="cancel(row)">取消</button>
          <RouterLink :to="`/customers/${row.customerId}`" class="ghost-button">客户档案</RouterLink>
        </div>
      </article>
    </section>

    <div v-if="formOpen" class="drawer-backdrop" @click.self="formOpen = false"><aside class="drawer wide-drawer"><header><div><p class="eyebrow">HANDOFF CARD</p><h2>一页咨询交接卡</h2><p class="muted">每项内容都应来自客户原话或已记录时间线；不确定的信息请明确写“未确认”。</p></div><button class="icon-button" @click="formOpen = false">×</button></header>
      <form @submit.prevent="create"><div class="form-actions draft-action"><button type="button" class="soft-button" :disabled="draftBusy || !form.customerId" @click="generateDraft">{{ draftBusy ? 'AI 整理中…' : '根据客户时间线生成带引用草稿' }}</button></div><p v-if="draftUncertainties.length" class="notice">待人工确认：{{ draftUncertainties.join('；') }}</p><div class="form-grid two">
        <label>客户 *<select v-model="form.customerId" required><option value="">请选择客户</option><option v-for="item in customers" :key="item.id" :value="item.id">{{ item.fullName }} · {{ item.school || '学校未填' }}</option></select></label>
        <label>咨询顾问 *<select v-model="form.consultantMembershipId" required><option v-for="item in candidates" :key="item.membershipId" :value="item.membershipId">{{ item.displayName }} · {{ item.teams.join(' / ') || '未分组' }}</option></select></label>
        <label>预约开始 *<input v-model="form.scheduledStart" type="datetime-local" required><small>{{ settings?.businessStart }}–{{ settings?.businessEnd }}，至少提前 {{ settings?.minimumLeadMinutes }} 分钟</small></label>
        <label>时长 *<select v-model.number="form.duration"><option v-for="duration in settings?.allowedDurations" :key="duration" :value="duration">{{ duration }} 分钟</option></select><small>每名顾问每天最多 {{ settings?.dailyCapacity }} 场</small></label>
        <label class="span-2">引用来源时间线 *<div class="source-list"><label v-for="item in sourceInteractions" :key="item.id"><input v-model="form.sourceInteractionIds" type="checkbox" :value="item.id"><span>{{ dateTime(item.contactedAt) }} · {{ item.channel }} · {{ item.summary }}</span></label><small v-if="!sourceInteractions.length">该客户暂无时间线，请先记录真实沟通。</small></div></label>
        <label>为什么安排咨询 *<textarea v-model.trim="form.consultationReason" rows="4" required maxlength="6000"></textarea></label><label>客户关键原话 *<textarea v-model.trim="form.customerQuotes" rows="4" required maxlength="10000"></textarea></label>
        <label>目标与动机 *<textarea v-model.trim="form.goalsMotivation" rows="4" required></textarea></label><label>已做行动 *<textarea v-model.trim="form.actionsTaken" rows="4" required></textarea></label>
        <label>主要顾虑 *<textarea v-model.trim="form.mainConcerns" rows="4" required></textarea></label><label>产品适配 *<textarea v-model.trim="form.productFit" rows="4" required></textarea></label>
        <label>建议重点 *<textarea v-model.trim="form.suggestedFocus" rows="4" required></textarea></label><label>明确禁忌 *<textarea v-model.trim="form.explicitTaboos" rows="4" required></textarea></label>
        <label class="span-2">预期咨询结果 *<textarea v-model.trim="form.expectedResult" rows="3" required></textarea></label>
        <label class="span-2">决策参与人（选填）<textarea v-model.trim="form.decisionParticipants" rows="3"></textarea></label><label v-if="form.decisionParticipants" class="span-2 checkbox-line"><input v-model="form.decisionParticipantConsent" type="checkbox"> 客户自愿提供，且记录这些信息为本次业务所必要</label>
      </div><footer class="form-actions"><button type="button" class="ghost-button" @click="formOpen = false">取消</button><button class="primary-button" :disabled="busy || !form.sourceInteractionIds.length">{{ busy ? '提交中…' : '提交交接卡' }}</button></footer></form>
    </aside></div>

    <div v-if="completionOpen && selectedConsultation" class="drawer-backdrop" @click.self="completionOpen = undefined"><aside class="drawer"><header><div><p class="eyebrow">RESULT</p><h2>回填咨询结果</h2><p class="muted">{{ selectedConsultation.customerName }} · 截止 {{ dateTime(selectedConsultation.feedbackDueAt) }}</p></div><button class="icon-button" @click="completionOpen = undefined">×</button></header>
      <form @submit.prevent="complete"><label>沟通结果 *<textarea v-model.trim="result.resultSummary" rows="6" required></textarea></label><label class="checkbox-line"><input v-model="result.continueFollowUp" type="checkbox"> 继续推进该客户</label><label>下一步 *<textarea v-model.trim="result.nextStep" rows="4" required></textarea></label><label>下一负责人 *<select v-model="result.nextOwnerMembershipId" required><option v-for="item in candidates" :key="item.membershipId" :value="item.membershipId">{{ item.displayName }}</option></select></label><label>下次时间<input v-model="result.nextFollowUpAt" type="datetime-local"></label><template v-if="!result.continueFollowUp"><label>失败原因 *<select v-model="result.failureReasonId" required><option value="">请选择</option><option v-for="item in failureReasons" :key="item.id" :value="item.id">{{ item.label }}</option></select></label><label v-if="selectedFailureReason?.code === 'OTHER'">其他原因 *<textarea v-model.trim="result.failureReasonOther" rows="3" required></textarea></label></template><footer class="form-actions"><button type="button" class="ghost-button" @click="completionOpen = undefined">取消</button><button class="primary-button" :disabled="busy">完成闭环</button></footer></form>
    </aside></div>
  </div>
</template>

<style scoped>
.consultation-row{display:flex;justify-content:space-between;gap:24px;padding:22px 0;border-bottom:1px solid #e7ebf3}.consultation-row:last-child{border-bottom:0}.consultation-copy{min-width:0}.consultation-row h3{margin:10px 0 6px;overflow-wrap:anywhere}.consultation-row p,.consultation-row small{display:block;color:#74809a}.consultation-details{margin-top:9px}.consultation-details summary{color:#3157c8;font-size:11px;font-weight:700;cursor:pointer}.consultation-details small{display:grid;grid-template-columns:42px minmax(0,1fr);gap:8px;margin-top:8px;overflow-wrap:anywhere;line-height:1.65}.consultation-details b{color:#34415a}.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:8px;flex-wrap:wrap;max-width:560px}.row-actions input{min-width:180px}.status-pill{display:inline-flex;padding:6px 10px;border-radius:999px;background:#edf2ff;color:#3157c8}.wide-drawer{width:min(980px,96vw)}.source-list{display:grid;gap:8px;max-height:180px;overflow:auto;padding:12px;border:1px solid #e1e7f2;border-radius:12px}.source-list label,.checkbox-line{display:flex;align-items:flex-start;gap:9px}.source-list input,.checkbox-line input{width:auto}@media(max-width:900px){.consultation-row{flex-direction:column}.row-actions{justify-content:flex-start}}
@media(max-width:700px){
  .hero-header{align-items:stretch}.hero-header>.primary-button{width:100%;min-height:46px}
  .consultation-row{padding:18px 0}.consultation-details summary{display:flex;min-height:44px;align-items:center}.row-actions{display:grid;grid-template-columns:1fr 1fr;max-width:none}.row-actions input{grid-column:1/-1;min-width:0;min-height:44px}.row-actions button,.row-actions a{min-height:44px}.row-actions>.danger-link{justify-self:start}
  .drawer-backdrop{align-items:flex-end}.drawer,.wide-drawer{width:100%;max-width:none;height:min(92dvh,920px);padding:18px 16px calc(18px + env(safe-area-inset-bottom));border-radius:22px 22px 0 0}.drawer>header{position:sticky;z-index:3;top:-18px;margin:-18px -16px 16px;padding:18px 16px 14px;background:#fff}.drawer form{padding-bottom:16px}.drawer .form-grid.two{grid-template-columns:1fr}.drawer .span-2{grid-column:auto}.source-list{max-height:30dvh}.drawer .form-actions{position:sticky;bottom:calc(-18px - env(safe-area-inset-bottom));z-index:2;display:grid;grid-template-columns:1fr 1fr;margin:18px -16px calc(-18px - env(safe-area-inset-bottom));padding:12px 16px calc(12px + env(safe-area-inset-bottom));background:rgba(255,255,255,.96);box-shadow:0 -8px 24px rgba(32,46,80,.08)}.drawer .form-actions button{min-height:48px}
}
</style>
