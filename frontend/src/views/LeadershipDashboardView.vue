<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../lib/api'
import LeadershipScreenExperience from '../components/LeadershipScreenExperience.vue'
import {
  defaultLeadershipScreenSettings,
  detectLeadershipEvents,
  previewLeadershipEvent,
  wakeUpEvent,
  type LeadershipAnimationEvent,
  type LeadershipDashboardData,
  type LeadershipEventType,
  type MemberRanking,
} from '../lib/leadershipAnimation'

type RankMetric = 'total' | 'qualityA' | 'trusted' | 'todayTotal' | 'task'
type RankTone = 'cyan' | 'blue' | 'purple' | 'gold' | 'green'

interface RankBoard {
  key: RankMetric
  label: string
  english: string
  tone: RankTone
  scope: string
  unit: string
  rows: MemberRanking[]
  total: number
  participants: number
  average: number
}

const data = ref<LeadershipDashboardData>()
const previousData = ref<LeadershipDashboardData>()
const loading = ref(false)
const error = ref('')
const teamId = ref('')
const membershipId = ref('')
const days = ref(30)
const now = ref(new Date())
const standby = ref(false)
const sceneIndex = ref(0)
const activeEvent = ref<LeadershipAnimationEvent>()
const eventQueue = ref<LeadershipAnimationEvent[]>([])
const seenEventIds = new Set<string>()
const isStandalone = window.location.pathname === '/leadership-screen'
const previewParams = new URLSearchParams(window.location.search)
const previewStandby = previewParams.get('preview') === 'standby'
const previewEventType = previewParams.get('previewEvent') as LeadershipEventType | null
let interactionWakeUntil = 0
let firstLoadedAt = Date.now()
let lastSceneChange = 0
let clockTimer: number | undefined
let refreshTimer: number | undefined
let sceneTimer: number | undefined
let eventTimer: number | undefined
const refreshIntervalMs = 10_000

const screenSettings = computed(() => data.value?.screenSettings ?? defaultLeadershipScreenSettings)
const featuredGrade = computed(() => data.value?.featuredGrade ?? screenSettings.value.featuredGrade ?? '2026')
const rankDefinitions = computed<Omit<RankBoard, 'rows' | 'total' | 'participants' | 'average'>[]>(() => [
  { key: 'total', label: '总添加客户排行榜', english: 'TOTAL CUSTOMERS ADDED', tone: 'cyan', scope: `仅统计 ${featuredGrade.value} 级`, unit: '人' },
  { key: 'todayTotal', label: '总添加客户日排行榜', english: 'DAILY CUSTOMERS ADDED', tone: 'gold', scope: `今日新增 · ${featuredGrade.value} 级`, unit: '人' },
  { key: 'trusted', label: '信任好友排行榜', english: 'TRUST ESTABLISHED', tone: 'purple', scope: `仅统计 ${featuredGrade.value} 级`, unit: '人' },
  { key: 'qualityA', label: 'A类客户排行榜', english: 'QUALITY A CUSTOMERS', tone: 'blue', scope: `仅统计 ${featuredGrade.value} 级`, unit: '人' },
  { key: 'task', label: '任务完成排行榜', english: 'TASK COMPLETION', tone: 'green', scope: '统计当前全部任务', unit: '项' },
])

const rankValue = (row: MemberRanking, metric: RankMetric) => {
  if (metric === 'qualityA') return row.qualityACustomers ?? 0
  if (metric === 'trusted') return row.trustedFriendCustomers
  if (metric === 'todayTotal') return row.todayCustomers ?? 0
  if (metric === 'task') return row.completedTasks
  return row.totalCustomers
}

