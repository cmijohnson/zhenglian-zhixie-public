<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'
import type { Customer, Reminder } from '../types'

const reminders = ref<Reminder[]>([])
const names = ref<Record<string, string>>({})
const filter = ref<'PENDING' | 'COMPLETED' | 'ALL'>('PENDING')
const visible = computed(() => filter.value === 'ALL' ? reminders.value : reminders.value.filter((item) => item.status === filter.value))

async function load() {
  reminders.value = await api<Reminder[]>('/api/v1/reminders')
  const customerIds = [...new Set(reminders.value.map((item) => item.customerId))]
  await Promise.all(customerIds.map(async (id) => {
    try { names.value[id] = (await api<Customer>(`/api/v1/customers/${id}`)).fullName } catch { names.value[id] = '无权访问的客户' }
  }))
}
async function update(id: string, status: string) { await api(`/api/v1/reminders/${id}`, { method: 'PATCH', body: jsonBody({ status }) }); await load() }
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><p class="eyebrow">FOLLOW-UP QUEUE</p><h1>跟进提醒</h1><p class="muted">逾期状态按当前时间动态计算，不会篡改原定提醒时间。</p></div></header>
    <div class="segmented"><button v-for="item in [['PENDING','待处理'],['COMPLETED','已完成'],['ALL','全部']]" :key="item[0]" :class="{ active: filter === item[0] }" @click="filter = item[0] as typeof filter">{{ item[1] }}</button></div>
    <section class="panel reminder-list">
      <article v-for="item in visible" :key="item.id" :class="['reminder-row', { overdue: item.overdue }]">
        <div class="date-tile"><strong>{{ new Date(item.remindAt).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', day: '2-digit' }) }}</strong><small>{{ new Date(item.remindAt).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'short' }) }}</small></div>
        <div class="reminder-copy"><div><span v-if="item.overdue" class="overdue-label">已逾期</span><strong>{{ item.title }}</strong></div><RouterLink :to="`/customers/${item.customerId}`">{{ names[item.customerId] || '客户档案' }}</RouterLink><small>{{ dateTime(item.remindAt) }}</small></div>
        <div v-if="item.status === 'PENDING'" class="row-actions"><button class="soft-button" @click="update(item.id, 'COMPLETED')">标记完成</button><button class="text-button" @click="update(item.id, 'CANCELLED')">取消</button></div>
        <span v-else class="muted">{{ item.status === 'COMPLETED' ? '已完成' : '已取消' }}</span>
      </article>
      <div v-if="!visible.length" class="empty-state">当前列表没有提醒。</div>
    </section>
  </div>
</template>

<style scoped>
@media(max-width:640px){
  .segmented{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));width:100%}
  .segmented button{min-height:44px;padding-inline:8px}
  .reminder-list{padding:8px 14px}
  .reminder-row{grid-template-columns:auto minmax(0,1fr);align-items:start;gap:12px;padding:16px 0}
  .reminder-copy>div{align-items:flex-start;flex-wrap:wrap}
  .row-actions{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr;width:100%;gap:9px}
  .row-actions button{min-height:44px}
  .reminder-row>.muted{grid-column:2}
}
</style>
