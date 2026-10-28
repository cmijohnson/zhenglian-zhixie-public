<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import type { AgentApproval, AgentAuthorization, PersonalAgentToken } from '../types'

const authorizations = ref<AgentAuthorization[]>([])
const approvals = ref<AgentApproval[]>([])
const mcpAddress = ref('')
const error = ref('')
const copied = ref(false)
const personalConfig = ref('')
const personalExpiresAt = ref('')
const issuingPersonalToken = ref(false)
const configCopied = ref(false)
const loading = ref(true)

const activeCount = computed(() => authorizations.value.filter((item) => item.status === 'ACTIVE').length)

async function load() {
  loading.value = true; error.value = ''
  try {
    const [grants, pending, metadata] = await Promise.all([
      api<AgentAuthorization[]>('/api/v1/agent/authorizations'),
      api<AgentApproval[]>('/api/v1/agent/approvals'),
      fetch('/.well-known/oauth-protected-resource/mcp').then(async (response) => {
        if (!response.ok) throw new Error('无法读取 MCP 元数据')
        return response.json() as Promise<{ resource: string }>
      }),
    ])
    authorizations.value = grants
    approvals.value = pending
    mcpAddress.value = metadata.resource
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '我的 Agent 加载失败'
  } finally { loading.value = false }
}

async function copyAddress() {
  await navigator.clipboard.writeText(mcpAddress.value)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1800)
}

async function issueQwenConfig() {
  if (!window.confirm('生成后会立即撤销你上一份问候兼容令牌。新令牌有效期 30 天，并将以明文写入你导入问候的本地配置。确定继续吗？')) return
  issuingPersonalToken.value = true; error.value = ''
  try {
    const result = await api<PersonalAgentToken>('/api/v1/agent/personal-token', { method: 'POST' })
    personalConfig.value = JSON.stringify({
      mcpServers: {
        yingge_mcp_personal: {
          name: '英格客户云',
          description: '以当前员工身份访问英格客户云业务功能',
          transport: 'streamable_http',
          url: result.resource,
          headers: { Authorization: `Bearer ${result.accessToken}` },
        },
      },
    }, null, 2)
    personalExpiresAt.value = result.expiresAt
    await load()
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '问候兼容配置生成失败'
  } finally { issuingPersonalToken.value = false }
}

async function copyQwenConfig() {
  await navigator.clipboard.writeText(personalConfig.value)
  configCopied.value = true
  window.setTimeout(() => { configCopied.value = false }, 1800)
}