const rankBoards = computed<RankBoard[]>(() => rankDefinitions.value.map((definition) => {
  const rows = [...(data.value?.rankings ?? [])].sort((left, right) =>
    rankValue(right, definition.key) - rankValue(left, definition.key)
      || (right.qualityACustomers ?? 0) - (left.qualityACustomers ?? 0)
      || left.displayName.localeCompare(right.displayName, 'zh-CN'))
  const total = rows.reduce((sum, row) => sum + rankValue(row, definition.key), 0)
  const participants = rows.filter((row) => rankValue(row, definition.key) > 0).length
  return {
    ...definition,
    rows,
    total,
    participants,
    average: rows.length ? total / rows.length : 0,
  }
}))
const sortedRankings = computed(() => rankBoards.value[0]?.rows ?? [])
const scopeName = computed(() => {
  if (!data.value) return '全组织'
  const member = data.value.members.find((item) => item.membershipId === membershipId.value)
  if (member) return `${member.displayName} · 个人`
  const team = data.value.teams.find((item) => item.id === teamId.value)
  return team ? `${team.name} · 团队` : '全组织'
})
const formattedClock = computed(() => new Intl.DateTimeFormat('zh-CN', {
  hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(now.value))
const formattedDate = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', weekday: 'short',
}).format(now.value))
const taskRingStyle = computed(() => ({
  background: `conic-gradient(#19e6d3 ${(data.value?.taskSummary.completionRate ?? 0) * 360}deg, rgba(73, 113, 160, .24) 0deg)`,
}))
const maxSource = computed(() => Math.max(1, ...(data.value?.sources.map((item) => item.customerCount) ?? [1])))
const topSources = computed(() => (data.value?.sources ?? []).slice(0, 8))
const activeScenes = computed(() => screenSettings.value.sceneOrder
  .filter((scene) => screenSettings.value.enabledScenes.includes(scene)))
const scenePosition = computed(() => activeScenes.value.length ? sceneIndex.value % activeScenes.value.length : 0)
const sceneCycle = computed(() => activeScenes.value.length ? Math.floor(sceneIndex.value / activeScenes.value.length) : 0)
const currentScene = computed(() => activeScenes.value[scenePosition.value] ?? 'time')
const experienceEnabled = computed(() => isStandalone
  && (screenSettings.value.animationsEnabled || previewStandby || Boolean(previewEventType)))

const chartWidth = 960
const chartHeight = 260
const chartPadding = { left: 42, right: 18, top: 18, bottom: 38 }
const chartMax = computed(() => Math.max(1, ...(data.value?.dailyConversions.flatMap((item) => [
  item.addedFriendCustomers, item.trustedFriendCustomers, item.qualifiedFriendCustomers,
]) ?? [1])))
const hasConversion = computed(() => (data.value?.dailyConversions ?? []).some((item) =>
  item.addedFriendCustomers || item.trustedFriendCustomers || item.qualifiedFriendCustomers))
const chartStep = computed(() => {
  const count = Math.max(data.value?.dailyConversions.length ?? 1, 1)
  return (chartWidth - chartPadding.left - chartPadding.right) / count
})
const candleWidth = computed(() => Math.max(5, Math.min(14, chartStep.value * .42)))
const chartX = (index: number) => chartPadding.left + chartStep.value * index + chartStep.value / 2
const chartY = (value: number) => chartPadding.top
  + (chartHeight - chartPadding.top - chartPadding.bottom) * (1 - value / chartMax.value)
const tickValues = computed(() => Array.from({ length: 5 }, (_, index) =>
  Math.round(chartMax.value * (4 - index) / 4)))
const tickY = (index: number) => chartPadding.top
  + (chartHeight - chartPadding.top - chartPadding.bottom) * index / 4
const labelEvery = computed(() => Math.max(1, Math.ceil((data.value?.dailyConversions.length ?? 1) / 10)))

function percent(value: number) {
  return `${Math.round(value * 1000) / 10}%`
}

function shortDate(value: string) {
  return value.slice(5)
}

function dueLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value))
}

function rankDetail(row: MemberRanking, metric: RankMetric) {
  if (metric === 'task') return `完成率 ${percent(row.taskCompletionRate)}`
  if (metric === 'total') return `筛选 ${row.qualifiedFriendCustomers} 人`
  if (metric === 'qualityA') return `占本人客户 ${percent(row.totalCustomers ? (row.qualityACustomers ?? 0) / row.totalCustomers : 0)}`
  if (metric === 'trusted') return `添加后信任率 ${percent(row.addedFriendCustomers ? row.trustedFriendCustomers / row.addedFriendCustomers : 0)}`
  return `累计总量 ${row.totalCustomers} 人`
}

