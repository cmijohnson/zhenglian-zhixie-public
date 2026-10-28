<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'

interface AssistantSettings {
  enabled: boolean
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  apiKeySource: 'DATABASE' | 'ENVIRONMENT' | 'NONE'
  updatedAt?: string
  version: number
}

const settings = ref<AssistantSettings>()
const form = reactive({ enabled: true, baseUrl: '', model: '', apiKey: '', clearApiKey: false })
const busy = ref(false)
const error = ref('')
const success = ref('')

function apply(value: AssistantSettings) {
  settings.value = value
  form.enabled = value.enabled
  form.baseUrl = value.baseUrl
  form.model = value.model
  form.apiKey = ''
  form.clearApiKey = false
}

async function load() {
  try { apply(await api<AssistantSettings>('/api/v1/admin/assistant-settings')) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '智能助手配置加载失败' }
}

async function save() {
  if (!settings.value) return
  busy.value = true; error.value = ''; success.value = ''
  const body: Record<string, unknown> = {
    enabled: form.enabled, baseUrl: form.baseUrl, model: form.model,
    clearApiKey: form.clearApiKey, expectedVersion: settings.value.version,
  }
  if (form.apiKey) body.apiKey = form.apiKey
  try {
    apply(await api<AssistantSettings>('/api/v1/admin/assistant-settings', { method: 'PUT', body: jsonBody(body) }))
    success.value = '智能助手配置已保存并动态生效'
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '智能助手配置保存失败'
  } finally { busy.value = false }
}

const keySourceLabel = (source: AssistantSettings['apiKeySource']) => ({
  DATABASE: '管理员加密配置', ENVIRONMENT: '服务器环境配置', NONE: '未配置',
}[source])

onMounted(load)
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">AI ADMINISTRATION</p><h1>智能助手配置</h1><p class="muted">仅组织管理员可管理；开关、API 地址和模型保存后立即生效。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="success" class="notice success">{{ success }}</p>
    <section v-if="settings" class="panel assistant-settings-card">
      <form class="form-grid assistant-settings-form" @submit.prevent="save">
        <label class="toggle-row"><input v-model="form.enabled" type="checkbox"><span><strong>启用智能助手服务</strong><small>关闭后所有草稿、摘要、建议和简报接口立即进入安全降级。</small></span></label>
        <label>OpenAI 兼容 API 地址<input v-model.trim="form.baseUrl" required maxlength="500" inputmode="url" placeholder="https://provider.example/v1"></label>
        <label>模型名称<input v-model.trim="form.model" required maxlength="200" placeholder="model-name"></label>
        <label>更新 API 密钥<input v-model="form.apiKey" type="password" maxlength="2000" autocomplete="new-password" placeholder="留空表示不修改"><small>现有密钥永不回显；新密钥以 AES-256-GCM 加密保存。</small></label>
        <label class="toggle-row danger-toggle"><input v-model="form.clearApiKey" type="checkbox" :disabled="Boolean(form.apiKey)"><span><strong>清除管理员密钥</strong><small>若服务器环境仍有密钥，将自动回退到环境配置。</small></span></label>
        <div class="settings-status">
          <span :class="['availability', { online: settings.enabled && settings.apiKeyConfigured }]">{{ settings.enabled ? '服务已开启' : '服务已关闭' }}</span>
          <span>密钥：{{ keySourceLabel(settings.apiKeySource) }}</span>
          <span>配置版本：{{ settings.version }}</span>
        </div>
        <button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存并立即生效' }}</button>
      </form>
    </section>
    <section class="panel security-note"><h2>安全说明</h2><p>接口只返回密钥是否已配置及来源，不返回明文或密文。审计日志只记录开关、地址、模型及“密钥是否变更”。</p></section>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .assistant-settings-card { padding: 20px 16px; }
  .toggle-row { align-items: flex-start; min-height: 52px; }
  .assistant-settings-form input { font-size: 16px; }
  .settings-status { display: grid; gap: 8px; }
  .assistant-settings-form .primary-button { position: sticky; bottom: calc(72px + env(safe-area-inset-bottom)); z-index: 2; width: 100%; min-height: 50px; }
  .security-note { padding: 18px 16px; }
}
</style>
