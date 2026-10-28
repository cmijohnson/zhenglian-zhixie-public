<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import CustomerCard from '../components/CustomerCard.vue'
import { ApiError, api } from '../lib/api'
import { useAuthStore } from '../stores/auth'
import type { Customer, FollowUpRecruitment } from '../types'

interface Dashboard {
  stats: { totalActive: number; archivedCount: number; todayDueCount: number; overdueCount: number; highIntentionCount: number }
  dueToday: Customer[]
  overdue: Customer[]
  staleHighIntention: Customer[]
  recentUpdated: Customer[]
}

const data = ref<Dashboard>()
const auth = useAuthStore()
const recruitments = ref<FollowUpRecruitment[]>([])
const error = ref('')
const recruitmentBusy = ref('')
onMounted(async () => {
  try {
    const [dashboard, loadedRecruitments] = await Promise.all([
      api<Dashboard>('/api/v1/dashboard'),
      auth.canWriteCustomers ? api<FollowUpRecruitment[]>('/api/v1/follow-up-recruitments') : Promise.resolve([]),
    ])
    data.value = dashboard
    recruitments.value = loadedRecruitments
  }
  catch { error.value = '工作台暂时无法加载' }
})

async function claim(item: FollowUpRecruitment) {
  if (!window.confirm(`确认加入“${item.fullName}”的共同跟进？领取后可编辑同一份客户档案与时间线。`)) return
  recruitmentBusy.value = item.id
  try {
    await api(`/api/v1/follow-up-recruitments/${item.id}/claim`, { method: 'POST' })
    recruitments.value = await api<FollowUpRecruitment[]>('/api/v1/follow-up-recruitments')
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '领取失败，请刷新后重试'
  } finally { recruitmentBusy.value = '' }
}
</script>

