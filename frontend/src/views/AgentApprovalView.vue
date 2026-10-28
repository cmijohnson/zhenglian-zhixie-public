<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import type { AgentApproval } from '../types'

const route = useRoute(); const item = ref<AgentApproval>(); const error = ref(''); const busy = ref(false)
async function load() { try { item.value = await api<AgentApproval>(`/api/v1/agent/approvals/${route.params.id}`) } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '确认请求加载失败' } }
async function decide(approve: boolean) { busy.value = true; error.value = ''; try { item.value = await api<AgentApproval>(`/api/v1/agent/approvals/${route.params.id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' }) } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '处理失败' } finally { busy.value = false } }
onMounted(load)
</script>

<template><div class="page narrow-page"><header class="page-header"><div><p class="eyebrow">ONE-TIME HUMAN APPROVAL</p><h1>确认 Agent 危险操作</h1><p class="muted">确认单与你、客户端、工具和参数绑定，5 分钟失效且只能使用一次。</p></div></header><p v-if="error" class="notice error">{{ error }}</p><section v-if="item" class="panel approval-detail"><div class="approval-shield">!</div><p class="eyebrow approval-client-id">{{ item.clientId }}</p><h2>{{ item.toolName }}</h2><p>{{ item.summary }}</p><dl><div><dt>状态</dt><dd><span class="table-status">{{ item.status }}</span></dd></div><div><dt>创建时间</dt><dd>{{ dateTime(item.createdAt) }}</dd></div><div><dt>失效时间</dt><dd>{{ dateTime(item.expiresAt) }}</dd></div></dl><div v-if="item.status === 'PENDING'" class="form-actions"><button class="soft-button" :disabled="busy" @click="decide(false)">拒绝</button><button class="danger-button" :disabled="busy" @click="decide(true)">确认执行</button></div><p v-else class="notice">该确认请求已处理，无法再次使用。</p></section></div></template>

<style scoped>
.approval-client-id { overflow-wrap: anywhere; }
@media (max-width: 900px) {
  .approval-detail { padding: 22px 16px; }
  .approval-detail > p { overflow-wrap: anywhere; }
  .approval-detail dl > div { gap: 16px; }
  .approval-detail dd { text-align: right; overflow-wrap: anywhere; }
  .form-actions { position: sticky; bottom: calc(72px + env(safe-area-inset-bottom)); display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding-top: 14px; background: rgba(255,255,255,.96); }
  .form-actions button { min-height: 48px; }
}
</style>
