<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CustomerCard from '../components/CustomerCard.vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { consumeTeamDataResume, rememberTeamDataPosition } from '../lib/teamDataNavigation'
import type { Customer, PageResponse } from '../types'

interface TeamOption { id: string; name: string }
interface MemberOption { membershipId: string; displayName: string; username: string; teamNames: string }
interface ReportItem { id: string; membershipId: string; memberDisplayName: string; reportDate: string; grade26NewValidLeads: number; deepCommunicationCount: number; todayIssues: string; tomorrowPlan: string }
interface ContentProgress { membershipId: string; displayName: string; xiaohongshuValid: number; douyinValid: number }
interface LeadReviewItem { customerId: string; fullName: string; ownerDisplayName: string; creatorDisplayName: string; status: 'PENDING' | 'VALID' | 'INVALID'; createdAt: string }
interface Overview {
  teams: TeamOption[]; members: MemberOption[]
  stats: { memberCount: number; activeCustomerCount: number; todayNewCustomerCount: number; todayGrade26LeadCount: number; pendingLeadReviewCount: number; todayReportCount: number; xiaohongshuValidCount: number; douyinValidCount: number }
  dailyReports: ReportItem[]; contentProgress: ContentProgress[]; leadReviewQueue: LeadReviewItem[]; administrator: boolean
}

const route = useRoute()
const router = useRouter()
const queryString = (key: string) => typeof route.query[key] === 'string' ? String(route.query[key]) : ''
const queryTab = queryString('tab')
const queryView = queryString('view')
const overview = ref<Overview>()
const customers = ref<Customer[]>([])
const customerTotal = ref(0)
const customerTotalPages = ref(0)
const teamId = ref(queryString('teamId'))
const membershipId = ref(queryString('membershipId'))
const search = ref(queryString('search'))
const grade = ref(queryString('grade'))
const initialPage = Number.parseInt(queryString('page'), 10)
const customerPage = ref(Number.isFinite(initialPage) && initialPage > 0 ? initialPage : 0)
const gradeOptions = ['2024', '2025', '2026', '2027', '2028']
const tab = ref<'customers' | 'reviews' | 'reports' | 'content'>(
  ['customers', 'reviews', 'reports', 'content'].includes(queryTab)
    ? queryTab as 'customers' | 'reviews' | 'reports' | 'content'
    : 'customers',
)
const customerView = ref<'table' | 'cards'>(queryView === 'cards' ? 'cards' : 'table')
const error = ref('')
const savedNotice = ref('')
const loading = ref(false)
const metricsExpanded = ref(false)
const refreshedAt = ref<Date>()
const reviewReasons = reactive<Record<string, string>>({})
let searchTimer: number | undefined
let loadSequence = 0

function sheetTone(value?: string) {
  if (!value) return 'sheet-pill-gray'
  const tones = ['mint', 'rose', 'blue', 'amber', 'violet']
  const score = [...value].reduce((sum, character) => sum + character.charCodeAt(0), 0)
  return `sheet-pill-${tones[score % tones.length]}`
}

function shortDate(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date(value))
}

function followUpSummary(customer: Customer) {
  return customer.ownerNotes || customer.demandSummary || customer.currentStatus || '暂无跟进记录'
}

function query(includeSearch = false) {
  const params = new URLSearchParams()
  if (teamId.value) params.set('teamId', teamId.value)
  if (membershipId.value) params.set('membershipId', membershipId.value)
  if (includeSearch && search.value) params.set('search', search.value)
  if (includeSearch && grade.value) params.set('grade', grade.value)
  return params
}

function stateQuery() {
  const params: Record<string, string> = { tab: tab.value, view: customerView.value }
  if (teamId.value) params.teamId = teamId.value
  if (membershipId.value) params.membershipId = membershipId.value
  if (search.value) params.search = search.value
  if (grade.value) params.grade = grade.value
  if (customerPage.value > 0) params.page = String(customerPage.value)
  return params
}

