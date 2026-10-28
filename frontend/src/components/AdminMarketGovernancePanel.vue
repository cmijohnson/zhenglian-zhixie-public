<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime, localInput, toIso } from '../lib/format'

interface Overview { deadlineDue: number; poolOpen: number; temporaryClaims: number; secondLineRequired: number; contactAuthorizations: number; takeoverTasks: number; classificationReviews: number; wonPending: number; quotePending: number }
interface Review { classificationId: string; customerId: string; customerName: string; level: 'A'|'B'|'C'; submittedByDisplayName: string; recommended: boolean; selected: boolean; reviewStatus: string; createdAt: string }
interface Anomaly { membershipId: string; displayName: string; totalDecisions: number; aDecisions: number; aRate: number; teamMedianRate: number; abnormal: boolean }
interface ClassificationDataIssue { customerId: string; customerName: string; issueType: 'MULTIPLE_QUALITY_TAGS'|'CLASSIFICATION_TAG_MISMATCH'; qualityTags: string; structuredLevel?: string }
interface Rule { id: string; version: number; effectiveFrom: string; mode: string; aPeakHours: number; bPeakHours: number; aOffPeakHours: number; bOffPeakHours: number; weekendsCount: boolean }
interface Classification { level: string; recognitionReason: string; potentialDemand: string; aSignals: string[]; evidenceInteractionIds: string[] }
interface DealRequest { id: string; customerId: string; customerName: string; teamName?: string; ownerDisplayName: string; applicantMembershipId: string; applicantDisplayName: string; status: 'PENDING'|'APPROVED'|'REJECTED'; requestedAt: string; reviewedAt?: string; reviewerMembershipId?: string; version: number }
interface DealRequestPage { content: DealRequest[]; page: number; size: number; totalElements: number; totalPages: number }

const overview = ref<Overview>()
const reviews = ref<Review[]>([])
const anomalies = ref<Anomaly[]>([])
const classificationDataIssues = ref<ClassificationDataIssue[]>([])
const rules = ref<Rule[]>([])
const dealRequests = ref<DealRequest[]>([])
const loading = ref(true); const busy = ref(false); const error = ref(''); const success = ref('')
const ruleOpen = ref(false)
const ruleForm = reactive({ effectiveFrom: localInput(new Date(Date.now() + 86400000).toISOString()), mode: 'SHADOW', aPeakHours: 72, bPeakHours: 96, aOffPeakHours: 168, bOffPeakHours: 216, weekendsCount: true, peakPeriods: '', pausePeriods: '' })
const correction = reactive({ classificationId: '', customerId: '', level: 'B' as 'A'|'B'|'C', recognitionReason: '', potentialDemand: '', aSignals: '', note: '', evidenceInteractionIds: [] as string[] })
const pendingCount = computed(() => (overview.value?.classificationReviews ?? 0) + (overview.value?.takeoverTasks ?? 0) + (overview.value?.secondLineRequired ?? 0) + dealRequests.value.length + classificationDataIssues.value.length)

async function load() {
  loading.value = true; error.value = ''
  try {
    const [summary, reviewRows, anomalyRows, issueRows, ruleRows, dealRows] = await Promise.all([
      api<Overview>('/api/v1/admin/governance/overview'),
      api<Review[]>('/api/v1/admin/market/classification-reviews?recommendedOnly=false'),
      api<Anomaly[]>('/api/v1/admin/market/classification-anomalies'),
      api<ClassificationDataIssue[]>('/api/v1/admin/market/classification-data-issues'),
      api<Rule[]>('/api/v1/admin/market/deadline-rules'),
      api<DealRequestPage>('/api/v1/admin/deal-requests?status=PENDING&page=0&size=100'),
    ])
    overview.value = summary
    reviews.value = Array.isArray(reviewRows) ? reviewRows : []
    anomalies.value = Array.isArray(anomalyRows) ? anomalyRows : []
    classificationDataIssues.value = Array.isArray(issueRows) ? issueRows : []
    rules.value = Array.isArray(ruleRows) ? ruleRows : []
    dealRequests.value = dealRows.content ?? []
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '治理待办加载失败' }
  finally { loading.value = false }
}

async function confirmReview(item: Review) {
  await run(async () => { await api(`/api/v1/admin/market/classification-reviews/${item.classificationId}/review`, { method: 'POST', body: jsonBody({ decision: 'CONFIRMED', note: '管理员复核确认' }) }); success.value = '分级复核已确认。'; await load() })
}

