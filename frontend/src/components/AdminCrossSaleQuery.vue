<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api } from '../lib/api'
import { dateTime, toIso } from '../lib/format'
import type { PageResponse } from '../types'

interface Option { membershipId: string; displayName: string }
interface CrossSaleRow { customerId: string; customerName: string; customerQuality?: string; teamName?: string; ownerMembershipId: string; ownerDisplayName: string; collaboratorCount: number; collaboratorNames: string; crossSale: boolean; businessCycleId?: string; businessCycleNumber?: number; businessCycleTitle?: string; businessCycleStatus?: string; quoteType?: string; quotedAt?: string; competitorName?: string; competitorFact?: string; statusMarkedAt?: string; customerUpdatedAt: string }

const props = withDefaults(defineProps<{ members?: Option[] }>(), { members: () => [] })
const rows = ref<CrossSaleRow[]>([])
const page = ref(0); const totalPages = ref(0); const total = ref(0)
const loading = ref(false); const error = ref('')
const filters = reactive({ crossSale: '', ownerMembershipId: '', collaboratorMembershipId: '', status: '', markedFrom: '', markedTo: '', quality: '' })

const statusLabels: Record<string,string> = { IN_PROGRESS: '进行中', LOCK_PENDING: '待锁定', FORMALLY_QUOTED: '已正式报价', WON_PENDING: '待确认成单', WON: '已成单', LOST_TO_COMPETITOR: '友商流失', CANCELLED: '已取消' }
function queryParameters(includePage = false, target = page.value) {
  const query = new URLSearchParams()
  if (includePage) { query.set('page', String(target)); query.set('size', '50') }
  Object.entries(filters).forEach(([key, value]) => {
    if (!value) return
    if (key === 'markedFrom' || key === 'markedTo') query.set(key, toIso(value) || '')
    else query.set(key, value)
  })
  return query
}
const exportUrl = computed(() => {
  const query = queryParameters()
  return `/api/v1/admin/cross-sales/export.xlsx${query.size ? `?${query}` : ''}`
})
async function load(target = page.value) {
  loading.value = true; error.value = ''
  try {
    const query = queryParameters(true, target)
    const result = await api<PageResponse<CrossSaleRow>>(`/api/v1/admin/cross-sales?${query}`)
    rows.value = result.items ?? []; page.value = result.page; totalPages.value = result.totalPages; total.value = result.totalElements
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '交叉单查询失败' }
  finally { loading.value = false }
}
function clearFilters() { Object.assign(filters, { crossSale: '', ownerMembershipId: '', collaboratorMembershipId: '', status: '', markedFrom: '', markedTo: '', quality: '' }); load(0) }
onMounted(() => load(0))
</script>

