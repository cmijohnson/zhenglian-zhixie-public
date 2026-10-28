<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, api } from '../lib/api'

interface Preview { jobId: string; filename: string; format: string; totalRows: number; readyRows: number; failedRows: number; rows: { rowNumber: number; status: string; data: { fullName: string; phone?: string; wechatId?: string; qqId?: string }; messages: string[] }[] }
interface Report { jobId: string; filename: string; format: string; status: string; totalRows: number; createdCount: number; skippedCount: number; failedCount: number; messages: { rowNumber: number; status: string; messages: string[] }[] }
const file = ref<File>()
const preview = ref<Preview>()
const report = ref<Report>()
const busy = ref(false)
const error = ref('')

function select(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] }
async function inspect() {
  if (!file.value) return
  busy.value = true; error.value = ''
  const body = new FormData(); body.append('file', file.value)
  try { preview.value = await api<Preview>('/api/v1/imports/customers/preview', { method: 'POST', body }) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '文件预览失败' }
  finally { busy.value = false }
}
async function commit() {
  if (!preview.value || !window.confirm(`确认导入 ${preview.value.readyRows} 行有效数据？`)) return
  busy.value = true
  try { report.value = await api<Report>(`/api/v1/imports/${preview.value.jobId}/commit`, { method: 'POST' }) }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '导入提交失败' }
  finally { busy.value = false }
}
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">CONTROLLED IMPORT</p><h1>批量导入</h1><p class="muted">先解析预览，再明确确认；错误行不会写入客户档案。</p></div></header>
    <section class="panel import-drop">
      <div class="upload-icon">⇧</div><h2>选择 CSV 或 XLSX 文件</h2><p>支持中文表头、UTF-8 BOM；至少包含姓名和一种联系方式。</p>
      <label class="primary-button file-button">选择文件<input type="file" accept=".csv,.xlsx" @change="select"></label><strong v-if="file">{{ file.name }}</strong>
      <button v-if="file" class="soft-button" :disabled="busy" @click="inspect">{{ busy ? '正在检查…' : '生成导入预览' }}</button>
    </section>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section v-if="preview" class="panel">
      <div class="panel-title"><div><p class="eyebrow">PREVIEW</p><h2>{{ preview.filename }}</h2></div><button v-if="!report" class="primary-button" :disabled="busy || !preview.readyRows" @click="commit">确认导入 {{ preview.readyRows }} 行</button></div>
      <div class="metric-grid mini"><article><span>总行数</span><strong>{{ preview.totalRows }}</strong></article><article><span>可导入</span><strong>{{ preview.readyRows }}</strong></article><article class="danger"><span>错误</span><strong>{{ preview.failedRows }}</strong></article></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>行</th><th>状态</th><th>姓名</th><th>联系方式</th><th>提示</th></tr></thead><tbody><tr v-for="row in preview.rows" :key="row.rowNumber"><td data-label="行">{{ row.rowNumber }}</td><td data-label="状态"><span :class="['table-status', row.status.toLowerCase()]">{{ row.status }}</span></td><td data-label="姓名">{{ row.data.fullName }}</td><td data-label="联系方式">{{ row.data.phone || row.data.wechatId || row.data.qqId || '—' }}</td><td data-label="提示">{{ row.messages.join('；') || '校验通过' }}</td></tr></tbody></table></div>
    </section>
    <section v-if="report" class="panel success-panel"><p class="eyebrow">IMPORT REPORT</p><h2>导入任务 {{ report.status }}</h2><p>新增 {{ report.createdCount }}，跳过 {{ report.skippedCount }}，失败 {{ report.failedCount }}。</p><ul><li v-for="message in report.messages" :key="message.rowNumber">第 {{ message.rowNumber }} 行：{{ message.messages.join('；') }}</li></ul></section>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .import-drop { padding: 28px 18px; text-align: center; }
  .import-drop .primary-button, .import-drop .soft-button { width: 100%; min-height: 48px; }
  .panel-title { align-items: stretch; }
  .panel-title > button { width: 100%; min-height: 48px; }
  .metric-grid.mini { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .success-panel li { overflow-wrap: anywhere; }
}
@media (max-width: 430px) {
  .metric-grid.mini { grid-template-columns: 1fr; }
}
</style>