async function beginCorrection(item: Review) {
  error.value = ''
  try {
    const current = await api<Classification>(`/api/v1/customers/${item.customerId}/classifications/current`)
    Object.assign(correction, { classificationId: item.classificationId, customerId: item.customerId, level: current.level, recognitionReason: current.recognitionReason, potentialDemand: current.potentialDemand, aSignals: current.aSignals.join('\n'), note: '', evidenceInteractionIds: current.evidenceInteractionIds })
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '无法读取当前分级' }
}

async function correctReview() {
  await run(async () => {
    await api(`/api/v1/admin/market/classification-reviews/${correction.classificationId}/review`, { method: 'POST', body: jsonBody({ decision: 'CORRECTED', note: correction.note || undefined, correction: { level: correction.level, recognitionReason: correction.recognitionReason, potentialDemand: correction.potentialDemand, aSignals: correction.aSignals.split('\n').map((item) => item.trim()).filter(Boolean), evidenceInteractionIds: correction.evidenceInteractionIds, correctionReason: correction.note || '管理员抽查修正' } }) })
    correction.classificationId = ''; success.value = '分级已以新历史版本修正，原记录保留。'; await load()
  })
}

function ranges(value: string) { return value.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => { const [start, end] = line.split('~').map((item) => item.trim()); return { start, end } }) }
async function createRule() {
  await run(async () => { await api('/api/v1/admin/market/deadline-rules', { method: 'POST', body: jsonBody({ ...ruleForm, effectiveFrom: toIso(ruleForm.effectiveFrom), peakPeriods: ranges(ruleForm.peakPeriods), pausePeriods: ranges(ruleForm.pausePeriods) }) }); ruleOpen.value = false; success.value = '新期限规则已建立，仅从生效时间起使用。'; await load() })
}

async function reviewDeal(item: DealRequest, approve: boolean) {
  const action = approve ? '确认' : '驳回'
  if (!window.confirm(`${action}“${item.customerName}”的成单申请？`)) return
  await run(async () => {
    await api(`/api/v1/admin/deal-requests/${item.id}/${approve ? 'approve' : 'reject'}`, {
      method: 'POST', headers: { 'If-Match-Version': String(item.version) },
    })
    success.value = approve ? '成单已确认，客户档案将显示“已成单”。' : '成单申请已驳回。'
    await load()
  })
}

async function run(action: () => Promise<void>) { busy.value = true; error.value = ''; success.value = ''; try { await action() } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '操作失败' } finally { busy.value = false } }
onMounted(load)
</script>

