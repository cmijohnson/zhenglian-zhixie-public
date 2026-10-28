<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'

interface Settings {
  enabled: boolean; autoProfileAnalysis: boolean; autoTimelineAnalysis: boolean
  embeddingBaseUrl: string; embeddingModel: string
  recentInteractionLimit: number; semanticChunkLimit: number
  backfillEnabled: boolean; backfillPaused: boolean; nightlyRatePerMinute: number
  pendingJobs: number; completedCustomers: number; totalCustomers: number
  updatedAt?: string; version: number
}

interface RuntimeStatus {
  sampledAt: string; pressureLevel: 'NORMAL' | 'BUSY' | 'HIGH'; pressureLabel: string; pressureMessage: string
  availableProcessors: number; systemCpuPercent?: number; processCpuPercent?: number
  systemMemoryUsedBytes?: number; systemMemoryMaxBytes?: number; systemMemoryPercent?: number
  jvmHeapUsedBytes: number; jvmHeapMaxBytes: number; jvmHeapPercent?: number; liveThreads: number; uptimeSeconds: number
  dbActiveConnections?: number; dbIdleConnections?: number; dbTotalConnections?: number
  dbAwaitingConnections?: number; dbMaxConnections?: number; dbPressurePercent?: number
  pendingJobs: number; runningJobs: number; failedJobs: number; oldestPendingSeconds?: number
}

const settings = ref<Settings>()
const runtime = ref<RuntimeStatus>()
const runtimeError = ref('')
let runtimeTimer: number | undefined
const form = reactive({
  enabled: true, autoProfileAnalysis: true, autoTimelineAnalysis: true,
  embeddingBaseUrl: 'http://embedding:8080', embeddingModel: 'bge-m3',
  recentInteractionLimit: 10, semanticChunkLimit: 12,
  backfillEnabled: true, backfillPaused: false, nightlyRatePerMinute: 10,
})
const busy = ref(false)
const error = ref('')
const success = ref('')

function apply(value: Settings) {
  settings.value = value
  Object.assign(form, {
    enabled: value.enabled, autoProfileAnalysis: value.autoProfileAnalysis,
    autoTimelineAnalysis: value.autoTimelineAnalysis,
    embeddingBaseUrl: value.embeddingBaseUrl, embeddingModel: value.embeddingModel,
    recentInteractionLimit: value.recentInteractionLimit,
    semanticChunkLimit: value.semanticChunkLimit, backfillEnabled: value.backfillEnabled,
    backfillPaused: value.backfillPaused, nightlyRatePerMinute: value.nightlyRatePerMinute,
  })
}

async function load() {
  try { apply(await api<Settings>('/api/v1/admin/customer-intelligence/settings')) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '客户智能分析配置加载失败' }
}

async function loadRuntime() {
  runtimeError.value = ''
  try { runtime.value = await api<RuntimeStatus>('/api/v1/admin/customer-intelligence/runtime') }
  catch (reason) { runtimeError.value = reason instanceof ApiError ? reason.message : '运行压力加载失败' }
}

