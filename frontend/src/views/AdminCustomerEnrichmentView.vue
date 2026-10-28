<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, api } from '../lib/api'
import type { EnrichmentJob, EnrichmentPreview } from '../types'

const file = ref<File>()
const preview = ref<EnrichmentPreview>()
const jobs = ref<EnrichmentJob[]>([])
const busy = ref(false)
const error = ref('')
const success = ref('')
const statusLabel: Record<string, string> = { READY: '可补充', UPDATED: '已补充', ALREADY_PRESENT: '已有资料', NO_CUSTOMER: '未找到客户', AMBIGUOUS: '多个客户', GRADE_MISMATCH: '年级不符', UNVERIFIED_LOCATION: '所在地待核实', CONFLICT: '资料冲突', INVALID: '无效行' }

async function loadJobs() { jobs.value = await api<EnrichmentJob[]>('/api/v1/admin/customer-enrichment-jobs') }
function choose(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] }
async function upload() {
  if (!file.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const body = new FormData(); body.append('file', file.value)
    preview.value = await api<EnrichmentPreview>('/api/v1/admin/customer-enrichment-jobs/preview', { method: 'POST', body })
    await loadJobs()
  } catch (reason) { error.value = message(reason, '补全文件预览失败') }
  finally { busy.value = false }
}
async function open(job: EnrichmentJob) { preview.value = await api<EnrichmentPreview>(`/api/v1/admin/customer-enrichment-jobs/${job.id}`) }
async function commit() {
  if (!preview.value || !window.confirm(`确认只补充 ${preview.value.job.readyRows} 条可匹配记录的空字段？已有客户资料不会被覆盖。`)) return
  busy.value = true; error.value = ''
  try { preview.value = await api<EnrichmentPreview>(`/api/v1/admin/customer-enrichment-jobs/${preview.value.job.id}/commit`, { method: 'POST' }); success.value = `提交完成：实际更新 ${preview.value.job.updatedRows} 位客户。`; await loadJobs() }
  catch (reason) { error.value = message(reason, '补全任务提交失败') }
  finally { busy.value = false }
}
function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? `${reason.message}${reason.body.traceId ? `（${reason.body.traceId}）` : ''}` : fallback }
onMounted(loadJobs)
</script>

<template>
  <div class="page enrichment-page">
    <header class="page-header"><div><p class="eyebrow">CUSTOMER ENRICHMENT</p><h1>客户资料补全</h1><p class="muted">按“姓名＋专业＋年级”严格匹配，只填空字段；高中冲突、待核实所在地和重复客户全部跳过。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <section class="panel upload-panel"><div class="panel-title"><div><p class="eyebrow">UPLOAD</p><h2>上传 XLSX 并预览</h2></div></div><div class="upload-row"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="choose"><button class="primary-button" :disabled="busy || !file" @click="upload">{{ busy ? '解析中…' : '生成安全预览' }}</button></div><p class="muted">文件原文不长期保存；预览快照 24 小时后失效，相同文件在同一组织内保持幂等。</p></section>
    <section v-if="preview" class="panel"><div class="panel-title"><div><p class="eyebrow">PREVIEW</p><h2>{{ preview.job.filename }}</h2></div><div class="header-actions"><a :href="`/api/v1/admin/customer-enrichment-jobs/${preview.job.id}/report.csv`" class="ghost-button">下载行级报告</a><button v-if="preview.job.status === 'PREVIEWED'" class="primary-button" :disabled="busy || !preview.job.readyRows" @click="commit">确认提交</button></div></div>
      <div class="metric-grid compact-metrics"><article><span>总行数</span><strong>{{ preview.job.totalRows }}</strong></article><article><span>可补充</span><strong>{{ preview.job.readyRows }}</strong></article><article class="warm"><span>跳过</span><strong>{{ preview.job.skippedRows }}</strong></article><article class="danger"><span>冲突</span><strong>{{ preview.job.conflictRows }}</strong></article><article><span>已更新</span><strong>{{ preview.job.updatedRows }}</strong></article></div>
      <div class="table-scroll"><table class="mobile-card-table"><thead><tr><th>行</th><th>姓名</th><th>专业 / 年级</th><th>高中</th><th>生源地</th><th>映射</th><th>处理结果</th><th>说明</th></tr></thead><tbody><tr v-for="row in preview.rows" :key="row.rowNumber"><td data-label="行">{{ row.rowNumber }}</td><td data-label="姓名">{{ row.fullName }}</td><td data-label="专业 / 年级">{{ row.major }} / {{ row.grade }}</td><td data-label="高中">{{ row.highSchool }}</td><td data-label="生源地">{{ row.sourceLocation || '—' }}</td><td data-label="映射">{{ row.mappingStatus }}</td><td data-label="处理结果"><span :class="['row-status', row.status.toLowerCase()]">{{ statusLabel[row.status] || row.status }}</span></td><td data-label="说明">{{ row.message || '—' }}</td></tr></tbody></table></div>
    </section>
    <section class="panel"><div class="panel-title"><div><p class="eyebrow">HISTORY</p><h2>历史补全任务</h2></div></div><div v-if="!jobs.length" class="empty-state">还没有补全任务。</div><button v-for="job in jobs" :key="job.id" class="job-row" @click="open(job)"><span><strong>{{ job.filename }}</strong><small>{{ new Date(job.createdAt).toLocaleString() }} · SHA-256 {{ job.fileSha256.slice(0,12) }}…</small></span><span>{{ job.status }}</span><span>可补 {{ job.readyRows }} / 已更新 {{ job.updatedRows }}</span></button></section>
  </div>
</template>

<style scoped>
.upload-panel{margin-bottom:24px}.upload-row{display:flex;align-items:center;gap:16px;margin-bottom:12px}.compact-metrics{grid-template-columns:repeat(5,minmax(0,1fr));margin-bottom:24px}.table-scroll{overflow:auto;max-height:620px;border:1px solid #e5eaf3;border-radius:14px}.table-scroll table{width:100%;min-width:1100px;border-collapse:collapse}.table-scroll th,.table-scroll td{padding:12px;text-align:left;border-bottom:1px solid #edf0f6;vertical-align:top}.table-scroll th{position:sticky;top:0;background:#f7f9fd;z-index:1}.row-status{display:inline-flex;padding:4px 8px;border-radius:8px;background:#eef2f8}.row-status.ready,.row-status.updated{background:#daf6e7;color:#177448}.row-status.conflict,.row-status.ambiguous{background:#ffe4e4;color:#b52b2b}.job-row{display:grid;width:100%;grid-template-columns:1fr 120px 220px;align-items:center;gap:16px;padding:16px 0;border:0;border-bottom:1px solid #e7ebf3;background:transparent;text-align:left}.job-row span:first-child{display:grid;gap:4px}@media(max-width:900px){.upload-row{align-items:stretch;flex-direction:column}.upload-row input,.upload-row button{width:100%;min-height:44px}.compact-metrics{grid-template-columns:1fr 1fr}.table-scroll{max-height:none;padding:10px;overflow:visible}.table-scroll table{min-width:0}.job-row{grid-template-columns:1fr;padding:16px 4px}.header-actions{display:grid;width:100%;grid-template-columns:1fr}.header-actions>*{width:100%}}
</style>