<template>
  <section class="governance-workbench">
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <div class="metric-grid governance-metrics"><article><span>即将/已超时</span><strong>{{ overview?.deadlineDue ?? '—' }}</strong><small>24 小时窗口</small></article><article><span>公共池</span><strong>{{ overview?.poolOpen ?? '—' }}</strong><small>待认领</small></article><article><span>临时认领</span><strong>{{ overview?.temporaryClaims ?? '—' }}</strong><small>24 小时待转正</small></article><article><span>缺少二线</span><strong>{{ overview?.secondLineRequired ?? '—' }}</strong><small>A 类客户</small></article><article><span>联系授权</span><strong>{{ overview?.contactAuthorizations ?? '—' }}</strong><small>待处理</small></article><article><span>治理待办</span><strong>{{ pendingCount }}</strong><small>抽查、接任与成单</small></article></div>
    <div v-if="loading" class="panel empty-state">正在加载治理数据…</div>
    <div v-else class="governance-sections">
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">A-CLASS REVIEW</p><h2>A 类抽查与异常</h2></div><span>30% 稳定随机抽查</span></div><div class="review-list"><article v-for="item in reviews.filter((row) => row.reviewStatus === 'PENDING')" :key="item.classificationId"><div><strong>{{ item.customerName }} · {{ item.level }} 类</strong><small>{{ item.submittedByDisplayName }} · {{ dateTime(item.createdAt) }}<template v-if="item.recommended"> · 抽查命中</template></small></div><RouterLink :to="`/customers/${item.customerId}`" class="text-button">查看依据</RouterLink><button class="ghost-button" :disabled="busy" @click="confirmReview(item)">确认</button><button class="ghost-button" :disabled="busy" @click="beginCorrection(item)">修正</button></article><div v-if="!reviews.some((row) => row.reviewStatus === 'PENDING')" class="empty-state small">暂无待复核分级。</div></div><div class="anomaly-list"><span v-for="item in anomalies.filter((row) => row.abnormal)" :key="item.membershipId"><b>{{ item.displayName }}</b>A 类率 {{ (item.aRate * 100).toFixed(1) }}% · 团队中位数 {{ (item.teamMedianRate * 100).toFixed(1) }}%</span><small v-if="!anomalies.some((row) => row.abnormal)">未检测到超过团队中位数 2 倍的异常成员。</small></div></section>
      <section class="panel classification-data-issues"><div class="panel-title"><div><p class="eyebrow">DATA INTEGRITY</p><h2>客户等级数据修复清单</h2><p class="muted">仅列出不能安全自动判断的问题；请进入客户档案按事实完成结构化分级。</p></div><span>{{ classificationDataIssues.length }} 项</span></div><div class="review-list"><article v-for="item in classificationDataIssues" :key="item.customerId"><div><strong>{{ item.customerName }}</strong><small>{{ item.issueType === 'MULTIPLE_QUALITY_TAGS' ? '同时存在多个客户等级标签' : '兼容标签与结构化等级不一致' }} · 标签 {{ item.qualityTags }}<template v-if="item.structuredLevel"> · 结构化 {{ item.structuredLevel }} 类</template></small></div><RouterLink :to="`/customers/${item.customerId}`" class="text-button">查看并修复</RouterLink></article><div v-if="!classificationDataIssues.length" class="empty-state small">未发现等级数据不一致。</div></div></section>
      <section v-if="correction.classificationId" class="panel correction-form"><div class="panel-title"><div><h2>修正客户分级</h2><p class="muted">修正将生成新版本，原始申报不被删除。</p></div></div><label>等级<select v-model="correction.level"><option>A</option><option>B</option><option>C</option></select></label><label>认定说明<textarea v-model.trim="correction.recognitionReason" rows="3" required></textarea></label><label>潜在需求<textarea v-model.trim="correction.potentialDemand" rows="3" required></textarea></label><label v-if="correction.level === 'A'">A 类信号<textarea v-model.trim="correction.aSignals" rows="2"></textarea></label><label>复核意见<textarea v-model.trim="correction.note" rows="2"></textarea></label><div class="form-actions"><button class="ghost-button" @click="correction.classificationId = ''">取消</button><button class="primary-button" :disabled="busy" @click="correctReview">保存修正</button></div></section>
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">DEADLINE ENGINE</p><h2>A/B 归属期限规则</h2></div><button class="ghost-button" @click="ruleOpen = !ruleOpen">＋ 新版本</button></div><div class="rule-list"><article v-for="rule in rules" :key="rule.id"><b>V{{ rule.version }} · {{ rule.mode }}</b><span>旺季 A/B：{{ rule.aPeakHours }}/{{ rule.bPeakHours }} 小时 · 淡季 A/B：{{ rule.aOffPeakHours }}/{{ rule.bOffPeakHours }} 小时</span><small>{{ rule.weekendsCount ? '周末计时' : '周末暂停' }} · 生效 {{ dateTime(rule.effectiveFrom) }}</small></article></div><form v-if="ruleOpen" class="rule-form" @submit.prevent="createRule"><label>生效时间<input v-model="ruleForm.effectiveFrom" type="datetime-local" required></label><label>模式<select v-model="ruleForm.mode"><option value="SHADOW">影子计算（不自动释放）</option><option value="ENFORCED">执行模式</option></select></label><div class="rule-numbers"><label>旺季 A 小时<input v-model.number="ruleForm.aPeakHours" type="number" min="1"></label><label>旺季 B 小时<input v-model.number="ruleForm.bPeakHours" type="number" min="1"></label><label>淡季 A 小时<input v-model.number="ruleForm.aOffPeakHours" type="number" min="1"></label><label>淡季 B 小时<input v-model.number="ruleForm.bOffPeakHours" type="number" min="1"></label></div><label>旺季日期段（每行 start ~ end）<textarea v-model="ruleForm.peakPeriods" rows="2" placeholder="2026-06-01 ~ 2026-09-15"></textarea></label><label>节假日/考试周暂停段<textarea v-model="ruleForm.pausePeriods" rows="2" placeholder="2026-06-20 ~ 2026-06-28"></textarea></label><label class="checkbox-line"><input v-model="ruleForm.weekendsCount" type="checkbox">周末继续计时</label><div class="form-actions"><button class="primary-button" :disabled="busy">创建未来规则版本</button></div></form></section>
      <section class="panel deal-review-panel full-width"><div class="panel-title"><div><p class="eyebrow">DEAL APPROVAL</p><h2>成单审核</h2><p class="muted">员工只需在客户档案点击“成单”；管理员确认后档案显示已成单，原跟进阶段和协作关系不变。</p></div><span>{{ dealRequests.length }} 项待办</span></div><div class="review-list"><article v-for="item in dealRequests" :key="item.id"><div><strong>{{ item.customerName }}</strong><small>{{ item.teamName || '未分组' }} · 负责人 {{ item.ownerDisplayName }} · 申请人 {{ item.applicantDisplayName }} · {{ dateTime(item.requestedAt) }}</small></div><RouterLink :to="`/customers/${item.customerId}`" class="text-button">查看档案</RouterLink><button class="ghost-button" :disabled="busy" @click="reviewDeal(item, false)">驳回</button><button class="primary-button" :disabled="busy" @click="reviewDeal(item, true)">确认成单</button></article><div v-if="!dealRequests.length" class="empty-state small">暂无待审核成单申请。</div></div></section>
    </div>
  </section>