function percent(value?: number) { return value == null ? '暂不可用' : `${value.toFixed(1)}%` }
function bytes(value?: number) {
  if (value == null) return '暂不可用'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']; let current = value; let index = 0
  while (current >= 1024 && index < units.length - 1) { current /= 1024; index += 1 }
  return `${current.toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}
function duration(seconds: number) {
  const days = Math.floor(seconds / 86400); const hours = Math.floor((seconds % 86400) / 3600)
  return days ? `${days} 天 ${hours} 小时` : `${hours} 小时`
}

async function save() {
  if (!settings.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    apply(await api<Settings>('/api/v1/admin/customer-intelligence/settings', {
      method: 'PUT', body: jsonBody({ ...form, expectedVersion: settings.value.version }),
    }))
    success.value = '客户智能分析策略已保存。'
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '配置保存失败'
  } finally { busy.value = false }
}

async function toggleBackfill(paused: boolean) {
  busy.value = true; error.value = ''; success.value = ''
  try {
    apply(await api<Settings>(`/api/v1/admin/customer-intelligence/backfill/${paused ? 'pause' : 'resume'}`, { method: 'POST' }))
    success.value = paused ? '历史客户回填已暂停。' : '历史客户回填已恢复，仅会在 00:00–06:00 处理。'
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '无法更新回填状态'
  } finally { busy.value = false }
}

onMounted(() => {
  void Promise.all([load(), loadRuntime()])
  runtimeTimer = window.setInterval(loadRuntime, 15_000)
})
onBeforeUnmount(() => { if (runtimeTimer != null) window.clearInterval(runtimeTimer) })
</script>

<template>
  <div class="page customer-intelligence-admin-page">
    <header class="page-header"><div><p class="eyebrow">CUSTOMER INTELLIGENCE CONTROL</p><h1>客户智能分析</h1><p class="muted">管理自动分析、BGE-M3 向量服务与历史回填；分析结果始终只读，不会自动改写客户档案。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <section class="panel runtime-panel" data-testid="intelligence-runtime">
      <header><div><p class="eyebrow">LIVE CAPACITY</p><h2>服务器性能与分析压力</h2><p class="muted">显示当前应用实例、数据库连接池和本组织分析队列的安全汇总，每 15 秒刷新。</p></div><button type="button" class="ghost-button" @click="loadRuntime">立即刷新</button></header>
      <p v-if="runtimeError" class="notice error">{{ runtimeError }}</p>
      <template v-if="runtime">
        <div class="pressure-banner" :class="runtime.pressureLevel.toLowerCase()"><span>当前压力</span><strong>{{ runtime.pressureLabel }}</strong><p>{{ runtime.pressureMessage }}</p></div>
        <div class="runtime-grid">
          <article><span>系统 CPU</span><strong>{{ percent(runtime.systemCpuPercent) }}</strong><small>{{ runtime.availableProcessors }} 核 · 进程 {{ percent(runtime.processCpuPercent) }}</small></article>
          <article><span>系统内存</span><strong>{{ percent(runtime.systemMemoryPercent) }}</strong><small>{{ bytes(runtime.systemMemoryUsedBytes) }} / {{ bytes(runtime.systemMemoryMaxBytes) }}</small></article>
          <article><span>JVM 堆内存</span><strong>{{ percent(runtime.jvmHeapPercent) }}</strong><small>{{ bytes(runtime.jvmHeapUsedBytes) }} / {{ bytes(runtime.jvmHeapMaxBytes) }} · {{ runtime.liveThreads }} 线程</small></article>
          <article><span>数据库连接</span><strong>{{ runtime.dbActiveConnections ?? '—' }} / {{ runtime.dbMaxConnections ?? '—' }}</strong><small>空闲 {{ runtime.dbIdleConnections ?? '—' }} · 等待 {{ runtime.dbAwaitingConnections ?? '—' }} · 压力 {{ percent(runtime.dbPressurePercent) }}</small></article>
          <article><span>分析队列</span><strong>{{ runtime.pendingJobs }} 待处理</strong><small>运行 {{ runtime.runningJobs }} · 近 24h 失败 {{ runtime.failedJobs }}<template v-if="runtime.oldestPendingSeconds != null"> · 最久等待 {{ runtime.oldestPendingSeconds }} 秒</template></small></article>
          <article><span>实例运行时间</span><strong>{{ duration(runtime.uptimeSeconds) }}</strong><small>采样 {{ new Date(runtime.sampledAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) }}</small></article>
        </div>
      </template>
      <p v-else-if="!runtimeError" class="muted">正在采集运行指标…</p>
    </section>
    <template v-if="settings">
      <section class="intelligence-admin-kpis">
        <article><span>已生成分析</span><strong>{{ settings.completedCustomers }}</strong><small>/ {{ settings.totalCustomers }} 位客户</small></article>
        <article><span>队列任务</span><strong>{{ settings.pendingJobs }}</strong><small>单工作器串行执行</small></article>
        <article><span>向量模型</span><strong>BGE-M3</strong><small>1024 维 · 本地服务</small></article>
      </section>
      <form class="panel intelligence-admin-form" @submit.prevent="save">
        <div class="setting-toggle-grid">
          <label class="toggle-row"><input v-model="form.enabled" type="checkbox"><span><strong>启用客户智能分析</strong><small>总开关关闭时不再执行新分析。</small></span></label>
          <label class="toggle-row"><input v-model="form.autoProfileAnalysis" type="checkbox"><span><strong>画像更新后自动分析</strong><small>新建客户和关键画像变更触发。</small></span></label>
          <label class="toggle-row"><input v-model="form.autoTimelineAnalysis" type="checkbox"><span><strong>时间线更新后自动分析</strong><small>30 秒去抖，密集录入只执行最新一次。</small></span></label>
          <label class="toggle-row"><input v-model="form.backfillEnabled" type="checkbox"><span><strong>历史客户回填</strong><small>仅在北京时间 00:00–06:00 运行。</small></span></label>
        </div>
        <div class="form-grid two">
          <label>向量服务地址<input v-model.trim="form.embeddingBaseUrl" type="url" required></label>
          <label>向量模型<input v-model.trim="form.embeddingModel" required></label>
          <label>最近时间线数<input v-model.number="form.recentInteractionLimit" type="number" min="1" max="30" required></label>
          <label>语义检索块数<input v-model.number="form.semanticChunkLimit" type="number" min="1" max="30" required></label>
          <label>夜间每分钟回填客户数<input v-model.number="form.nightlyRatePerMinute" type="number" min="1" max="10" required></label>
        </div>
        <div class="intelligence-admin-note"><strong>数据边界</strong><p>语义检索始终同时限定组织和客户 ID；未确认的信息必须显式标记，每个版本保留证据引用。</p></div>
        <footer class="form-actions"><button type="button" class="ghost-button" :disabled="busy" @click="toggleBackfill(!form.backfillPaused)">{{ form.backfillPaused ? '恢复回填' : '暂停回填' }}</button><button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存分析策略' }}</button></footer>
      </form>
    </template>
  </div>
</template>

<style scoped>
.runtime-panel{display:grid;gap:18px;margin-bottom:24px}.runtime-panel>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.runtime-panel h2{margin:4px 0 8px}.pressure-banner{display:grid;grid-template-columns:auto auto 1fr;align-items:center;gap:12px;padding:15px 18px;border:1px solid #cfe0ff;border-radius:16px;background:#f4f8ff}.pressure-banner span{font-size:13px;color:#65728a}.pressure-banner strong{padding:5px 10px;border-radius:999px;background:#dce8ff;color:#2755c7}.pressure-banner p{margin:0;color:#516078}.pressure-banner.busy{border-color:#f2d39b;background:#fff9ed}.pressure-banner.busy strong{background:#ffebc6;color:#946009}.pressure-banner.high{border-color:#f0b8bd;background:#fff4f5}.pressure-banner.high strong{background:#ffdce0;color:#b32635}.runtime-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.runtime-grid article{display:grid;gap:7px;padding:18px;border:1px solid #e4e9f2;border-radius:17px;background:linear-gradient(145deg,#fff,#f8faff)}.runtime-grid span,.runtime-grid small{color:#6d7890}.runtime-grid strong{font-size:24px;color:#15213a}@media(max-width:1000px){.runtime-grid{grid-template-columns:1fr 1fr}}@media(max-width:650px){.runtime-panel>header{display:grid}.runtime-panel>header button{width:100%;min-height:44px}.pressure-banner{grid-template-columns:auto auto}.pressure-banner p{grid-column:1/-1}.runtime-grid{grid-template-columns:1fr}.intelligence-admin-kpis{grid-template-columns:1fr}.setting-toggle-grid{grid-template-columns:1fr}.intelligence-admin-form{padding:18px 14px}.intelligence-admin-form .form-actions{position:sticky;bottom:calc(72px + env(safe-area-inset-bottom));z-index:2;display:grid;grid-template-columns:1fr;gap:8px;padding:10px 0;background:rgba(255,255,255,.96)}.intelligence-admin-form .form-actions button{width:100%;min-height:48px}}
</style>