const returnPath = computed(() => {
  const params = new URLSearchParams(stateQuery())
  return `/team-data?${params.toString()}`
})

function syncRouteState() {
  if (route.fullPath !== returnPath.value) {
    void router.replace({ path: '/team-data', query: stateQuery() })
  }
}

function customerDetailLocation(customerId: string) {
  return { path: `/customers/${customerId}`, query: { returnTo: returnPath.value } }
}

function rememberReturnPosition() {
  syncRouteState()
  rememberTeamDataPosition(returnPath.value)
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true; error.value = ''
  try {
    const overviewParams = query(true)
    const customerParams = query(true)
    customerParams.set('page', String(customerPage.value))
    customerParams.set('size', '50')
    customerParams.set('sort', 'updatedDesc')
    const [summary, customerResult] = await Promise.all([
      api<Overview>(`/api/v1/team-data/overview?${overviewParams}`),
      api<PageResponse<Customer>>(`/api/v1/customers?${customerParams}`),
    ])
    if (sequence !== loadSequence) return
    if (customerResult.totalPages > 0 && customerResult.page >= customerResult.totalPages) {
      customerPage.value = customerResult.totalPages - 1
      syncRouteState()
      void load()
      return
    }
    overview.value = summary
    customers.value = customerResult.items
    customerTotal.value = customerResult.totalElements
    customerTotalPages.value = customerResult.totalPages
    refreshedAt.value = new Date()
  } catch { error.value = '团队数据暂时无法加载' }
  finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function loadAndRestore() {
  syncRouteState()
  await load()
  const resume = consumeTeamDataResume(returnPath.value)
  if (!resume) return
  savedNotice.value = resume.savedMessage ?? ''
  if (resume.scrollY > 0) {
    await nextTick()
    window.requestAnimationFrame(() => window.scrollTo({ top: resume.scrollY, behavior: 'auto' }))
  }
}

async function reviewLead(item: LeadReviewItem, status: 'VALID' | 'INVALID') {
  error.value = ''
  try {
    await api(`/api/v1/customers/${item.customerId}/lead-review`, {
      method: 'PATCH', body: jsonBody({ status, reason: reviewReasons[item.customerId] || undefined }),
    })
    delete reviewReasons[item.customerId]
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '线索审核失败' }
}

function goCustomerPage(page: number) {
  if (page < 0 || page >= customerTotalPages.value || page === customerPage.value) return
  customerPage.value = page
  syncRouteState()
  void load()
}

function refreshedLabel() {
  if (!refreshedAt.value) return '尚未更新'
  return `更新于 ${refreshedAt.value.toLocaleTimeString('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })}`
}

function reloadOnFocus() {
  void load()
}

watch(teamId, () => { membershipId.value = '' })
watch([teamId, membershipId, grade], () => {
  customerPage.value = 0
  syncRouteState()
  void load()
})
watch([tab, customerView], syncRouteState)
watch(search, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    customerPage.value = 0
    syncRouteState()
    void load()
  }, 180)
})
onMounted(() => {
  if (!queryView && window.matchMedia?.('(max-width: 700px)').matches) customerView.value = 'cards'
  window.addEventListener('focus', reloadOnFocus)
  void loadAndRestore()
})
onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  window.removeEventListener('focus', reloadOnFocus)
})
</script>

