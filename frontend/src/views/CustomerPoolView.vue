<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime, idempotencyKey } from '../lib/format'

interface MaskedTimeline { occurredAt: string; channel: string; description: string }
interface PoolEntry { id: string; customerId: string; poolType: 'AB' | 'C'; releaseReason: string; customerName: string; school?: string; grade?: string; source?: string; demandSummary?: string; timeline: MaskedTimeline[]; availableAt: string; priorityUntil?: string; canClaim: boolean; status: string }
interface Recruitment { id: string; customerId: string; customerName: string; school?: string; grade?: string; demandSummary?: string; requiredCount: number; claimedCount: number; volunteerUntil: string; status: string }
interface ClaimResult { customerId: string; status: string; expiresAt: string; idempotentReplay: boolean }

const type = ref<'AB' | 'C'>('AB')
const rows = ref<PoolEntry[]>([])
const recruitments = ref<Recruitment[]>([])
const loading = ref(false)
const busyId = ref('')
const error = ref('')
const notice = ref('')
const expandedCards = ref<Record<string, boolean>>({})

function toggleCard(key: string) {
  expandedCards.value[key] = !expandedCards.value[key]
}

function visibleTimeline(item: PoolEntry) {
  return expandedCards.value[`pool-${item.id}`] ? item.timeline : item.timeline.slice(0, 2)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [poolRows, recruitmentRows] = await Promise.all([
      api<PoolEntry[]>(`/api/v1/customer-pools?type=${type.value}`),
      api<Recruitment[]>('/api/v1/second-line-recruitments'),
    ])
    rows.value = poolRows
    recruitments.value = recruitmentRows
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '公共池加载失败' }
  finally { loading.value = false }
}

async function claim(item: PoolEntry) {
  if (!item.canClaim) return
  busyId.value = item.id; error.value = ''; notice.value = ''
  try {
    const result = await api<ClaimResult>(`/api/v1/customer-pools/${item.id}/claims`, { method: 'POST', body: jsonBody({ idempotencyKey: idempotencyKey() }) })
    notice.value = `已获得 24 小时临时跟进权，请在 ${dateTime(result.expiresAt)} 前完成首次有效行动。`
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '认领失败，该客户可能已被其他成员认领' }
  finally { busyId.value = '' }
}

async function claimSecondLine(item: Recruitment) {
  busyId.value = item.id; error.value = ''; notice.value = ''
  try {
    await api(`/api/v1/second-line-recruitments/${item.id}/claim`, { method: 'POST' })
    notice.value = '已成为 A 类客户二线跟进人。直接联系客户前，请在客户档案中申请授权。'
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '二线领取失败' }
  finally { busyId.value = '' }
}

onMounted(load)
</script>

<template>
  <div class="page customer-pool-page">
    <header class="hero-header"><div><p class="eyebrow">CUSTOMER OWNERSHIP</p><h1>客户公共池</h1><p>系统时间戳是唯一归属凭证。认领前展示客户姓名、简要概述和脱敏时间线，成功后才开放联系方式与完整档案。</p></div><button class="ghost-button" :disabled="loading" @click="load">刷新</button></header>
    <p v-if="error" class="form-error">{{ error }}</p><p v-if="notice" class="success-banner">{{ notice }}</p>
    <section v-if="recruitments.length" class="panel recruitment-panel"><div class="panel-title"><div><p class="eyebrow">SECOND LINE</p><h2>A 类二线招募</h2><p class="muted">先开放 24 小时自愿领取，无人领取后转管理员指定。</p></div></div><div class="recruitment-grid"><article v-for="item in recruitments" :key="item.id"><span class="pool-tag">A 类协作</span><h3>{{ item.customerName }}</h3><p class="customer-meta">{{ item.school || '学校未核实' }} · {{ item.grade || '年级未核实' }}</p><div class="profile-summary"><strong>简要概述</strong><p :class="{ expanded: expandedCards[`recruitment-${item.id}`] }">{{ item.demandSummary || '需求摘要待补充' }}</p><button v-if="(item.demandSummary?.length ?? 0) > 90" type="button" class="content-toggle" :aria-expanded="Boolean(expandedCards[`recruitment-${item.id}`])" @click="toggleCard(`recruitment-${item.id}`)">{{ expandedCards[`recruitment-${item.id}`] ? '收起概述' : '展开概述' }}</button></div><small>已领取 {{ item.claimedCount }}/{{ item.requiredCount }} · 自愿窗口至 {{ dateTime(item.volunteerUntil) }}</small><button class="primary-button" :disabled="busyId === item.id" @click="claimSecondLine(item)">{{ busyId === item.id ? '处理中…' : '领取二线' }}</button></article></div></section>
    <section class="panel pool-panel"><div class="panel-title"><div><p class="eyebrow">PUBLIC POOL</p><h2>待认领客户</h2></div><div class="segmented"><button :class="{ active: type === 'AB' }" @click="type = 'AB'; load()">A/B 人工释放池</button><button :class="{ active: type === 'C' }" @click="type = 'C'; load()">C 类激活池</button></div></div>
      <div v-if="loading" class="empty-state">正在加载…</div><div v-else-if="!rows.length" class="empty-state">当前公共池没有可认领客户。</div>
      <div v-else class="pool-grid"><article v-for="item in rows" :key="item.id"><header><span class="pool-tag">{{ item.poolType }} 池</span><small>{{ item.releaseReason }}</small></header><h3>{{ item.customerName }}</h3><p class="customer-meta">{{ item.school || '学校未核实' }} · {{ item.grade || '年级未核实' }}</p><p class="source">来源：{{ item.source || '待核实' }}</p><div class="profile-summary"><strong>简要概述</strong><p :class="{ expanded: expandedCards[`pool-${item.id}`] }">{{ item.demandSummary || '需求摘要待补充' }}</p></div><div class="masked-timeline"><strong>脱敏时间线</strong><span v-for="timeline in visibleTimeline(item)" :key="timeline.occurredAt + timeline.channel">{{ dateTime(timeline.occurredAt) }} · {{ timeline.channel }} · {{ timeline.description }}</span><small v-if="!item.timeline.length">暂无可展示信息</small><button v-if="item.timeline.length > 2 || (item.demandSummary?.length ?? 0) > 90" type="button" class="content-toggle" :aria-expanded="Boolean(expandedCards[`pool-${item.id}`])" @click="toggleCard(`pool-${item.id}`)">{{ expandedCards[`pool-${item.id}`] ? '收起客户概述' : `展开完整概述${item.timeline.length > 2 ? `与 ${item.timeline.length} 条时间线` : ''}` }}</button></div><footer><small>开放时间 {{ dateTime(item.availableAt) }}<template v-if="item.priorityUntil"> · 原负责人优先至 {{ dateTime(item.priorityUntil) }}</template></small><button class="primary-button" :disabled="!item.canClaim || busyId === item.id" @click="claim(item)">{{ busyId === item.id ? '认领中…' : item.canClaim ? '获取临时跟进权' : '当前不可认领' }}</button></footer></article></div>
    </section>
  </div>
