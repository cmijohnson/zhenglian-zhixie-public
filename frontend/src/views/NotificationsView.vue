<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'

interface Notice { id: string; eventType: string; title: string; summary: string; targetPath?: string; read: boolean; createdAt: string; expiresAt: string }
interface Inbox { unreadCount: number; items: Notice[] }

const inbox = ref<Inbox>({ unreadCount: 0, items: [] })
const unreadOnly = ref(false)
const busy = ref(false)
const error = ref('')
const expanded = ref<Set<string>>(new Set())

function toggleSummary(id: string) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

async function load() {
  busy.value = true; error.value = ''
  try { inbox.value = await api<Inbox>(`/api/v1/notifications?unreadOnly=${unreadOnly.value}&size=100`) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '通知加载失败' }
  finally { busy.value = false }
}

async function markRead(item: Notice) {
  if (item.read) return
  await api(`/api/v1/notifications/${item.id}/read`, { method: 'POST' })
  item.read = true; inbox.value.unreadCount = Math.max(0, inbox.value.unreadCount - 1)
}

async function markAll() {
  await api('/api/v1/notifications/read-all', { method: 'POST' })
  inbox.value.items.forEach((item) => { item.read = true }); inbox.value.unreadCount = 0
}

onMounted(load)
</script>

<template>
  <div class="page narrow-page notification-page">
    <header class="page-header"><div><p class="eyebrow">BUSINESS INBOX</p><h1>通知中心</h1><p class="muted">归属、回池、二线、成单和治理事项以站内通知为准。</p></div><div class="header-actions"><label class="check-label"><input v-model="unreadOnly" type="checkbox" @change="load">只看未读</label><button class="ghost-button" :disabled="!inbox.unreadCount" @click="markAll">全部已读</button></div></header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section class="panel notification-list">
      <div class="panel-title"><div><p class="eyebrow">LATEST</p><h2>业务通知</h2></div><span>{{ inbox.unreadCount }} 条未读</span></div>
      <div v-if="busy" class="loading-state">正在加载通知…</div>
      <div v-else-if="!inbox.items.length" class="empty-state">暂无通知。</div>
      <article v-for="item in inbox.items" :key="item.id" :class="['notification-item', { unread: !item.read }]" @click="markRead(item)">
        <i></i><div><header><strong>{{ item.title }}</strong><time>{{ dateTime(item.createdAt) }}</time></header><p :class="{ expanded: expanded.has(item.id) }">{{ item.summary }}</p><button v-if="item.summary.length > 80" type="button" class="summary-toggle" :aria-expanded="expanded.has(item.id)" @click.stop="toggleSummary(item.id)">{{ expanded.has(item.id) ? '收起' : '展开详情' }}</button><small>{{ item.eventType }}</small></div>
        <RouterLink v-if="item.targetPath" :to="item.targetPath" class="ghost-button" @click="markRead(item)">去处理</RouterLink>
      </article>
    </section>
  </div>
</template>

<style scoped>
.header-actions{display:flex;align-items:center;gap:12px}.notification-list{padding:22px}.notification-item{display:grid;grid-template-columns:10px minmax(0,1fr) auto;align-items:center;gap:15px;padding:17px 10px;border-bottom:1px solid #edf0f5}.notification-item>i{width:8px;height:8px;border-radius:50%;background:#cbd3e2}.notification-item.unread>i{background:#4567e8;box-shadow:0 0 0 5px #edf1ff}.notification-item header{display:flex;justify-content:space-between;gap:20px}.notification-item time,.notification-item small{color:var(--muted);font-size:10px}.notification-item p{margin:7px 0;color:#526078;font-size:12px;line-height:1.65}.summary-toggle{display:none;color:#3157d5;background:transparent;font-size:11px;font-weight:700}@media(max-width:700px){.page-header{align-items:stretch}.header-actions{display:grid;grid-template-columns:1fr 1fr}.header-actions>*{min-height:44px}.notification-list{padding:14px}.notification-item{grid-template-columns:10px minmax(0,1fr);align-items:start;padding:18px 4px}.notification-item>.ghost-button{grid-column:2;justify-self:stretch;min-height:44px}.notification-item header{display:grid;gap:5px}.notification-item header strong,.notification-item p{overflow-wrap:anywhere}.notification-item p:not(.expanded){display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:3}.summary-toggle{display:inline-flex;min-height:40px;align-items:center;padding:0}.notification-item small{display:block}}
</style>
