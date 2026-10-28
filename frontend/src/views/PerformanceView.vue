<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import type { PerformanceActivity, PerformanceHistoryPoint, PerformanceLeaderboardRow, PerformanceToday } from '../types/performance'

const today = ref<PerformanceToday>()
const leaderboard = ref<PerformanceLeaderboardRow[]>([])
const history = ref<PerformanceHistoryPoint[]>([])
const error = ref('')
const success = ref('')
const busy = ref(false)
const scoreExpanded = ref(false)
const claimForm = reactive({ interactionId: '', requestedType: 'ORDINARY', files: [] as File[] })
const activityForm = reactive({ eventDate: localDate(), title: '', goal: '', expectedParticipants: 0, description: '' })
const completionCounts = reactive<Record<string, number>>({})
const completionFiles = reactive<Record<string, File[]>>({})

const progressPercent = computed(() => {
  const baseline = today.value?.score.baseline ?? 0
  if (!baseline) return 0
  return Math.min(100, Math.round(((today.value?.score.earnedPoints ?? 0) / baseline) * 100))
})
const statusLabel = computed(() => ({
  OPEN: '申报中', REVIEWING: '审核结算中', SETTLED: '已结算', NON_SCORING: '非计分日',
}[today.value?.dayStatus ?? 'NON_SCORING']))
const activeActivities = computed(() => today.value?.activities.filter((item) => item.status !== 'REJECTED') ?? [])

function localDate() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
function monthRange() {
  const now = new Date()
  const from = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
  const end = new Date(now.getFullYear(), now.getMonth() + 1, 0)
  return { from, to: `${end.getFullYear()}-${String(end.getMonth() + 1).padStart(2, '0')}-${String(end.getDate()).padStart(2, '0')}` }
}
function formatTime(value: string) { return value.slice(0, 5) }
function claimLabel(type?: string) { return type === 'DEEP' ? '深度交流' : '普通交流' }
function statusText(status: string) {
  return ({ DRAFT: '草稿', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回',
    PROPOSED: '待立项', COMPLETION_SUBMITTED: '成果待审核', ALLOCATED: '已分配', OPEN: '申报中' } as Record<string, string>)[status] ?? status
}
function files(event: Event) { return Array.from((event.target as HTMLInputElement).files ?? []).slice(0, 3) }

async function load() {
  error.value = ''
  const range = monthRange()
  const [todayData, rankData, historyData] = await Promise.all([
    api<PerformanceToday>('/api/v1/performance/today'),
    api<PerformanceLeaderboardRow[]>(`/api/v1/performance/leaderboard?from=${range.from}&to=${range.to}`),
    api<PerformanceHistoryPoint[]>(`/api/v1/performance/history?from=${range.from}&to=${range.to}`),
  ])
  today.value = todayData
  leaderboard.value = rankData
  history.value = historyData
  if (!claimForm.interactionId && todayData.availableInteractions.length) {
    claimForm.interactionId = todayData.availableInteractions[0].interactionId
  }
}

async function submitClaim() {
  if (!claimForm.interactionId || !claimForm.files.length) {
    error.value = '请选择沟通记录并上传至少一张证据截图。'; return
  }
  busy.value = true; error.value = ''; success.value = ''
  try {
    const claim = await api<{ id: string }>('/api/v1/performance/claims', {
      method: 'POST', body: jsonBody({ interactionId: claimForm.interactionId, requestedType: claimForm.requestedType }),
    })
    for (const file of claimForm.files) {
      const body = new FormData(); body.append('file', file)
      await api(`/api/v1/performance/claims/${claim.id}/evidence`, { method: 'POST', body })
    }
    await api(`/api/v1/performance/claims/${claim.id}/submit`, { method: 'POST' })
    success.value = '交流绩效已经提交，等待管理员审核。'
    Object.assign(claimForm, { interactionId: '', requestedType: 'ORDINARY', files: [] })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '交流绩效提交失败' }
  finally { busy.value = false }
}