</template>

<style scoped>
.governance-workbench{display:grid;gap:20px}.governance-metrics{grid-template-columns:repeat(6,minmax(0,1fr))}.governance-sections{display:grid;grid-template-columns:1fr 1fr;gap:20px}.governance-sections>.panel{display:grid;gap:16px}.review-list,.rule-list{display:grid;gap:10px}.review-list article{display:flex;align-items:center;gap:10px;padding:12px;border:1px solid #e2e7f0;border-radius:12px}.review-list article>div{display:grid;gap:4px;flex:1}.review-list small,.rule-list small{color:#7e889c}.anomaly-list{display:flex;gap:8px;flex-wrap:wrap}.anomaly-list span{padding:9px 11px;border-radius:10px;background:#fff2ef;color:#914232}.anomaly-list b{margin-right:7px}.correction-form>label,.rule-form>label,.rule-numbers label{display:grid;gap:6px;font-weight:650}.rule-list article{display:grid;gap:5px;padding:12px;border-left:3px solid #7996e9;background:#f7f9ff}.rule-form{display:grid;gap:12px;padding:15px;border:1px solid #e0e7f2;border-radius:14px}.rule-form fieldset{display:flex;gap:10px;flex-wrap:wrap;border:1px solid #dfe6f1;border-radius:12px;padding:12px}.rule-numbers{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.checkbox-line{display:flex!important;align-items:center;gap:8px}.checkbox-line input{width:auto}.changeable-cycles{display:flex;gap:8px;flex-wrap:wrap}.participant-change-admin,.full-width{grid-column:1/-1}@media(max-width:1200px){.governance-metrics{grid-template-columns:repeat(3,1fr)}}@media(max-width:900px){.governance-sections{grid-template-columns:1fr}.rule-numbers{grid-template-columns:1fr 1fr}.governance-sections>.panel{padding:18px 14px}.panel-title{align-items:flex-start}.panel-title>button{width:100%;min-height:44px}.review-list article{display:grid;grid-template-columns:1fr 1fr}.review-list article>div{grid-column:1/-1}.review-list article>a,.review-list article>button{min-height:44px;justify-content:center}.changeable-cycles{display:grid;grid-template-columns:1fr}.changeable-cycles button{width:100%;min-height:44px}.rule-form .form-actions{position:sticky;bottom:calc(72px + env(safe-area-inset-bottom));z-index:2;display:grid;grid-template-columns:1fr 1fr;padding:10px 0;background:rgba(255,255,255,.96)}.rule-form .form-actions button{min-height:48px}}@media(max-width:600px){.governance-metrics{grid-template-columns:1fr 1fr}.rule-numbers{grid-template-columns:1fr}.review-list article{align-items:flex-start}.anomaly-list span{width:100%}}
@media(max-width:900px){.governance-workbench{min-width:0;grid-template-columns:minmax(0,1fr)}.governance-workbench>*{min-width:0;max-width:100%}.governance-sections{min-width:0;grid-template-columns:minmax(0,1fr)}}
@media(max-width:600px){.governance-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
