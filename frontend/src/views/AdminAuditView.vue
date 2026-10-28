<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../lib/api'
import { dateTime } from '../lib/format'
import type { PageResponse } from '../types'

interface Audit { action: string; entityType: string; entityId?: string; outcome: string; details?: Record<string, unknown>; traceId?: string; actorMembershipId?: string; actorDisplayName?: string; createdAt: string }
interface Member { membershipId: string; displayName: string }

const result = ref<PageResponse<Audit>>()
const members = ref<Member[]>([])
const filter = reactive({ customerId: '', memberId: '', action: '', from: '', to: '' })
const busy = ref(false)
const error = ref('')
const query = computed(() => {
  const params = new URLSearchParams({ size: '100' })
  if (filter.customerId) params.set('customerId', filter.customerId.trim())
  if (filter.memberId) params.set('memberId', filter.memberId)
  if (filter.action) params.set('action', filter.action.trim())
  if (filter.from) params.set('from', new Date(filter.from).toISOString())
  if (filter.to) params.set('to', new Date(filter.to).toISOString())
  return params.toString()
})

async function load() {
  busy.value = true; error.value = ''
  try { result.value = await api<PageResponse<Audit>>(`/api/v1/admin/audit?${query.value}`) }
  catch { error.value = '审计日志筛选失败，请检查客户ID和日期范围。' }
  finally { busy.value = false }
}

function reset() { Object.assign(filter, { customerId: '', memberId: '', action: '', from: '', to: '' }); void load() }
onMounted(async () => { members.value = await api<Member[]>('/api/v1/admin/members'); await load() })
</script>

<template>
  <div class="page audit-page">
    <header class="page-header"><div><p class="eyebrow">APPEND-ONLY AUDIT</p><h1>审计日志</h1><p class="muted">按客户、成员、动作和日期检索不可覆盖的业务原始证据。</p></div><a class="ghost-button" :href="`/api/v1/admin/audit/export?${query}`">导出当前筛选</a></header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section class="panel audit-filters"><label>客户 UUID<input v-model.trim="filter.customerId" placeholder="完整客户ID"></label><label>操作成员<select v-model="filter.memberId"><option value="">全部成员</option><option v-for="member in members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label><label>动作类型<input v-model.trim="filter.action" placeholder="如 CUSTOMER_CREATED"></label><label>开始时间<input v-model="filter.from" type="datetime-local"></label><label>结束时间<input v-model="filter.to" type="datetime-local"></label><div><button class="primary-button" :disabled="busy" @click="load">查询</button><button class="text-button" @click="reset">清除</button></div></section>
    <section class="panel table-wrap"><table class="mobile-card-table"><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>结果</th><th>traceId</th><th>脱敏详情</th></tr></thead><tbody><tr v-for="item in result?.items" :key="item.createdAt + item.action"><td data-label="时间">{{ dateTime(item.createdAt) }}</td><td data-label="操作人">{{ item.actorDisplayName || '系统/历史成员' }}<small v-if="item.actorMembershipId" class="block">{{ item.actorMembershipId.slice(0,8) }}…</small></td><td data-label="动作"><strong>{{ item.action }}</strong></td><td data-label="对象">{{ item.entityType }}<small v-if="item.entityId" class="block">{{ item.entityId.slice(0, 8) }}…</small></td><td data-label="结果"><span :class="['table-status', item.outcome === 'SUCCESS' ? 'ready' : 'failed']">{{ item.outcome }}</span></td><td data-label="traceId"><code>{{ item.traceId || '—' }}</code></td><td data-label="脱敏详情"><details class="audit-details"><summary>查看详情</summary><code>{{ item.details ? JSON.stringify(item.details) : '—' }}</code></details></td></tr></tbody></table><div v-if="!busy && !result?.items.length" class="empty-state">当前筛选没有审计记录。</div></section>
  </div>
</template>

<style scoped>
.audit-filters{display:grid;grid-template-columns:1.25fr 1fr 1.2fr 1fr 1fr auto;align-items:end;gap:12px;margin-bottom:18px}.audit-filters label{display:grid;gap:7px;color:#56627a;font-size:11px;font-weight:650}.audit-filters>div{display:flex;align-items:center;gap:8px}.table-wrap{overflow:auto}table{min-width:1250px}td code{display:block;max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.audit-details summary{cursor:pointer;color:var(--brand);font-weight:700}.audit-details code{margin-top:8px;max-width:none;white-space:pre-wrap;overflow-wrap:anywhere}@media(max-width:1100px){.audit-filters{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:900px){.audit-filters{grid-template-columns:1fr}.audit-filters>div{display:grid;grid-template-columns:1fr 1fr}.table-wrap{padding:12px;overflow:visible}table{min-width:0}td code{max-width:100%;white-space:normal;overflow-wrap:anywhere}}
</style>
