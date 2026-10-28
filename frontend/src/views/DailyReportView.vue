<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import DailyExecutionNav from '../components/DailyExecutionNav.vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { useAuthStore } from '../stores/auth'

interface Report {
  id?: string
  membershipId: string
  memberDisplayName: string
  reportDate: string
  grade26NewValidLeads: number
  deepCommunicationCount: number
  pendingDeepCommunicationCount?: number
  performanceManaged?: boolean
  todayIssues: string
  tomorrowPlan: string
  submitted: boolean
}
interface CalendarMember { membershipId: string; displayName: string }
interface CalendarDay {
  date: string
  reportingDay: boolean
  expectedCount: number
  submittedCount: number
  submittedMembers: CalendarMember[]
  missingMembers: CalendarMember[]
}
interface CalendarResponse {
  month: string
  today: string
  expectedMemberCount: number
  days: CalendarDay[]
}
interface CalendarCell { key: string; day?: CalendarDay }

const auth = useAuthStore()
const canManage = computed(() => auth.canViewTeamData)
const today = ref<Report>()
const history = ref<Report[]>([])
const calendar = ref<CalendarResponse>()
const calendarMonth = ref(localMonth(new Date()))
const selectedDate = ref('')
const error = ref('')
const success = ref('')
const calendarBusy = ref(false)
const expandedReports = ref<Set<string>>(new Set())
const form = reactive({ deepCommunicationCount: 0, todayIssues: '', tomorrowPlan: '' })
const weekdays = ['一', '二', '三', '四', '五', '六', '日']

const monthTitle = computed(() => {
  const [year, month] = (calendar.value?.month ?? calendarMonth.value).split('-')
  return `${year} 年 ${Number(month)} 月`
})
const calendarCells = computed<CalendarCell[]>(() => {
  if (!calendar.value) return []
  const [year, month] = calendar.value.month.split('-').map(Number)
  const offset = (new Date(year, month - 1, 1).getDay() + 6) % 7
  const cells: CalendarCell[] = Array.from({ length: offset }, (_, index) => ({ key: `before-${index}` }))
  cells.push(...calendar.value.days.map((day) => ({ key: day.date, day })))
  while (cells.length % 7) cells.push({ key: `after-${cells.length}` })
  return cells
})
const selectedDay = computed(() => calendar.value?.days.find((day) => day.date === selectedDate.value))
const selectedReports = computed(() => history.value.filter((report) => report.reportDate === selectedDate.value))

function localMonth(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}
function formatDate(date: string) {
  const [, month, day] = date.split('-')
  return `${Number(month)} 月 ${Number(day)} 日`
}
function dayNumber(date: string) {
  return Number(date.slice(-2))
}
function reportKey(report: Report) { return report.id ?? `${report.membershipId}:${report.reportDate}` }
function toggleReport(report: Report) {
  const key = reportKey(report)
  const next = new Set(expandedReports.value)
  next.has(key) ? next.delete(key) : next.add(key)
  expandedReports.value = next
}

async function loadCalendar() {
  if (!canManage.value) return
  calendarBusy.value = true
  try {
    const data = await api<CalendarResponse>(`/api/v1/daily-reports/calendar?month=${encodeURIComponent(calendarMonth.value)}`)
    calendar.value = data
    if (!data.days.some((day) => day.date === selectedDate.value)) {
      selectedDate.value = data.days.find((day) => day.date === data.today)?.date
        ?? [...data.days].reverse().find((day) => day.reportingDay)?.date
        ?? data.days[0]?.date
        ?? ''
    }
  } finally {
    calendarBusy.value = false
  }
}
async function load() {
  const [todayData, historyData] = await Promise.all([
    api<Report>('/api/v1/daily-reports/today'),
    api<Report[]>('/api/v1/daily-reports'),
  ])
  today.value = todayData
  history.value = historyData
  Object.assign(form, {
    deepCommunicationCount: todayData.deepCommunicationCount,
    todayIssues: todayData.todayIssues,
    tomorrowPlan: todayData.tomorrowPlan,
  })
  if (canManage.value) {
    calendarMonth.value = todayData.reportDate.slice(0, 7)
    await loadCalendar()
  }
}
async function submit() {
  if (!today.value) return
  error.value = ''
  success.value = ''
  try {
    await api(`/api/v1/daily-reports/${today.value.reportDate}`, { method: 'PUT', body: jsonBody(form) })
    success.value = '今日汇报已提交。'
    await load()
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '每日汇报提交失败'
  }
}
async function moveMonth(offset: number) {
  const [year, month] = calendarMonth.value.split('-').map(Number)
  calendarMonth.value = localMonth(new Date(year, month - 1 + offset, 1))
  await loadCalendar()
}
async function chooseMonth() {
  if (/^\d{4}-\d{2}$/.test(calendarMonth.value)) await loadCalendar()
}