<template>
  <div class="page team-data-page">
    <header class="page-header">
      <div><p class="eyebrow">TEAM INTELLIGENCE</p><h1>{{ overview?.administrator ? '全组织数据' : '团队数据' }}</h1><p class="muted">客户、每日汇报和内容任务均按服务端权限范围统计。</p></div>
      <div class="team-data-refresh">
        <small>{{ refreshedLabel() }}</small>
        <button class="ghost-button" type="button" :disabled="loading" @click="load">{{ loading ? '正在同步…' : '刷新数据' }}</button>
      </div>
    </header>
    <p v-if="savedNotice" class="notice success" data-testid="team-data-saved-notice">{{ savedNotice }}</p>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section class="panel team-data-filters">
      <label>团队<select v-model="teamId"><option value="">{{ overview?.administrator ? '全部团队' : '我负责的全部团队' }}</option><option v-for="team in overview?.teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label>
      <label>成员<select v-model="membershipId"><option value="">全部成员</option><option v-for="member in overview?.members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }} · {{ member.teamNames || '未分组' }}</option></select></label>
      <label>客户搜索<input v-model.trim="search" placeholder="姓名、学校或联系方式"></label>
      <div class="grade-quick-filter embedded" data-testid="grade-filter" aria-label="按客户年级快速筛选">
        <span>客户年级</span>
        <div>
          <button :class="{ active: !grade }" type="button" @click="grade = ''">全部</button>
          <button v-for="option in gradeOptions" :key="option" :class="{ active: grade === option }" type="button" @click="grade = option">{{ option }}</button>
        </div>
        <small>客户搜索和年级会同步刷新客户统计、审核队列与下方清单</small>
      </div>
    </section>
    <div v-if="overview" :class="['metric-grid', 'team-metrics', { expanded: metricsExpanded }]">
      <article><span>范围内成员</span><strong>{{ overview.stats.memberCount }}</strong><small>当前筛选</small></article>
      <article><span>活跃客户</span><strong data-testid="active-customer-count">{{ overview.stats.activeCustomerCount }}</strong><small>与客户筛选一致</small></article>
      <article class="warm"><span>今日新增客户</span><strong>{{ overview.stats.todayNewCustomerCount }}</strong><small>北京时间</small></article>
      <article><span>今日有效线索</span><strong>{{ overview.stats.todayGrade26LeadCount }}</strong><small>团队长/管理员审核</small></article>
      <article class="warm"><span>待审核线索</span><strong>{{ overview.stats.pendingLeadReviewCount }}</strong><small>当前范围</small></article>
      <article><span>今日日报</span><strong>{{ overview.stats.todayReportCount }}</strong><small>已提交</small></article>
      <article><span>有效内容</span><strong>{{ overview.stats.xiaohongshuValidCount + overview.stats.douyinValidCount }}</strong><small>小红书 {{ overview.stats.xiaohongshuValidCount }} · 抖音 {{ overview.stats.douyinValidCount }}</small></article>
    </div>
    <button v-if="overview" type="button" class="mobile-kpi-toggle" :aria-expanded="metricsExpanded" @click="metricsExpanded = !metricsExpanded">{{ metricsExpanded ? '收起经营指标' : '展开全部 7 项指标' }}</button>
    <div class="segmented-tabs" role="tablist">
      <button :class="{ active: tab === 'customers' }" @click="tab = 'customers'">客户 {{ customerTotal }}</button>
      <button :class="{ active: tab === 'reviews' }" @click="tab = 'reviews'">线索审核 {{ overview?.stats.pendingLeadReviewCount || 0 }}</button>
      <button :class="{ active: tab === 'reports' }" @click="tab = 'reports'">每日汇报 {{ overview?.dailyReports.length || 0 }}</button>
      <button :class="{ active: tab === 'content' }" @click="tab = 'content'">内容任务进度</button>
    </div>
    <section v-if="tab === 'customers'" class="team-data-section">
      <div class="team-customer-view-toolbar">
        <div><strong>客户明细</strong><small>共 {{ customerTotal }} 位，当前第 {{ customerPage + 1 }} / {{ Math.max(customerTotalPages, 1) }} 页</small></div>
        <div class="view-switch" data-testid="team-customer-view-switch" aria-label="团队客户展示模式">
          <button type="button" :class="{ active: customerView === 'table' }" :aria-pressed="customerView === 'table'" @click="customerView = 'table'">表格</button>
          <button type="button" :class="{ active: customerView === 'cards' }" :aria-pressed="customerView === 'cards'" @click="customerView = 'cards'">卡片</button>
        </div>
      </div>
      <div v-if="customers.length && customerView === 'table'" class="panel table-wrap team-customer-table-wrap" data-testid="team-customer-table">
        <table class="team-customer-table">
          <thead><tr><th>姓名</th><th>院校</th><th>专业 / 年级</th><th>创建时间</th><th>流量来源</th><th>负责人</th><th>QQ</th><th>微信 / 手机</th><th>报班意愿</th><th>跟进记录</th><th>操作</th></tr></thead>
          <tbody><tr v-for="customer in customers" :key="customer.id">
            <td><RouterLink :to="customerDetailLocation(customer.id)" @click="rememberReturnPosition"><strong :title="customer.fullName">{{ customer.fullName }}</strong></RouterLink></td>
            <td><span :class="['sheet-pill', sheetTone(customer.school)]">{{ customer.school || '院校未填' }}</span></td>
            <td><div class="sheet-stack"><span :class="['sheet-pill', sheetTone(customer.major)]">{{ customer.major || '专业未填' }}</span><small>{{ customer.grade || '年级未填' }}</small></div></td>
            <td class="sheet-date">{{ shortDate(customer.createdAt) }}</td>
            <td><span :class="['sheet-pill', sheetTone(customer.sourceChannel)]">{{ customer.sourceChannel || '来源未填' }}</span></td>
            <td><span :class="['sheet-pill', sheetTone(customer.ownerDisplayName)]">{{ customer.ownerDisplayName || '负责人未知' }}</span></td>
            <td class="sheet-contact" :title="customer.qqId">{{ customer.qqId || '—' }}</td>
            <td class="sheet-contact" :title="[customer.wechatId, customer.phone].filter(Boolean).join(' / ')">{{ customer.wechatId || customer.phone || '—' }}</td>
            <td><span :class="['sheet-pill', sheetTone(customer.intentionLevel)]">{{ customer.intentionLevel || '未知' }}</span></td>
            <td class="sheet-follow-up" :title="followUpSummary(customer)">{{ followUpSummary(customer) }}</td>
            <td><RouterLink :to="customerDetailLocation(customer.id)" class="text-button" @click="rememberReturnPosition">查看</RouterLink></td>
          </tr></tbody>
        </table>
      </div>
      <div v-else-if="customers.length" class="card-grid customer-list" @click.capture="rememberReturnPosition"><CustomerCard v-for="customer in customers" :key="customer.id" :customer="customer" :to="customerDetailLocation(customer.id)" /></div>
      <div v-else-if="!loading" class="empty-state panel">当前范围没有客户。</div>
      <div v-if="customerTotalPages > 1" class="pagination" data-testid="team-customer-pagination">
        <button type="button" :disabled="customerPage <= 0 || loading" @click="goCustomerPage(customerPage - 1)">上一页</button>
        <span>{{ customerPage + 1 }} / {{ customerTotalPages }} · 共 {{ customerTotal }} 位</span>
        <button type="button" :disabled="customerPage >= customerTotalPages - 1 || loading" @click="goCustomerPage(customerPage + 1)">下一页</button>
      </div>
    </section>
    <section v-if="tab === 'reviews'" class="panel review-panel team-data-section">
      <div class="panel-title"><div><p class="eyebrow">LEAD REVIEW</p><h2>待审核有效线索</h2></div><span>{{ overview?.leadReviewQueue.length || 0 }} 条</span></div>
      <article v-for="item in overview?.leadReviewQueue" :key="item.customerId" class="review-row">
        <div><RouterLink :to="customerDetailLocation(item.customerId)" @click="rememberReturnPosition"><strong>{{ item.fullName }}</strong></RouterLink><small>负责人：{{ item.ownerDisplayName }} · 录入人：{{ item.creatorDisplayName }} · {{ new Date(item.createdAt).toLocaleString() }}</small></div>
        <input v-model.trim="reviewReasons[item.customerId]" placeholder="判定无效时填写原因">
        <button class="soft-button" @click="reviewLead(item, 'VALID')">审核有效</button>
        <button class="ghost-button" @click="reviewLead(item, 'INVALID')">判定无效</button>
      </article>
      <div v-if="!overview?.leadReviewQueue.length" class="empty-state small">没有待审核线索。</div>
    </section>
    <section v-if="tab === 'reports'" class="panel report-history team-data-section">
      <article v-for="report in overview?.dailyReports" :key="report.id" class="report-row"><header><strong>{{ report.memberDisplayName }}</strong><time>{{ report.reportDate }}</time></header><div class="report-numbers"><span>审核有效线索 <b>{{ report.grade26NewValidLeads }}</b></span><span>深度沟通 <b>{{ report.deepCommunicationCount }}</b></span></div><p><strong>今日问题</strong>{{ report.todayIssues }}</p><p><strong>明日计划</strong>{{ report.tomorrowPlan }}</p></article>
      <div v-if="!overview?.dailyReports.length" class="empty-state">当前范围没有日报。</div>
    </section>
    <section v-if="tab === 'content'" class="panel team-data-section table-wrap">
      <table><thead><tr><th>成员</th><th>小红书有效</th><th>抖音有效</th><th>合计</th></tr></thead><tbody><tr v-for="member in overview?.contentProgress" :key="member.membershipId"><td>{{ member.displayName }}</td><td>{{ member.xiaohongshuValid }}/20</td><td>{{ member.douyinValid }}/20</td><td>{{ member.xiaohongshuValid + member.douyinValid }}</td></tr></tbody></table>
      <div v-if="!overview?.contentProgress.length" class="empty-state">当前范围没有成员进度。</div>
    </section>
  </div>
