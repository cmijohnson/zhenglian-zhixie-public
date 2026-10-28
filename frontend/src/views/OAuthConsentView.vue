<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import type { OAuthConsentRequest } from '../types'

const route = useRoute()
const request = ref<OAuthConsentRequest>()
const error = ref('')
const busy = ref(false)
const omitted = computed(() => request.value?.requestedScopes.filter((scope) => !request.value?.grantableScopes.includes(scope)) ?? [])
const scopeText: Record<string, string> = {
  'yingge.read': '读取你有权访问的客户、任务、内容、日报和团队数据',
  'yingge.write': '以你的权限创建或更新业务数据',
  'yingge.ai': '调用本组织的智能助手能力',
  'yingge.admin.read': '只读查看组织管理数据（仅组织管理员可授予）',
  'yingge.admin': '执行组织管理操作（仅组织管理员可授予）',
}

async function decide(approve: boolean) {
  if (!request.value) return
  busy.value = true; error.value = ''
  try {
    const result = await api<{ redirectUrl: string }>(`/api/v1/oauth/requests/${request.value.requestId}/${approve ? 'approve' : 'deny'}`, { method: 'POST' })
    window.location.assign(result.redirectUrl)
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '授权处理失败'; busy.value = false }
}

onMounted(async () => {
  const id = String(route.query.request ?? '')
  if (!id) { error.value = '授权请求缺失'; return }
  try { request.value = await api<OAuthConsentRequest>(`/api/v1/oauth/requests/${id}`) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '授权请求加载失败' }
})
</script>

<template>
  <div class="page narrow-page consent-page">
    <header class="page-header"><div><p class="eyebrow">OAUTH 2.1 CONSENT</p><h1>授权应用 / Agent</h1><p class="muted">请确认客户端身份、精确回调地址和将授予的权限。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section v-if="request" class="panel consent-card">
      <div class="consent-client"><span class="agent-client-mark large">⌘</span><div><small>请求连接的客户端</small><h2>{{ request.clientName }}</h2><code>{{ request.clientId }}</code></div></div>
      <div class="consent-callback"><span>精确回调地址</span><code>{{ request.redirectUri }}</code></div>
      <div class="consent-scopes"><h3>该应用将获得</h3><article v-for="scope in request.grantableScopes" :key="scope"><span>✓</span><div><strong>{{ scope }}</strong><p>{{ scopeText[scope] }}</p></div></article></div>
      <p v-if="omitted.length" class="notice">因当前角色不允许，以下 Scope 不会授予：<strong>{{ omitted.join('、') }}</strong></p>
      <div class="consent-expiry">请求将于 {{ dateTime(request.expiresAt) }} 失效。授权后可随时在“我的 Agent”撤销。</div>
      <div class="form-actions"><button class="soft-button" :disabled="busy" @click="decide(false)">拒绝</button><button class="primary-button" :disabled="busy || !request.grantableScopes.length" @click="decide(true)">{{ busy ? '正在处理…' : '同意并继续' }}</button></div>
    </section>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .consent-page { padding-inline: 14px; }
  .consent-card { padding: 20px 16px; }
  .consent-client { align-items: flex-start; }
  .consent-client code, .consent-callback code { display: block; max-width: 100%; overflow-wrap: anywhere; word-break: break-word; }
  .consent-scopes article { align-items: flex-start; }
  .form-actions { position: sticky; bottom: calc(72px + env(safe-area-inset-bottom)); z-index: 2; display: grid; grid-template-columns: 1fr 1fr; padding: 12px 0; background: rgba(255,255,255,.96); backdrop-filter: blur(14px); }
  .form-actions button { min-height: 48px; }
}
</style>