onMounted(() => load().catch(() => { error.value = '每日汇报暂时无法加载' }))
</script>

<template>
  <div :class="['page', { 'narrow-page': !canManage }]">
    <header class="page-header"><div><p class="eyebrow">DAILY REPORT</p><h1>每日汇报</h1><p class="muted">当日录入并由团队长或管理员审核为有效的线索自动计入，无需手工重复填写。</p></div></header>
    <DailyExecutionNav />
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>

    <section v-if="canManage" class="panel report-calendar-panel" data-testid="report-calendar">
      <div class="report-calendar-header">
        <div><p class="eyebrow">SUBMISSION CALENDAR</p><h2>日报提交日历</h2><p class="muted">点击日期查看当天已提交与未提交人员。</p></div>
        <div class="report-calendar-controls">
          <button class="ghost-button" type="button" aria-label="上个月" @click="moveMonth(-1)">‹</button>
          <label>查看月份<input v-model="calendarMonth" type="month" @change="chooseMonth"></label>
          <button class="ghost-button" type="button" aria-label="下个月" @click="moveMonth(1)">›</button>
        </div>
      </div>
      <div class="report-month-summary">
        <strong>{{ monthTitle }}</strong>
        <span>应汇报成员 {{ calendar?.expectedMemberCount ?? 0 }} 人</span>
        <span v-if="calendarBusy">正在更新…</span>
      </div>
      <div class="report-calendar">
        <div v-for="weekday in weekdays" :key="weekday" class="report-calendar-weekday">周{{ weekday }}</div>
        <template v-for="cell in calendarCells" :key="cell.key">
          <button
            v-if="cell.day"
            type="button"
            :class="['report-calendar-day', {
              selected: selectedDate === cell.day.date,
              today: calendar?.today === cell.day.date,
              complete: cell.day.reportingDay && cell.day.expectedCount > 0 && cell.day.submittedCount === cell.day.expectedCount,
              missing: cell.day.reportingDay && cell.day.submittedCount < cell.day.expectedCount,
              inactive: !cell.day.reportingDay,
            }]"
            :data-date="cell.day.date"
            @click="selectedDate = cell.day.date"
          >
            <span>{{ dayNumber(cell.day.date) }}</span>
            <small v-if="cell.day.reportingDay">{{ cell.day.submittedCount }}/{{ cell.day.expectedCount }} 已交</small>
            <small v-else>无需统计</small>
          </button>
          <div v-else class="report-calendar-day blank"></div>
        </template>
      </div>

      <div v-if="selectedDay" class="report-day-detail" data-testid="report-day-detail">
        <header>
          <div><p class="eyebrow">{{ selectedDay.date }}</p><h3>{{ formatDate(selectedDay.date) }}提交情况</h3></div>
          <div v-if="selectedDay.reportingDay" class="report-day-totals">
            <span class="submitted">{{ selectedDay.submittedCount }} 人已提交</span>
            <span class="unsubmitted">{{ selectedDay.missingMembers.length }} 人未提交</span>
          </div>
        </header>
        <div v-if="selectedDay.reportingDay" class="report-member-columns">
          <article>
            <h4>已提交人员</h4>
            <div class="report-member-list submitted">
              <span v-for="member in selectedDay.submittedMembers" :key="member.membershipId">{{ member.displayName }}</span>
              <p v-if="!selectedDay.submittedMembers.length">当天暂无人员提交。</p>
            </div>
          </article>
          <article>
            <h4>未提交人员</h4>
            <div class="report-member-list missing">
              <span v-for="member in selectedDay.missingMembers" :key="member.membershipId">{{ member.displayName }}</span>
              <p v-if="!selectedDay.missingMembers.length">已全部提交。</p>
            </div>
          </article>
        </div>
        <p v-else class="empty-state">该日期尚未到达或不在日报任务周期内，不计入未提交。</p>
        <div v-if="selectedReports.length" class="report-selected-records">
          <strong>当天汇报摘要</strong>
          <span v-for="report in selectedReports" :key="report.id">{{ report.memberDisplayName }} · 有效线索 {{ report.grade26NewValidLeads }} · 深度沟通 {{ report.deepCommunicationCount }}</span>
        </div>
      </div>
    </section>

    <form v-if="today" class="panel report-form" @submit.prevent="submit">
      <div class="panel-title"><div><p class="eyebrow">{{ today.reportDate }}</p><h2>{{ today.submitted ? '更新今日汇报' : '提交今日汇报' }}</h2></div><span class="badge">审核结果实时更新</span></div>
      <label>1. 26级新增有效线索（审核统计）<div class="auto-field"><strong>{{ today.grade26NewValidLeads }}</strong><span>人</span></div></label>
      <label v-if="today.performanceManaged">2. 深度沟通人数（绩效审核统计）
        <div class="auto-field"><strong>{{ today.deepCommunicationCount }}</strong><span>已通过 · {{ today.pendingDeepCommunicationCount ?? 0 }} 条待审核</span></div>
        <small class="muted">绩效启用后由审核通过的深度交流自动生成，不再手工填写。</small>
      </label>
      <label v-else>2. 深度沟通人数<input v-model.number="form.deepCommunicationCount" type="number" min="0" required></label>
      <label>3. 今日问题<textarea v-model.trim="form.todayIssues" rows="5" maxlength="5000" required placeholder="记录今天遇到的问题、阻碍或需要协助的事项"></textarea></label>
      <label>4. 明日计划<textarea v-model.trim="form.tomorrowPlan" rows="5" maxlength="5000" required placeholder="写清明天最重要的行动"></textarea></label>
      <button class="primary-button">{{ today.submitted ? '保存更新' : '提交汇报' }}</button>
    </form>
    <section class="panel report-history"><div class="panel-title"><div><p class="eyebrow">HISTORY</p><h2>汇报记录</h2></div><span class="muted">最近 100 条</span></div><article v-for="report in history" :key="report.id" :class="['report-row', { expanded: expandedReports.has(reportKey(report)) }]"><header><strong>{{ report.memberDisplayName }}</strong><time>{{ report.reportDate }}</time></header><div class="report-numbers"><span>审核有效线索 <b>{{ report.grade26NewValidLeads }}</b></span><span>深度沟通 <b>{{ report.deepCommunicationCount }}</b><small v-if="report.performanceManaged"> 绩效审核口径</small></span></div><p><strong>今日问题</strong><span>{{ report.todayIssues }}</span></p><p><strong>明日计划</strong><span>{{ report.tomorrowPlan }}</span></p><button v-if="report.todayIssues.length + report.tomorrowPlan.length > 120" type="button" class="report-expand" :aria-expanded="expandedReports.has(reportKey(report))" @click="toggleReport(report)">{{ expandedReports.has(reportKey(report)) ? '收起内容' : '展开完整小结' }}</button></article><div v-if="!history.length" class="empty-state">还没有汇报记录。</div></section>
  </div>
