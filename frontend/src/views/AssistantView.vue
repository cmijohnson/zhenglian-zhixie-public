<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'

const availability = ref<{ available: boolean; provider: string; model: string }>()
const mode = ref<'customer' | 'interaction'>('customer')
const input = ref('')
const result = ref<Record<string, unknown>>()
const briefing = ref('')
const busy = ref(false)
const error = ref('')
const briefingExpanded = ref(false)
const expandedResultFields = ref<Record<string, boolean>>({})
const copyNotice = ref('')
const resultEntries = computed(() => Object.entries(result.value ?? {}))
onMounted(async () => { availability.value = await api('/api/v1/assistant/availability') })

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '未确认'
  if (Array.isArray(value)) return value.map(String).filter(Boolean).join('\n') || '未确认'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

async function copyResult() {
  if (!result.value || !navigator.clipboard) return
  await navigator.clipboard.writeText(JSON.stringify(result.value, null, 2))
  copyNotice.value = '结构化结果已复制'
  window.setTimeout(() => { copyNotice.value = '' }, 1800)
}

async function parse() {
  busy.value = true; error.value = ''; result.value = undefined; expandedResultFields.value = {}
  const endpoint = mode.value === 'customer' ? 'customer-draft' : 'interaction-draft'
  try { result.value = await api(`/api/v1/assistant/${endpoint}`, { method: 'POST', body: jsonBody({ text: input.value }) }) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '智能助手调用失败' }
  finally { busy.value = false }
}

function streamBriefing() {
  briefing.value = ''; briefingExpanded.value = false; error.value = ''; busy.value = true
  const source = new EventSource('/api/v1/assistant/daily-briefing')
  source.addEventListener('delta', (event) => { briefing.value += (event as MessageEvent).data })
  source.addEventListener('done', () => { source.close(); busy.value = false })
  source.addEventListener('error', () => { source.close(); busy.value = false; if (!briefing.value) error.value = '简报生成中断' })
}
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">OPTIONAL AI ASSISTANT</p><h1>智能助手</h1><p class="muted">模型只接收已经过权限过滤的最小业务上下文。</p></div><span v-if="availability" :class="['availability', { online: availability.available }]">{{ availability.available ? `${availability.provider} · ${availability.model}` : '模型服务未配置' }}</span></header>
    <div v-if="availability && !availability.available" class="notice"><strong>基础功能不受影响</strong><span>管理员配置服务端模型密钥后，本页才会启用；客户、提醒、导入导出仍可正常使用。</span></div>
    <section class="panel assistant-card">
      <div class="assistant-tabs"><button :class="{ active: mode === 'customer' }" @click="mode = 'customer'">解析客户草稿</button><button :class="{ active: mode === 'interaction' }" @click="mode = 'interaction'">解析沟通草稿</button></div>
      <label>粘贴自然语言记录<textarea v-model.trim="input" rows="7" :placeholder="mode === 'customer' ? '例如：李同学，某大学大二，微信…' : '例如：今天微信沟通了课程方向，下周二再联系…'"></textarea></label>
      <p class="field-hint">解析结果只是可编辑草稿，不会自动写入数据库。</p><button class="primary-button" :disabled="busy || !input || !availability?.available" @click="parse">{{ busy ? '正在生成…' : '生成结构化草稿' }}</button>
      <div v-if="result" class="structured-result" aria-live="polite">
        <div class="result-heading"><div><p class="eyebrow">STRUCTURED DRAFT</p><h2>结构化草稿</h2><small>请逐项核对；系统不会自动写入客户档案。</small></div><button type="button" class="ghost-button" @click="copyResult">复制完整结果</button></div>
        <p v-if="copyNotice" class="copy-notice">{{ copyNotice }}</p>
        <div class="result-grid">
          <article v-for="([key, value]) in resultEntries" :key="key"><strong>{{ key }}</strong><pre :class="{ expanded: expandedResultFields[key] }">{{ displayValue(value) }}</pre><button v-if="displayValue(value).length > 120" type="button" class="content-toggle" :aria-expanded="Boolean(expandedResultFields[key])" @click="expandedResultFields[key] = !expandedResultFields[key]">{{ expandedResultFields[key] ? '收起' : '展开完整内容' }}</button></article>
        </div>
        <details class="raw-json"><summary>查看原始 JSON</summary><pre class="json-result">{{ JSON.stringify(result, null, 2) }}</pre></details>
      </div>
    </section>
    <section class="panel briefing-card"><div><p class="eyebrow">DAILY BRIEFING</p><h2>今日智能简报</h2><p>仅分析你当前可见且近期需要关注的客户，最多 25 位。</p></div><button class="soft-button" :disabled="busy || !availability?.available" @click="streamBriefing">生成简报</button><div v-if="briefing" :class="['ai-output', { expanded: briefingExpanded }]">{{ briefing }}</div><button v-if="briefing.length > 420" type="button" class="content-toggle" :aria-expanded="briefingExpanded" @click="briefingExpanded = !briefingExpanded">{{ briefingExpanded ? '收起简报' : '展开完整简报' }}</button></section>
    <p v-if="error" class="notice error">{{ error }}</p>
  </div>
</template>

<style scoped>
.structured-result{display:grid;gap:16px;margin-top:22px;padding-top:20px;border-top:1px solid #e3e8f2}
.result-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.result-heading h2{margin:2px 0 4px}.result-heading small{color:#768197}.result-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.result-grid article{min-width:0;padding:15px;border:1px solid #e2e8f2;border-radius:14px;background:#f8faff}.result-grid strong{display:block;margin-bottom:8px;color:#3159c7}.result-grid pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;line-height:1.65;color:#354158}.result-grid pre:not(.expanded){display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:5}.content-toggle{width:max-content;min-height:34px;padding:4px 0;border:0;background:transparent;color:#3159c7;font-size:12px;font-weight:750}.raw-json{padding:12px 14px;border:1px solid #e2e8f2;border-radius:12px;background:#fff}.raw-json summary{cursor:pointer;font-weight:700;color:#4c5971}.json-result{max-width:100%;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere}.copy-notice{margin:0;color:#237a4b}.ai-output{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.75}.ai-output:not(.expanded){display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:12}
@media(max-width:760px){.narrow-page{padding-inline:14px}.page-header{gap:12px}.availability{width:max-content;max-width:100%;overflow-wrap:anywhere}.assistant-card,.briefing-card{padding:18px}.assistant-tabs{display:grid;grid-template-columns:1fr 1fr;width:100%}.assistant-tabs button{min-height:44px;padding-inline:8px}.assistant-card textarea{font-size:16px}.assistant-card>.primary-button,.briefing-card>.soft-button{width:100%;min-height:48px;justify-content:center}.result-heading{display:grid}.result-heading .ghost-button{width:100%;min-height:44px}.result-grid{grid-template-columns:1fr}.result-grid article{padding:14px}.raw-json .json-result{max-height:50dvh}.briefing-card{display:grid;gap:12px}.ai-output:not(.expanded){-webkit-line-clamp:10}}
</style>