async function proposeActivity() {
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api('/api/v1/performance/activities', { method: 'POST', body: jsonBody(activityForm) })
    success.value = '活动提案已提交，管理员立项后可上传成果证据。'
    Object.assign(activityForm, { eventDate: localDate(), title: '', goal: '', expectedParticipants: 0, description: '' })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '活动提案提交失败' }
  finally { busy.value = false }
}

async function completeActivity(activity: PerformanceActivity) {
  const selected = completionFiles[activity.id] ?? []
  if (!selected.length) { error.value = '活动成果至少需要一张证据截图。'; return }
  busy.value = true; error.value = ''; success.value = ''
  try {
    for (const file of selected) {
      const body = new FormData(); body.append('file', file)
      await api(`/api/v1/performance/activities/${activity.id}/evidence`, { method: 'POST', body })
    }
    await api(`/api/v1/performance/activities/${activity.id}/complete`, {
      method: 'POST', body: jsonBody({ actualParticipants: completionCounts[activity.id] ?? 0, expectedVersion: activity.version }),
    })
    success.value = '活动成果已提交，等待管理员核定人数和分配分数。'
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '活动成果提交失败' }
  finally { busy.value = false }
}

onMounted(() => load().catch((reason) => { error.value = reason instanceof ApiError ? reason.message : '绩效中心暂时无法加载' }))
</script>