async function revoke(id: string) {
  if (!window.confirm('确定撤销该 Agent 授权吗？现有 Access Token 和 Refresh Token 将立即失效。')) return
  try { await api<void>(`/api/v1/agent/authorizations/${id}`, { method: 'DELETE' }); await load() }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '撤销失败' }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><p class="eyebrow">PERSONAL AGENT ACCESS</p><h1>我的 Agent</h1><p class="muted">Agent 始终以你本人身份执行，继承当前组织、角色、团队和客户可见性。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <div v-if="loading" class="loading-state">正在加载 Agent 授权…</div>
    <template v-else>
      <section class="panel agent-endpoint-card">
        <div><p class="eyebrow">STREAMABLE HTTP</p><h2>员工统一 MCP 地址</h2><p>在支持 OAuth 2.1 的 Agent 客户端中添加此地址，然后在英格客户云登录并确认 Scope。</p></div>
        <div class="agent-address"><code>{{ mcpAddress }}</code><button class="soft-button" @click="copyAddress">{{ copied ? '已复制' : '复制地址' }}</button></div>
        <div class="agent-security-grid"><span><strong>{{ activeCount }}</strong>活跃授权</span><span><strong>15 分钟</strong>Access Token</span><span><strong>30 天</strong>Refresh Token</span><span><strong>PKCE S256</strong>授权码保护</span></div>
      </section>

      <section class="panel qwen-compat-card">
        <div class="panel-title">
          <div><p class="eyebrow">QWENPAW 1.1 COMPATIBILITY</p><h2>问候兼容连接</h2></div>
          <button class="soft-button" :disabled="issuingPersonalToken" @click="issueQwenConfig">{{ issuingPersonalToken ? '正在生成…' : '生成问候连接配置' }}</button>
        </div>
        <p class="muted">问候 1.1.12 的 OAuth 回调会被其自身登录中间件拦截。兼容配置改用个人 Bearer 令牌，仍按你的角色、组织、团队和资源权限执行。</p>
        <div class="notice"><strong>安全提示</strong><span>令牌只显示一次、30 天到期；不要发到聊天或截图中。重新生成会立即撤销上一份兼容令牌。</span></div>
        <div v-if="personalConfig" class="personal-token-reveal">
          <div><strong>请复制整个 JSON，在问候“新建 MCP → JSON”中导入</strong><small>本页面刷新后不再显示。到期时间：{{ dateTime(personalExpiresAt) }}</small></div>
          <textarea readonly :value="personalConfig" aria-label="问候 MCP JSON 配置"></textarea>
          <button class="primary-button" @click="copyQwenConfig">{{ configCopied ? '已复制' : '复制完整 JSON' }}</button>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">AUTHORIZED CLIENTS</p><h2>已授权客户端</h2></div><button class="text-button" @click="load">刷新</button></div>
        <div v-if="!authorizations.length" class="empty-state">暂无 Agent 授权。从 Agent 客户端连接上方 MCP 地址即可开始授权。</div>
        <div v-else class="agent-grant-list">
          <article v-for="grant in authorizations" :key="grant.id">
            <div class="agent-client-mark">⌘</div>
            <div><strong>{{ grant.clientName }}</strong><small>{{ grant.clientId }}</small><div class="scope-list"><span v-for="scope in grant.scopes" :key="scope">{{ scope }}</span></div></div>
            <div class="agent-grant-times"><span>最近使用<strong>{{ dateTime(grant.lastUsedAt) }}</strong></span><span>授权到期<strong>{{ dateTime(grant.expiresAt) }}</strong></span></div>
            <span :class="['table-status', grant.status === 'ACTIVE' ? 'ready' : 'failed']">{{ grant.status }}</span>
            <button v-if="grant.status === 'ACTIVE'" class="danger-link" @click="revoke(grant.id)">撤销</button>
          </article>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">HUMAN APPROVAL</p><h2>危险操作确认</h2></div></div>
        <div v-if="!approvals.length" class="empty-state small">暂无确认请求。</div>
        <div v-else class="approval-list">
          <RouterLink v-for="item in approvals.slice(0, 20)" :key="item.id" :to="`/agent/approvals/${item.id}`">
            <div><strong>{{ item.toolName }}</strong><small>{{ item.summary }}</small></div><span :class="['table-status', item.status === 'APPROVED' ? 'ready' : '']">{{ item.status }}</span><time>{{ dateTime(item.createdAt) }}</time>
          </RouterLink>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .agent-endpoint-card { padding: 20px 16px; }
  .agent-address code { max-width: 100%; overflow-wrap: anywhere; word-break: break-word; }
  .agent-address button,
  .qwen-compat-card .panel-title button,
  .personal-token-reveal button { width: 100%; min-height: 48px; }
  .qwen-compat-card .panel-title { align-items: stretch; }
  .personal-token-reveal textarea { min-height: 280px; font-size: 16px; white-space: pre-wrap; overflow-wrap: anywhere; }
  .agent-grant-list article { padding: 16px 0; }
  .agent-grant-list small { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
  .agent-grant-times { flex-wrap: wrap; gap: 10px 18px; }
  .agent-grant-list .danger-link { min-height: 44px; padding: 0 14px; border-radius: 10px; background: #fff1f0; }
  .approval-list > a { min-height: 72px; }
}
</style>