</template>

<style scoped>
.mobile-kpi-toggle{display:none}
@media(max-width:700px){
  .page-header{align-items:stretch}.team-data-refresh{display:grid;grid-template-columns:1fr auto}.team-data-refresh .ghost-button{min-height:44px}
  .team-data-filters{grid-template-columns:1fr}.team-data-filters .grade-quick-filter{grid-column:auto}.grade-quick-filter>div{display:flex;overflow-x:auto;scrollbar-width:none}.grade-quick-filter button{flex:0 0 auto;min-height:42px}
  .metric-grid.team-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;overflow:visible}.team-metrics article{min-height:104px;padding:16px}.team-metrics article strong{font-size:25px}.team-metrics:not(.expanded) article:nth-child(n+5){display:none}
  .mobile-kpi-toggle{display:flex;width:100%;min-height:44px;align-items:center;justify-content:center;margin:-8px 0 18px;color:#3157d5;border:1px solid #dce4f8;border-radius:12px;background:#f7f9ff;font-size:12px;font-weight:700}
  .segmented-tabs{display:flex;width:100%;overflow-x:auto;scrollbar-width:none}.segmented-tabs button{flex:0 0 auto;min-height:44px}
  .team-customer-view-toolbar{align-items:flex-start;flex-direction:column}.team-customer-view-toolbar .view-switch{display:grid;grid-template-columns:1fr 1fr;width:100%}.team-customer-view-toolbar button{min-height:44px}
  .team-customer-table-wrap{max-width:100%;overscroll-behavior-inline:contain;touch-action:pan-x pan-y}.team-customer-table{min-width:1420px}
  .review-row{display:grid;grid-template-columns:1fr;gap:10px}.review-row input,.review-row button{width:100%;min-height:44px}
  .report-row>p{grid-template-columns:1fr;gap:4px;overflow-wrap:anywhere}
  .team-data-section.table-wrap{overflow-x:auto;overscroll-behavior-inline:contain}.team-data-section.table-wrap table{min-width:420px}
  .pagination{display:grid;grid-template-columns:1fr auto 1fr}.pagination button{min-height:44px}
}
</style>