<template>
  <div class="page performance-page">
    <header class="page-header">
      <div><p class="eyebrow">DAILY PERFORMANCE</p><h1>绩效中心</h1><p class="muted">每一分都来自审核通过的业务记录，团队排行只展示汇总，不公开客户证据。</p></div>
      <span v-if="today" :class="['performance-status', today.dayStatus.toLowerCase()]">{{ statusLabel }}</span>
    </header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>

    <section v-if="today" class="performance-score-hero panel">
      <div class="performance-score-main">
        <span>{{ today.date }}</span><strong>{{ today.score.earnedPoints }}</strong><small>/ {{ today.score.baseline }} 分 baseline</small>
      </div>
      <div class="performance-score-progress"><i :style="{ width: `${progressPercent}%` }"></i></div>
      <div :class="['performance-score-grid', { expanded: scoreExpanded }]">
        <span><small>业务得分</small><b>{{ today.score.businessPoints }}</b></span>
        <span><small>后勤分</small><b>{{ today.score.logisticsPoints }}</b></span>
        <span><small>纠错分</small><b>{{ today.score.correctionPoints }}</b></span>
        <span><small>距离达标</small><b>{{ today.score.shortfall }}</b></span>
        <span><small>最终净分</small><b>{{ today.score.finalPoints }}</b></span>
      </div>
      <button type="button" class="performance-score-toggle" :aria-expanded="scoreExpanded" @click="scoreExpanded = !scoreExpanded">{{ scoreExpanded ? '收起得分构成' : '展开全部得分构成' }}</button>
      <p>申报时间 {{ formatTime(today.opensAt) }}–{{ formatTime(today.closesAt) }} · 待审核交流 {{ today.pendingClaims }} 条 · 深度交流已通过 {{ today.approvedDeepCount }} 条 / 待审核 {{ today.pendingDeepCount }} 条</p>
    </section>

    <section v-if="today && !today.participant" class="panel empty-state">管理员尚未将你加入绩效计算，当前只能查看团队排行。</section>

    <div v-if="today?.participant" class="performance-work-grid">
      <form class="panel performance-claim-form" @submit.prevent="submitClaim">
        <div class="panel-title"><div><p class="eyebrow">INTERACTION CLAIM</p><h2>申报交流绩效</h2></div><span class="badge">必须关联沟通</span></div>
        <label>沟通记录<select v-model="claimForm.interactionId" :disabled="!today.submissionOpen" required>
          <option value="">请选择今天由你记录的沟通</option>
          <option v-for="item in today.availableInteractions" :key="item.interactionId" :value="item.interactionId">{{ item.customerName }} · {{ item.channel }} · {{ item.summary }}</option>
        </select></label>
        <label>申报类型<select v-model="claimForm.requestedType" :disabled="!today.submissionOpen"><option value="ORDINARY">普通交流 · {{ today.rule.ordinaryPoints }} 分</option><option value="DEEP">深度交流 · {{ today.rule.deepPoints }} 分</option></select></label>
        <label>证据截图<input type="file" accept="image/jpeg,image/png,image/webp" multiple :disabled="!today.submissionOpen" @change="claimForm.files = files($event)"><small>最多 3 张，单张不超过 5MB</small></label>
        <button class="primary-button" :disabled="busy || !today.submissionOpen">提交审核</button>
        <p v-if="!today.submissionOpen" class="muted">当前不在申报窗口，管理员重开日期后可补交。</p>
      </form>

      <form class="panel performance-activity-form" @submit.prevent="proposeActivity">
        <div class="panel-title"><div><p class="eyebrow">ACTIVITY PROPOSAL</p><h2>发起活动提案</h2></div></div>
        <div class="form-grid two"><label>活动日期<input v-model="activityForm.eventDate" type="date" required></label><label>预计参与人数<input v-model.number="activityForm.expectedParticipants" type="number" min="0" required></label></div>
        <label>活动名称<input v-model.trim="activityForm.title" maxlength="200" required></label>
        <label>活动目标<textarea v-model.trim="activityForm.goal" rows="2" maxlength="2000" required></textarea></label>
        <label>执行说明<textarea v-model.trim="activityForm.description" rows="3" maxlength="6000" required></textarea></label>
        <button class="soft-button" :disabled="busy">提交立项</button>
      </form>
    </div>

    <section v-if="today" class="panel performance-records">
      <div class="panel-title"><div><p class="eyebrow">MY CLAIMS</p><h2>我的交流申报</h2></div><span>{{ today.claims.length }} 条</span></div>
      <div class="performance-record-list"><article v-for="claim in today.claims" :key="claim.id">
        <div><strong>{{ claim.customerName }} · {{ claimLabel(claim.approvedType ?? claim.requestedType) }}</strong><small>{{ claim.performanceDate }} · {{ claim.evidence.length }} 张证据</small></div>
        <span :class="['table-status', claim.status === 'APPROVED' ? 'ready' : claim.status === 'REJECTED' ? 'failed' : 'pending']">{{ statusText(claim.status) }}</span>
        <p v-if="claim.reviewReason">{{ claim.reviewReason }}</p>
        <div class="evidence-links"><a v-for="file in claim.evidence" :key="file.id" :href="`/api/v1/performance/evidence/${file.id}`" target="_blank" rel="noopener">{{ file.fileName }}</a></div>
      </article><p v-if="!today.claims.length" class="empty-state">今天还没有交流绩效申报。</p></div>
    </section>

    <section v-if="today" class="panel performance-history">
      <div class="panel-title"><div><p class="eyebrow">MY SCORE DETAILS</p><h2>本月积分明细与趋势</h2></div><span>{{ history.length }} 个有分日期</span></div>
      <div v-if="history.length" class="performance-trend" aria-label="本月绩效趋势">
        <div v-for="point in history" :key="point.date" class="performance-trend-column">
          <span :style="{ height: `${Math.max(6, Math.min(100, point.baseline ? point.finalPoints / point.baseline * 70 : point.finalPoints * 4))}%` }"></span>
          <strong>{{ point.finalPoints }}</strong><small>{{ point.date.slice(5) }}</small>
        </div>
      </div>
      <div class="performance-ledger-list"><article v-for="entry in today.ledger" :key="entry.id"><div><strong>{{ entry.description }}</strong><small>{{ entry.category }} · {{ entry.createdAt.slice(11, 16) }}</small></div><b :class="{ negative: entry.points < 0 }">{{ entry.points > 0 ? '+' : '' }}{{ entry.points }}</b></article><p v-if="!today.ledger.length" class="empty-state">今天还没有审核入账的积分。</p></div>
    </section>

    <section v-if="today" class="panel performance-records">
      <div class="panel-title"><div><p class="eyebrow">MY ACTIVITIES</p><h2>我的活动</h2></div><span>{{ activeActivities.length }} 个</span></div>
      <div class="performance-record-list"><article v-for="activity in activeActivities" :key="activity.id">
        <div><strong>{{ activity.title }}</strong><small>{{ activity.eventDate }} · 预计 {{ activity.expectedParticipants }} 人</small></div>
        <span class="table-status pending">{{ statusText(activity.status) }}</span>
        <p>{{ activity.goal }}</p>
        <form v-if="activity.status === 'APPROVED'" class="activity-completion" @submit.prevent="completeActivity(activity)">
          <label>实际参与人数<input v-model.number="completionCounts[activity.id]" type="number" min="0" required></label>
          <label>成果证据<input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="completionFiles[activity.id] = files($event)"></label>
          <button class="soft-button" :disabled="busy">提交成果</button>
        </form>
        <small v-if="activity.status === 'ALLOCATED'">核定 {{ activity.verifiedParticipants }} 人 · {{ activity.tierLabel }} · 总分 {{ activity.totalPoints }}</small>
      </article><p v-if="!activeActivities.length" class="empty-state">还没有活动记录。</p></div>
    </section>

    <section class="panel performance-ranking">
      <div class="panel-title"><div><p class="eyebrow">TEAM RANKING</p><h2>本月团队排行</h2></div><span>全员可见汇总</span></div>
      <div class="table-wrap"><table><thead><tr><th>排名</th><th>成员</th><th>团队</th><th>已得分</th><th>最终净分</th><th>达标天数</th></tr></thead><tbody>
        <tr v-for="row in leaderboard" :key="row.membershipId"><td><b class="rank-number">{{ row.rank }}</b></td><td>{{ row.displayName }}</td><td>{{ row.teamName }}</td><td>{{ row.earnedPoints }}</td><td><strong>{{ row.finalPoints }}</strong></td><td>{{ row.achievedDays }} / {{ row.scoredDays }}</td></tr>
      </tbody></table></div>
    </section>
  </div>