</template>

<style scoped>
.report-expand{display:none;color:#3157d5;background:transparent;font-size:12px;font-weight:700}
@media(max-width:700px){
  .report-calendar-panel{padding:16px;overflow:visible}.report-calendar-header{min-width:0;align-items:stretch;flex-direction:column}.report-calendar-controls{display:grid;grid-template-columns:44px minmax(0,1fr) 44px}.report-calendar-controls label{min-width:0}.report-calendar-controls button{min-height:44px}
  .report-month-summary{align-items:flex-start;flex-direction:column;gap:6px}.report-month-summary strong{margin-right:0}
  .report-calendar{min-width:0;grid-template-columns:repeat(7,minmax(0,1fr));border-radius:10px}.report-calendar-weekday{padding:8px 2px;font-size:8px}.report-calendar-day{min-width:0;min-height:52px;align-items:center;gap:4px;padding:5px 2px;text-align:center}.report-calendar-day span{width:27px;height:27px}.report-calendar-day small{display:none}.report-calendar-day.missing::after,.report-calendar-day.complete::after{width:5px;height:5px;border-radius:50%;content:""}.report-calendar-day.missing::after{background:#dc5867}.report-calendar-day.complete::after{background:#28a477}
  .report-day-detail{min-width:0;padding:14px}.report-day-detail>header{flex-direction:column}.report-day-totals{width:100%}.report-day-totals span{flex:1;text-align:center}.report-member-columns{grid-template-columns:1fr}.report-member-columns article{min-height:0}
  .report-form{padding:18px}.report-form .panel-title{align-items:flex-start;flex-direction:column}.report-form>button{width:100%;min-height:46px;justify-self:stretch}.report-form textarea{font-size:16px}
  .report-row>p{grid-template-columns:1fr;gap:4px}.report-row>p span{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow-wrap:anywhere}.report-row.expanded>p span{display:block;-webkit-line-clamp:unset}.report-expand{display:inline-flex;min-height:44px;align-items:center;padding:0}
}
</style>