<template>
  <div class="page page-dashboard">
    <header class="page-header hero-header">
      <div><p class="eyebrow">今日业务概览</p><h1>工作台</h1><p class="muted">优先处理到期跟进，再回看近期有变化的客户。</p></div>
      <RouterLink to="/customers/new" class="primary-button">＋ 快速建档</RouterLink>
    </header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <div v-if="data" class="metric-grid">
      <article><span>活跃客户</span><strong>{{ data.stats.totalActive }}</strong><small>当前可见范围</small></article>
      <article class="warm"><span>今日待跟进</span><strong>{{ data.stats.todayDueCount }}</strong><small>请及时处理</small></article>
      <article class="danger"><span>已经逾期</span><strong>{{ data.stats.overdueCount }}</strong><small>优先补充记录</small></article>
      <article><span>高意向客户</span><strong>{{ data.stats.highIntentionCount }}</strong><small>持续保持联系</small></article>
    </div>
    <div v-if="data" class="dashboard-grid">
      <section v-if="recruitments.length" class="panel span-3 recruitment-panel">
        <div class="panel-title"><div><p class="eyebrow">A-CLASS COLLABORATION</p><h2>重点客户协作招募</h2><p class="muted">A 类客户至少两人共同跟进。领取前可核对完整业务画像和近期时间线。</p></div><span>{{ recruitments.length }} 个待领取</span></div>
        <div class="recruitment-grid"><article v-for="item in recruitments" :key="item.id" class="recruitment-card"><header><span class="avatar">{{ item.fullName.slice(0,1) }}</span><div><h3>{{ item.fullName }}</h3><small>{{ item.school || '学校未填' }} · {{ item.major || '专业未填' }} · {{ item.grade || '年级未填' }}</small></div></header><dl><div><dt>负责人</dt><dd>{{ item.ownerDisplayName }}</dd></div><div><dt>联系方式</dt><dd>{{ item.phone || item.wechatId || item.qqId || '未填' }}</dd></div><div><dt>高中 / 生源地</dt><dd>{{ item.highSchool || '未填' }} / {{ item.sourceLocation || '未填' }}</dd></div><div><dt>招募进度</dt><dd>{{ item.claimedCount }}/{{ item.requiredCount }}</dd></div></dl><p>{{ item.demandSummary || '需求摘要尚未补充。' }}</p><details><summary>查看近期时间线</summary><div v-if="!item.recentTimeline.length" class="empty-state small">暂无沟通记录</div><div v-for="timeline in item.recentTimeline" :key="timeline.contactedAt + timeline.summary" class="mini-timeline"><strong>{{ timeline.channel }} · {{ timeline.summary }}</strong><small>{{ new Date(timeline.contactedAt).toLocaleString() }}</small><p>{{ timeline.details || timeline.outcome }}</p></div></details><footer><RouterLink :to="`/customers/${item.customerId}`" class="ghost-button">查看档案</RouterLink><button class="primary-button" :disabled="recruitmentBusy === item.id" @click="claim(item)">{{ recruitmentBusy === item.id ? '领取中…' : '领取协作' }}</button></footer></article></div>
      </section>
      <section class="panel span-2">
        <div class="panel-title"><div><p class="eyebrow">FOLLOW UP</p><h2>今日与逾期</h2></div><RouterLink to="/reminders">查看提醒 →</RouterLink></div>
        <div v-if="!data.dueToday.length && !data.overdue.length" class="empty-state">今天没有需要处理的跟进。</div>
        <div class="card-grid compact">
          <CustomerCard v-for="customer in [...data.overdue, ...data.dueToday].slice(0, 8)" :key="customer.id" :customer="customer" />
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">WATCHLIST</p><h2>高意向沉默客户</h2></div></div>
        <div v-if="!data.staleHighIntention.length" class="empty-state small">暂无需要提醒的客户。</div>
        <RouterLink v-for="customer in data.staleHighIntention" :key="customer.id" :to="`/customers/${customer.id}`" class="mini-row">
          <span class="avatar small">{{ customer.fullName.slice(0, 1) }}</span><span><strong>{{ customer.fullName }}</strong><small>{{ customer.school || customer.sourceChannel || '未补充来源' }}</small></span><b>高</b>
        </RouterLink>
      </section>
      <section class="panel span-3">
        <div class="panel-title"><div><p class="eyebrow">RECENT</p><h2>近期更新</h2></div><RouterLink to="/customers">全部客户 →</RouterLink></div>
        <div class="card-grid"><CustomerCard v-for="customer in data.recentUpdated" :key="customer.id" :customer="customer" /></div>
      </section>
    </div>
    <div v-else-if="!error" class="loading-state">正在整理今日工作…</div>
  </div>
</template>

<style scoped>
.recruitment-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.recruitment-card{min-width:0;padding:20px;border:1px solid #e0e7f4;border-radius:16px;background:#fbfcff}.recruitment-card header{display:flex;align-items:center;gap:12px}.recruitment-card h3{margin:0 0 4px}.recruitment-card dl{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:18px 0}.recruitment-card dl div{display:grid;min-width:0;gap:3px}.recruitment-card dt,.recruitment-card small{color:#78839a}.recruitment-card dd{margin:0;overflow-wrap:anywhere}.recruitment-card footer{display:flex;justify-content:flex-end;gap:10px;margin-top:16px}.mini-timeline{padding:10px 0;border-bottom:1px solid #e7ebf3}.mini-timeline p{margin:5px 0 0;overflow-wrap:anywhere}
@media(max-width:900px){.recruitment-grid{grid-template-columns:1fr}}
@media(max-width:1500px){.page-dashboard .card-grid{grid-template-columns:1fr}}
@media(max-width:640px){.hero-header{align-items:stretch}.hero-header>.primary-button{width:100%}.page-dashboard :deep(.metric-grid){grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.page-dashboard :deep(.metric-grid article){min-height:104px;padding:16px}.page-dashboard :deep(.metric-grid article strong){font-size:26px}.dashboard-grid{grid-template-columns:1fr}.dashboard-grid>.panel{grid-column:auto}.recruitment-card{padding:16px}.recruitment-card dl{grid-template-columns:1fr}.recruitment-card footer{display:grid;grid-template-columns:1fr 1fr}.recruitment-card footer>*{width:100%;min-height:44px}.panel-title{align-items:flex-start}.card-grid{grid-template-columns:1fr}}
</style>