</template>

<style scoped>
.performance-score-toggle{display:none}
@media(max-width:700px){
  .page-header{align-items:flex-start}.performance-status{min-height:40px}
  .performance-score-hero{padding:20px}.performance-score-main{flex-wrap:wrap}.performance-score-main strong{font-size:54px}.performance-score-main small{width:100%}
  .performance-score-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.performance-score-grid span{min-width:0;padding:12px}.performance-score-grid:not(.expanded) span:nth-child(n+4){display:none}.performance-score-toggle{display:flex;width:100%;min-height:44px;align-items:center;justify-content:center;margin-top:10px;color:#fff;border:1px solid rgba(255,255,255,.24);border-radius:12px;background:rgba(255,255,255,.09);font-size:12px;font-weight:700}
  .performance-score-hero>p{overflow-wrap:anywhere;line-height:1.65}
  .performance-work-grid{grid-template-columns:1fr}.performance-claim-form,.performance-activity-form,.performance-records,.performance-ranking{padding:18px}.performance-activity-form .form-grid.two{grid-template-columns:1fr}
  .performance-record-list>article{grid-template-columns:1fr}.performance-record-list>article>.table-status{justify-self:start}.performance-record-list article form{grid-template-columns:1fr}.performance-record-list article form>*{width:100%;min-height:44px}
  .performance-history .panel-title{align-items:flex-start;flex-direction:column}.performance-ledger-list article{align-items:flex-start}.performance-ledger-list article>div{min-width:0}.performance-ledger-list small{overflow-wrap:anywhere}
  .performance-ranking .table-wrap{overflow-x:auto;overscroll-behavior-inline:contain;touch-action:pan-x pan-y}.performance-ranking table{min-width:650px}
}
</style>