</template>

<style scoped>
.customer-pool-page{max-width:1500px}.success-banner{padding:14px 18px;border:1px solid #bde4ce;border-radius:14px;background:#effaf4;color:#216e45}.recruitment-panel,.pool-panel{display:grid;gap:20px}.recruitment-grid,.pool-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.recruitment-grid article,.pool-grid article{display:grid;gap:12px;padding:20px;border:1px solid #e1e7f2;border-radius:20px;background:linear-gradient(145deg,#fff,#f8faff)}.recruitment-grid h3,.pool-grid h3{margin:0}.recruitment-grid p,.pool-grid p{margin:0;color:#59657a;line-height:1.6;overflow-wrap:anywhere}.profile-summary p:not(.expanded){display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:3}.customer-meta{font-size:13px;font-weight:650;color:#78849a!important}.profile-summary{display:grid;gap:5px;padding:12px 14px;border-radius:14px;background:#f2f5fb}.profile-summary strong{font-size:12px;color:#3157bd}.pool-tag{width:max-content;padding:6px 10px;border-radius:999px;background:#eaf0ff;color:#3259c5;font-size:12px;font-weight:750}.pool-grid article>header{display:flex;justify-content:space-between;align-items:center}.source{font-size:13px}.masked-timeline{display:grid;gap:6px;padding:12px;border-radius:12px;background:#f1f4f9}.masked-timeline span,.masked-timeline small{font-size:12px;color:#6e798e;overflow-wrap:anywhere}.content-toggle{width:max-content;min-height:34px;padding:3px 0;border:0;background:transparent;color:#3159c7;font-size:12px;font-weight:750}.pool-grid footer{display:grid;gap:12px;margin-top:auto}.segmented{display:flex;padding:4px;border-radius:12px;background:#eef2f8}.segmented button{border:0;padding:9px 13px;border-radius:9px;background:transparent;color:#778197}.segmented button.active{background:#fff;color:#244eb7;box-shadow:0 4px 12px #233c6b18}@media(max-width:1050px){.recruitment-grid,.pool-grid{grid-template-columns:1fr 1fr}}@media(max-width:700px){.customer-pool-page{padding-inline:14px}.hero-header{align-items:flex-start;flex-direction:column}.hero-header .ghost-button{width:100%;min-height:44px}.recruitment-panel,.pool-panel{padding:16px}.recruitment-grid,.pool-grid{grid-template-columns:1fr}.recruitment-grid article,.pool-grid article{padding:17px}.pool-panel>.panel-title{align-items:flex-start;flex-direction:column}.segmented{display:grid;grid-template-columns:1fr 1fr;width:100%}.segmented button{min-height:44px}.recruitment-grid .primary-button,.pool-grid .primary-button{min-height:48px;width:100%;justify-content:center}.pool-grid article>header{align-items:flex-start;gap:8px}.pool-grid article>header small{text-align:right}.masked-timeline{padding:12px 10px}}
</style>