function businessActivityTime(value: LeadershipDashboardData | undefined) {
  if (!value?.lastBusinessActivityAt) return firstLoadedAt
  const parsed = new Date(value.lastBusinessActivityAt).getTime()
  return Number.isFinite(parsed) ? parsed : firstLoadedAt
}

function evaluateStandby() {
  const nextStandby = previewStandby || (isStandalone
    && screenSettings.value.animationsEnabled
    && screenSettings.value.standbyEnabled
    && activeScenes.value.length > 0
    && !activeEvent.value
    && Date.now() >= interactionWakeUntil
    && Date.now() - businessActivityTime(data.value) >= screenSettings.value.idleTimeoutMinutes * 60_000)
  if (nextStandby && !standby.value) {
    sceneIndex.value = 0
    lastSceneChange = Date.now()
  }
  standby.value = nextStandby
}

function startNextEvent() {
  if (activeEvent.value || !eventQueue.value.length) {
    evaluateStandby()
    return
  }
  activeEvent.value = eventQueue.value.shift()
  standby.value = false
  window.clearTimeout(eventTimer)
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  eventTimer = window.setTimeout(
    skipEvent,
    reducedMotion ? 1_200 : screenSettings.value.eventDurationSeconds * 1_000,
  )
}

function skipEvent() {
  window.clearTimeout(eventTimer)
  activeEvent.value = undefined
  window.setTimeout(startNextEvent, 180)
}

function enqueueEvents(events: LeadershipAnimationEvent[]) {
  if (!isStandalone || !screenSettings.value.animationsEnabled) return
  const enabledEvents = new Set(screenSettings.value.enabledEvents)
  const fresh = events.filter((event) => {
    if (event.type !== 'wakeUp' && !enabledEvents.has(event.type)) return false
    if (seenEventIds.has(event.id)) return false
    seenEventIds.add(event.id)
    return true
  }).slice(0, 3)
  eventQueue.value.push(...fresh)
  startNextEvent()
}

function wakeForInteraction() {
  if (!isStandalone || !standby.value) return
  interactionWakeUntil = Date.now() + 2 * 60_000
  standby.value = false
}

async function load(compare = true) {
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ days: String(days.value) })
  if (teamId.value) params.set('teamId', teamId.value)
  if (membershipId.value) params.set('membershipId', membershipId.value)
  try {
    const next = await api<LeadershipDashboardData>(`/api/v1/leadership-dashboard?${params}`)
    next.displayMessages ??= []
    next.tickerItems ??= []
    next.recentCustomerActivities ??= []
    next.recentTaskPublications ??= []
    next.customerQuality ??= {
      classifiedCustomers: 0,
      unclassifiedCustomers: next.headline.totalCustomers,
      levels: [],
    }
    next.screenSettings = { ...defaultLeadershipScreenSettings, ...(next.screenSettings ?? {}) }
    const missingScenes = defaultLeadershipScreenSettings.sceneOrder
      .filter((scene) => !next.screenSettings.sceneOrder.includes(scene))
    next.screenSettings.sceneOrder = [...next.screenSettings.sceneOrder, ...missingScenes]
    next.screenSettings.enabledScenes = [...new Set([
      ...next.screenSettings.enabledScenes,
      ...missingScenes,
    ])]
    next.featuredGrade ??= next.screenSettings.featuredGrade
    const wasStandby = standby.value
    const activityChanged = Boolean(previousData.value
      && previousData.value.lastBusinessActivityAt !== next.lastBusinessActivityAt)
    const events = compare && previousData.value ? detectLeadershipEvents(previousData.value, next) : []
    data.value = next
    if (wasStandby && activityChanged) events.unshift(wakeUpEvent(scopeName.value))
    enqueueEvents(events)
    previousData.value = next
    evaluateStandby()
  } catch {
    error.value = '数据大屏暂时无法加载，请稍后刷新。'
  } finally {
    loading.value = false
  }
}

async function changeTeam() {
  membershipId.value = ''
  await changeScope()
}