<template>
  <section class="panel cross-sale-query" data-testid="cross-sale-query">
    <div class="panel-title"><div><p class="eyebrow">CROSS-SALE DIRECTORY</p><h2>交叉单快速查询</h2><p class="muted">客户档案中只要存在有效协作者，即判定为交叉单；无需先建立业务周期。</p></div><span>{{ total }} 位客户</span></div>
    <form class="query-grid" @submit.prevent="load(0)">
      <label>交叉单<select v-model="filters.crossSale"><option value="">全部</option><option value="true">交叉单</option><option value="false">非交叉单</option></select></label>
      <label>主负责人<select v-model="filters.ownerMembershipId"><option value="">全部</option><option v-for="member in props.members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label>
      <label>协作者<select v-model="filters.collaboratorMembershipId"><option value="">全部</option><option v-for="member in props.members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label>
      <label>业务状态<select v-model="filters.status"><option value="">全部</option><option v-for="(label,key) in statusLabels" :key="key" :value="key">{{ label }}</option></select></label>
      <label>客户等级<select v-model="filters.quality"><option value="">全部</option><option>A</option><option>B</option><option>C</option></select></label>
      <label>标记起始<input v-model="filters.markedFrom" type="datetime-local"></label><label>标记结束<input v-model="filters.markedTo" type="datetime-local"></label>
      <div class="query-actions"><button class="primary-button" :disabled="loading">查询</button><a class="ghost-button export-button" data-testid="export-cross-sales" :href="exportUrl" download>导出当前筛选</a><button type="button" class="ghost-button" @click="clearFilters">清空筛选</button></div>
    </form>
    <p v-if="error" class="notice error">{{ error }}</p>
    <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>客户 / 最近周期</th><th>客户等级</th><th>主负责人</th><th>协作者</th><th>交叉单</th><th>业务状态</th><th>正式报价</th><th>最终结果</th><th>标记时间</th><th>档案</th></tr></thead><tbody><tr v-for="item in rows" :key="item.customerId"><td data-label="客户 / 最近周期"><strong>{{ item.customerName }}</strong><small>{{ item.businessCycleId ? `#${item.businessCycleNumber} · ${item.businessCycleTitle}` : '尚未建立业务周期' }}</small></td><td data-label="客户等级">{{ item.customerQuality || '—' }}</td><td data-label="主负责人">{{ item.ownerDisplayName || '—' }}</td><td data-label="协作者">{{ item.collaboratorNames || '—' }}<small v-if="item.collaboratorCount">{{ item.collaboratorCount }} 人</small></td><td data-label="交叉单"><span :class="['table-status', item.crossSale ? 'ready' : 'pending']">{{ item.crossSale ? '是' : '否' }}</span></td><td data-label="业务状态">{{ item.businessCycleStatus ? (statusLabels[item.businessCycleStatus] || item.businessCycleStatus) : '无业务周期' }}</td><td data-label="正式报价">{{ item.quoteType ? `${item.quoteType} · ${dateTime(item.quotedAt)}` : '未报价' }}</td><td data-label="最终结果">{{ item.businessCycleStatus === 'WON' ? '已成单' : item.businessCycleStatus === 'LOST_TO_COMPETITOR' ? `友商流失${item.competitorName ? `·${item.competitorName}` : ''}` : item.businessCycleStatus === 'CANCELLED' ? '已取消' : item.businessCycleStatus ? '推进中' : '—' }}</td><td data-label="标记时间">{{ dateTime(item.statusMarkedAt || item.customerUpdatedAt) }}</td><td data-label="档案"><RouterLink :to="`/customers/${item.customerId}`" class="ghost-button record-link">查看档案</RouterLink></td></tr></tbody></table><div v-if="!loading && !rows.length" class="empty-state">暂无符合条件的客户。</div></div>
    <footer class="pagination"><button class="ghost-button" :disabled="page <= 0 || loading" @click="load(page-1)">上一页</button><span>{{ totalPages ? page+1 : 0 }} / {{ totalPages }}</span><button class="ghost-button" :disabled="page+1 >= totalPages || loading" @click="load(page+1)">下一页</button></footer>
  </section>
</template>

<style scoped>
.cross-sale-query{display:grid;gap:16px}.query-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;padding:15px;border:1px solid var(--line);border-radius:16px;background:#f8faff}.query-grid label{display:grid;gap:6px;font-weight:650}.query-actions{display:flex;align-items:end;gap:8px;flex-wrap:wrap}.export-button{text-decoration:none;white-space:nowrap}.table-wrap td strong,.table-wrap td small{display:block}.table-wrap td small{margin-top:4px;color:var(--muted)}.pagination{display:flex;justify-content:center;align-items:center;gap:14px}.record-link{text-decoration:none}@media(max-width:1000px){.query-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:620px){.query-grid{grid-template-columns:1fr}.query-actions{display:grid;grid-template-columns:1fr}.query-actions>*{width:100%}.table-wrap{overflow:visible}.pagination{position:sticky;bottom:calc(var(--mobile-nav-space,0px));z-index:2;padding:10px;border:1px solid var(--line);border-radius:14px;background:rgba(255,255,255,.95)}.pagination button{min-width:0;flex:1}}
</style>