async function changeScope() {
  previousData.value = undefined
  eventQueue.value = []
  activeEvent.value = undefined
  seenEventIds.clear()
  await load(false)
}

function downloadReport() {
  const params = new URLSearchParams({ days: String(days.value) })
  if (teamId.value) params.set('teamId', teamId.value)
  if (membershipId.value) params.set('membershipId', membershipId.value)
  window.location.assign(`/api/v1/leadership-dashboard/export.xlsx?${params}`)
}

async function toggleFullscreen() {
  if (document.fullscreenElement) await document.exitFullscreen()
  else await document.documentElement.requestFullscreen()
}

function refreshWhenVisible() {
  if (!document.hidden) void load(true)
}

onMounted(async () => {
  firstLoadedAt = Date.now()
  await load(false)
  if (previewEventType && defaultLeadershipScreenSettings.enabledEvents.includes(previewEventType)) {
    activeEvent.value = previewLeadershipEvent(previewEventType)
    standby.value = false
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    eventTimer = window.setTimeout(
      skipEvent,
      reducedMotion ? 1_200 : screenSettings.value.eventDurationSeconds * 1_000,
    )
  }
  clockTimer = window.setInterval(() => {
    now.value = new Date()
    evaluateStandby()
  }, 1000)
  refreshTimer = window.setInterval(() => { void load(true) }, refreshIntervalMs)
  sceneTimer = window.setInterval(() => {
    if (standby.value && !activeEvent.value
      && Date.now() - lastSceneChange >= screenSettings.value.sceneDurationSeconds * 1_000) {
      sceneIndex.value += 1
      lastSceneChange = Date.now()
    }
  }, 1_000)
  if (isStandalone) {
    window.addEventListener('pointerdown', wakeForInteraction)
    window.addEventListener('keydown', wakeForInteraction)
  }
  window.addEventListener('focus', refreshWhenVisible)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})
onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(refreshTimer)
  window.clearInterval(sceneTimer)
  window.clearTimeout(eventTimer)
  window.removeEventListener('pointerdown', wakeForInteraction)
  window.removeEventListener('keydown', wakeForInteraction)
  window.removeEventListener('focus', refreshWhenVisible)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <div class="leadership-screen">
    <div class="screen-grid-overlay"></div>
    <header class="leadership-header">
      <div class="leadership-brand">
        <span class="leadership-logo">YG</span>
        <div><small>YINGGE INTELLIGENCE CENTER</small><h1>英格营销数据指挥中心</h1></div>
      </div>
      <div class="leadership-scope"><i></i><span>实时经营驾驶舱</span><b>{{ scopeName }}</b></div>
      <div class="leadership-clock">
        <div><strong>{{ formattedClock }}</strong><small>{{ formattedDate }}</small></div>
        <button type="button" title="刷新数据" :class="{ spinning: loading }" @click="load(true)">↻</button>
        <button type="button" title="全屏展示" @click="toggleFullscreen">⛶</button>
      </div>
    </header>

    <div class="leadership-filterbar">
      <span><i></i>数据范围</span>
      <select v-model="teamId" @change="changeTeam"><option value="">全部团队</option><option v-for="team in data?.teams" :key="team.id" :value="team.id">{{ team.name }}</option></select>
      <select v-model="membershipId" @change="changeScope"><option value="">全部成员</option><option v-for="member in data?.members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}{{ member.teamNames ? ` · ${member.teamNames}` : '' }}</option></select>
      <select v-model="days" @change="changeScope"><option :value="7">近 7 天</option><option :value="30">近 30 天</option><option :value="60">近 60 天</option><option :value="90">近 90 天</option></select>
      <button type="button" class="leadership-export" @click="downloadReport">⇩ 导出分析 Excel</button>
      <a v-if="!isStandalone" class="leadership-tv-link" href="/leadership-screen" target="_blank" rel="noopener">◈ 进入电视大屏</a>
      <small v-if="data">{{ data.startDate }} — {{ data.endDate }} · 每 10 秒自动刷新</small>
    </div>

    <p v-if="error" class="leadership-error">{{ error }}</p>
    <template v-if="data">
      <section v-if="data.tickerItems.length" class="leadership-ticker" aria-label="重要经营提示" data-testid="leadership-ticker">
        <div class="leadership-ticker-track">
          <template v-for="pass in 2" :key="pass">
            <span v-for="item in data.tickerItems" :key="`${pass}-${item.id}`" :class="`priority-${item.priority.toLowerCase()}`">
              <i></i>{{ item.text }}
            </span>
          </template>
        </div>
      </section>
      <section class="leadership-kpis leadership-kpis-total">
        <article class="tech-card kpi-card total-kpi-card cyan">
          <div class="kpi-icon">◉</div>
          <div class="total-kpi-copy">
            <span>总客户数</span>
            <strong :key="`total-${data.generatedAt}`" class="metric-flip">{{ data.headline.totalCustomers.toLocaleString() }}</strong>
          </div>
          <small>今日新增 <b>+{{ data.headline.todayNewCustomers }}</b></small>
          <em></em>
        </article>
      </section>

      <section class="tech-card customer-quality-panel" data-testid="leadership-customer-quality">
        <header class="quality-panel-header">
          <div><small>CUSTOMER QUALITY MATRIX</small><h2>客户质量分层</h2></div>
          <p>已评级 <strong>{{ data.customerQuality.classifiedCustomers }}</strong> 人 · 未评级 <strong>{{ data.customerQuality.unclassifiedCustomers }}</strong> 人</p>
        </header>
        <div class="quality-tier-grid">
          <article v-for="level in data.customerQuality.levels" :key="level.code" :class="`tier-${level.code.toLowerCase()}`">
            <b>{{ level.code }}</b>
            <span><small>{{ level.label }}</small><strong>{{ level.customerCount }}</strong><em>人 · {{ percent(level.share) }}</em></span>
            <p>{{ level.description }}</p>
          </article>
          <article class="tier-unset">
            <b>—</b>
            <span><small>未评级</small><strong>{{ data.customerQuality.unclassifiedCustomers }}</strong><em>人 · {{ percent(data.customerQuality.unclassifiedCustomers / Math.max(1, data.headline.totalCustomers)) }}</em></span>
            <p>等待成员或团队长根据真实沟通情况完成分层</p>
          </article>
        </div>
      </section>

      <section class="tech-card friend-stage-total-panel" data-testid="leadership-friend-stage-totals">
        <header>
          <div><small>FRIEND CONVERSION TOTALS</small><h2>好友经营三阶段总数</h2></div>
          <p>仅展示当前累计人数</p>
        </header>
        <div class="friend-stage-total-grid">
          <article class="stage-added">
            <b>＋</b>
            <span><small>添加好友客户</small><strong>{{ data.headline.addedFriendCustomers.toLocaleString() }}</strong><em>人</em></span>
          </article>
          <article class="stage-trusted">
            <b>◆</b>
            <span><small>信任好友客户</small><strong>{{ data.headline.trustedFriendCustomers.toLocaleString() }}</strong><em>人</em></span>
          </article>
          <article class="stage-qualified">
            <b>✓</b>
            <span><small>筛选好友客户</small><strong>{{ data.headline.qualifiedFriendCustomers.toLocaleString() }}</strong><em>人</em></span>
          </article>
        </div>
      </section>

      <section class="leadership-ranking-zone">
        <header class="ranking-zone-header">
          <div>
            <small>{{ featuredGrade }} COHORT · INDIVIDUAL PERFORMANCE MATRIX</small>
            <h2>个人业绩五维排行榜</h2>
          </div>
          <p><i></i>客户业绩仅看 {{ featuredGrade }} 级 · 任务榜统计全部任务 · 按当前负责人唯一归属</p>
        </header>
        <div class="rank-board-grid">
          <article
            v-for="(board, boardIndex) in rankBoards"
            :key="board.key"
            :class="['tech-card', 'rank-metric-card', `tone-${board.tone}`]"
            :style="{ animationDelay: `${boardIndex * 90}ms` }"
          >
            <header>
              <div><small>{{ board.english }}</small><h3>{{ board.label }}</h3></div>
              <span>{{ board.scope }}</span>
            </header>
            <div v-if="board.rows.length" class="rank-champion">
              <div class="champion-orbit"><i></i><b>1</b></div>
              <div class="champion-copy">
                <small>TOP PERFORMER</small>
                <strong>{{ board.rows[0].displayName }}</strong>
                <span>{{ board.rows[0].teamNames || '组织成员' }}</span>
              </div>
              <div class="champion-value">
                <b class="metric-flip">{{ rankValue(board.rows[0], board.key) }}</b>
                <small>{{ board.unit }}</small>
                <em>{{ rankDetail(board.rows[0], board.key) }}</em>
              </div>
            </div>
            <div class="rank-mini-list">
              <div
                v-for="(member, index) in board.rows.slice(1, 6)"
                :key="member.membershipId"
                :style="{ animationDelay: `${boardIndex * 80 + index * 45}ms` }"
              >
                <b>{{ String(index + 2).padStart(2, '0') }}</b>
                <span><strong>{{ member.displayName }}</strong><small>{{ rankDetail(member, board.key) }}</small></span>
                <div><i :style="{ width: `${Math.max(4, rankValue(member, board.key) / Math.max(1, rankValue(board.rows[0], board.key)) * 100)}%` }"></i></div>
                <em>{{ rankValue(member, board.key) }}</em>
              </div>
              <p v-if="!board.rows.length" class="tech-empty">当前范围暂无成员数据</p>
            </div>
            <footer>
              <div><span>团队合计</span><b>{{ board.total }}</b></div>
              <div><span>人均业绩</span><b>{{ board.average.toFixed(1) }}</b></div>
              <div><span>有效成员</span><b>{{ board.participants }}</b></div>
            </footer>
            <div class="rank-scanline"></div>
          </article>
        </div>
      </section>

      <section class="leadership-layout">
        <article class="tech-card conversion-card">
          <header class="tech-title"><div><small>DAILY CONVERSION K-LINE</small><h2>每日好友转化 K 线</h2></div><div class="k-legend"><span class="added">添加</span><span class="trusted">信任</span><span class="qualified">筛选</span></div></header>
          <div class="k-chart">
            <svg :viewBox="`0 0 ${chartWidth} ${chartHeight}`" role="img" aria-label="每日好友转化人数K线">
              <g v-for="(tick, index) in tickValues" :key="`tick-${index}`">
                <line :x1="chartPadding.left" :x2="chartWidth - chartPadding.right" :y1="tickY(index)" :y2="tickY(index)" class="chart-grid-line" />
                <text x="4" :y="tickY(index) + 4" class="chart-axis-text">{{ tick }}</text>
              </g>
              <g v-for="(item, index) in data.dailyConversions" :key="item.date">
                <line :x1="chartX(index)" :x2="chartX(index)" :y1="chartY(item.addedFriendCustomers)" :y2="chartY(item.qualifiedFriendCustomers)" class="k-wick" />
                <circle :cx="chartX(index)" :cy="chartY(item.addedFriendCustomers)" r="3" class="k-added-point"><title>{{ item.date }} 添加 {{ item.addedFriendCustomers }}</title></circle>
                <rect :x="chartX(index) - candleWidth / 2" :y="chartY(item.trustedFriendCustomers)" :width="candleWidth" :height="Math.max(3, chartY(item.qualifiedFriendCustomers) - chartY(item.trustedFriendCustomers))" class="k-body"><title>{{ item.date }}：添加 {{ item.addedFriendCustomers }} / 信任 {{ item.trustedFriendCustomers }} / 筛选 {{ item.qualifiedFriendCustomers }}</title></rect>
                <circle :cx="chartX(index)" :cy="chartY(item.qualifiedFriendCustomers)" r="2.8" class="k-qualified-point" />
                <text v-if="index % labelEvery === 0 || index === data.dailyConversions.length - 1" :x="chartX(index)" :y="chartHeight - 12" text-anchor="middle" class="chart-axis-text">{{ shortDate(item.date) }}</text>
              </g>
            </svg>
            <div v-if="!hasConversion" class="chart-empty"><b>等待首批转化数据</b><span>在客户档案中记录添加、信任和筛选时间后，K 线将自动形成。</span></div>
          </div>
          <div class="funnel-strip">
            <div><span>总客户</span><strong>{{ data.headline.totalCustomers }}</strong></div><i>→</i>
            <div><span>添加好友</span><strong>{{ data.headline.addedFriendCustomers }}</strong><small>{{ percent(data.headline.addedRate) }}</small></div><i>→</i>
            <div><span>建立信任</span><strong>{{ data.headline.trustedFriendCustomers }}</strong><small>{{ percent(data.headline.trustRate) }}</small></div><i>→</i>
            <div><span>筛选确认</span><strong>{{ data.headline.qualifiedFriendCustomers }}</strong><small>{{ percent(data.headline.qualifiedRate) }}</small></div>
          </div>
        </article>

        <article class="tech-card task-card">
          <header class="tech-title"><div><small>TASK EXECUTION</small><h2>任务完成情况</h2></div><span :class="{ danger: data.taskSummary.overdueCount }">逾期 {{ data.taskSummary.overdueCount }}</span></header>
          <div class="task-overview">
            <div class="task-ring" :style="taskRingStyle"><div><strong>{{ percent(data.taskSummary.completionRate) }}</strong><small>整体完成率</small></div></div>
            <div class="task-status-grid">
              <div><span>已完成</span><b>{{ data.taskSummary.completedCount }}</b></div>
              <div><span>待验收</span><b>{{ data.taskSummary.submittedCount }}</b></div>
              <div><span>进行中</span><b>{{ data.taskSummary.inProgressCount }}</b></div>
              <div><span>待开始</span><b>{{ data.taskSummary.pendingCount }}</b></div>
            </div>
          </div>
          <div class="task-progress-list">
            <div v-for="task in data.tasks" :key="task.taskId">
              <header><span><strong>{{ task.title }}</strong><small>截止 {{ dueLabel(task.dueAt) }} · {{ task.completedCount }}/{{ task.assignmentCount }} 完成</small></span><b :class="{ overdue: task.overdue }">{{ percent(task.completionRate) }}</b></header>
              <div><i :style="{ width: `${task.completionRate * 100}%` }"></i><em v-if="task.submittedCount" :style="{ width: `${task.submittedCount / Math.max(1, task.assignmentCount) * 100}%` }"></em></div>
            </div>
            <p v-if="!data.tasks.length" class="tech-empty">当前范围暂无任务</p>
          </div>
        </article>

        <article class="tech-card source-card">
          <header class="tech-title"><div><small>CUSTOMER SOURCE</small><h2>客户来源结构</h2></div><span>TOP {{ topSources.length }}</span></header>
          <div class="source-list">
            <div v-for="(item, index) in topSources" :key="item.source">
              <b>{{ String(index + 1).padStart(2, '0') }}</b>
              <span><strong>{{ item.source }}</strong><small>占比 {{ percent(item.share) }} · 筛选 {{ item.qualifiedFriendCustomers }} / {{ percent(item.qualifiedRate) }}</small></span>
              <div><i :style="{ width: `${item.customerCount / maxSource * 100}%` }"></i></div>
              <em>{{ item.customerCount }}</em>
            </div>
            <p v-if="!data.sources.length" class="tech-empty">暂无来源数据</p>
          </div>
        </article>

        <article class="tech-card leaderboard-table-card">
          <header class="tech-title"><div><small>2026 COHORT SCOREBOARD</small><h2>26级全员业绩明细</h2></div><span>{{ data.rankings.length }} 人</span></header>
          <div class="leadership-table-wrap">
            <table>
              <thead><tr><th>排名</th><th>成员</th><th>26级客户</th><th>添加好友</th><th>信任好友</th><th>筛选好友</th><th>全部任务</th><th>完成率</th></tr></thead>
              <tbody>
                <tr v-for="(member, index) in sortedRankings" :key="member.membershipId">
                  <td><b :class="['rank-number', { medal: index < 3 }]">{{ index + 1 }}</b></td>
                  <td><strong>{{ member.displayName }}</strong><small>{{ member.teamNames || '组织成员' }}</small></td>
                  <td>{{ member.totalCustomers }}</td><td>{{ member.addedFriendCustomers }}</td><td>{{ member.trustedFriendCustomers }}</td><td>{{ member.qualifiedFriendCustomers }}</td>
                  <td>{{ member.completedTasks }} / {{ member.assignedTasks }}</td><td><span class="rate-chip">{{ percent(member.taskCompletionRate) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
      </section>
    </template>
    <div v-else-if="loading" class="leadership-loading"><i></i><span>正在汇聚组织经营数据…</span></div>
    <footer class="leadership-footer"><span>英格客户云 · 数据实时计算</span><b>DATA SECURITY LEVEL · INTERNAL</b><span>{{ data ? `最后更新 ${new Date(data.generatedAt).toLocaleTimeString('zh-CN', { hour12: false })}` : '等待连接' }}</span></footer>
    <LeadershipScreenExperience
      v-if="data && experienceEnabled"
      :data="data"
      :now="now"
      :scope-name="scopeName"
      :standby="standby"
      :scene-id="currentScene"
      :scene-position="scenePosition"
      :scene-count="activeScenes.length"
      :scene-cycle="sceneCycle"
      :motion-intensity="screenSettings.motionIntensity"
      :event="activeEvent"
      @skip="skipEvent"
    />
  </div>
</template>

<style scoped>
@media(max-width:820px){
  .leadership-screen{min-width:0;padding:10px;padding-bottom:calc(18px + env(safe-area-inset-bottom));overflow-x:hidden}
  .leadership-header{grid-template-columns:minmax(0,1fr) auto;min-height:58px;padding:0 2px 10px}.leadership-logo{width:38px;height:38px}.leadership-brand h1{font-size:16px}.leadership-brand small{display:none}.leadership-scope{display:none}.leadership-clock>div{display:none}.leadership-clock button{width:42px;height:42px}
  .leadership-filterbar{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:10px 0}.leadership-filterbar>span,.leadership-filterbar>small{grid-column:1/-1;margin:0}.leadership-filterbar select{width:100%;min-width:0;min-height:44px}.leadership-filterbar .leadership-export,.leadership-filterbar .leadership-tv-link{min-height:44px;justify-content:center}
  .leadership-ticker{margin-block:4px 10px}
  .leadership-kpis{grid-template-columns:1fr}.friend-stage-total-grid,.quality-tier-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .ranking-zone-header{align-items:flex-start;flex-direction:column}.rank-board-grid{grid-template-columns:1fr}.rank-champion{grid-template-columns:auto minmax(0,1fr);gap:12px}.champion-value{grid-column:1/-1;justify-self:stretch;text-align:left}
  .leadership-layout{grid-template-columns:1fr}.tech-card{min-width:0}.conversion-card,.task-card,.source-card,.leaderboard-table-card{min-height:0;padding:14px}.tech-title{align-items:flex-start;flex-direction:column}.k-legend{flex-wrap:wrap}
  .k-chart{overflow-x:auto;overscroll-behavior-inline:contain;touch-action:pan-x}.k-chart svg{min-width:720px}
  .funnel-strip{display:grid;grid-template-columns:1fr 1fr;gap:8px}.funnel-strip>i{display:none}
  .task-overview{grid-template-columns:1fr}.task-ring{justify-self:center}.task-status-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .source-list>div{grid-template-columns:30px minmax(0,1fr) 52px}.source-list>div>div{display:none}
  .leadership-table-wrap{max-height:58dvh;overscroll-behavior:contain;touch-action:pan-x pan-y}.leadership-table-wrap table{min-width:720px}
  .leadership-footer{align-items:flex-start;flex-direction:column;gap:4px;padding-inline:2px}
}
@media(max-width:390px){.friend-stage-total-grid,.quality-tier-grid{grid-template-columns:1fr}.leadership-filterbar{grid-template-columns:1fr}.leadership-filterbar>*{grid-column:1}.rank-champion{grid-template-columns:1fr}.champion-orbit{display:none}}
</style>
