<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'
import type { Interaction } from '../types'

interface Classification {
  id: string
  revision: number
  level: 'A' | 'B' | 'C'
  recognitionReason: string
  potentialDemand: string
  aSignals: string[]
  evidenceInteractionIds: string[]
  source: string
  correctionReason?: string
  createdAt: string
}

interface DeadlineSnapshot {
  classificationLevel?: string
  computedDeadline?: string
  mode?: string
  noReplyExtensionUsed?: boolean
  calculatedAt?: string
}

const props = defineProps<{ customerId: string; interactions: Interaction[]; canEdit: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const history = ref<Classification[]>([])
const deadline = ref<DeadlineSnapshot>()
const loading = ref(true)
const editing = ref(false)
const busy = ref(false)
const error = ref('')
const form = reactive({ level: 'B' as 'A' | 'B' | 'C', recognitionReason: '', potentialDemand: '', aSignals: '', evidenceInteractionIds: [] as string[], correctionReason: '' })

async function load() {
  loading.value = true
  const [classificationResult, deadlineResult] = await Promise.allSettled([
    api<Classification[]>(`/api/v1/customers/${props.customerId}/classifications`),
    api<DeadlineSnapshot>(`/api/v1/customers/${props.customerId}/deadline-shadow`),
  ])
  history.value = classificationResult.status === 'fulfilled' && Array.isArray(classificationResult.value) ? classificationResult.value : []
  deadline.value = deadlineResult.status === 'fulfilled' ? deadlineResult.value : undefined
  loading.value = false
}

function begin() {
  const current = history.value[0]
  form.level = current?.level ?? 'B'
  form.recognitionReason = current?.recognitionReason ?? ''
  form.potentialDemand = current?.potentialDemand ?? ''
  form.aSignals = current?.aSignals?.join('\n') ?? ''
  form.evidenceInteractionIds = []
  form.correctionReason = ''
  error.value = ''
  editing.value = true
}

async function submit() {
  if (!form.evidenceInteractionIds.length) { error.value = '请至少选择一条沟通记录作为事实依据'; return }
  busy.value = true
  error.value = ''
  try {
    await api(`/api/v1/customers/${props.customerId}/classifications`, {
      method: 'POST',
      body: jsonBody({
        level: form.level,
        recognitionReason: form.recognitionReason,
        potentialDemand: form.potentialDemand,
        aSignals: form.aSignals.split('\n').map((item) => item.trim()).filter(Boolean),
        evidenceInteractionIds: form.evidenceInteractionIds,
        correctionReason: form.correctionReason || undefined,
      }),
    })
    editing.value = false
    await load()
    emit('changed')
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '客户分级保存失败'
  } finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="panel classification-panel" data-testid="customer-classification-panel">
    <header class="panel-title">
      <div><p class="eyebrow">MARKET CLASSIFICATION</p><h2>客户分级与归属期限</h2><p class="muted">A/B/C 以结构化认定和时间线事实为准，标签仅用于列表兼容。</p></div>
      <button v-if="canEdit && !editing" class="ghost-button" type="button" @click="begin">{{ history[0]?.source === 'LEGACY_IMPORT' ? '补充分级依据' : history.length ? '变更分级' : '建立分级' }}</button>
    </header>
    <div v-if="loading" class="empty-state small">正在读取分级记录…</div>
    <template v-else>
      <div v-if="history[0]" class="classification-current">
        <b :class="`level-${history[0].level.toLowerCase()}`">{{ history[0].level }}</b>
        <div><strong>{{ history[0].recognitionReason }}</strong><p>潜在需求：{{ history[0].potentialDemand }}</p><small><template v-if="history[0].source === 'LEGACY_IMPORT'">历史等级，待补充事实依据 · </template>第 {{ history[0].revision }} 版 · {{ dateTime(history[0].createdAt) }}</small></div>
        <aside><span>当前期限</span><strong>{{ dateTime(deadline?.computedDeadline) }}</strong><small>{{ deadline?.mode || '规则待生效' }}<template v-if="deadline?.noReplyExtensionUsed"> · 已使用未回复延长</template></small></aside>
      </div>
      <div v-else-if="!editing" class="empty-state small">尚未建立结构化客户分级。</div>
      <form v-if="editing" class="classification-form" @submit.prevent="submit">
        <div class="level-picker"><label v-for="level in ['A','B','C']" :key="level" :class="{ selected: form.level === level }"><input v-model="form.level" type="radio" :value="level"><b>{{ level }}</b><span>{{ level === 'A' ? '重点推进' : level === 'B' ? '持续培育' : '低频跟进' }}</span></label></div>
        <label>认定说明 *<textarea v-model.trim="form.recognitionReason" rows="3" maxlength="4000" required placeholder="写明客户当前阶段和判断原因"></textarea></label>
        <label>潜在需求 *<textarea v-model.trim="form.potentialDemand" rows="3" maxlength="4000" required placeholder="仅记录已确认或待核实需求"></textarea></label>
        <label v-if="form.level === 'A'">A 类信号（每行一项）<textarea v-model.trim="form.aSignals" rows="3" placeholder="例如：已明确时间表\n主动询问产品适配"></textarea></label>
        <fieldset><legend>事实依据 *（至少一条）</legend><label v-for="item in interactions" :key="item.id" class="evidence-option"><input v-model="form.evidenceInteractionIds" type="checkbox" :value="item.id"><span><strong>{{ item.channel }} · {{ item.summary }}</strong><small>{{ dateTime(item.contactedAt) }}</small></span></label><p v-if="!interactions.length" class="muted">请先记录真实沟通后再认定。</p></fieldset>
        <label v-if="history.length">变更说明<textarea v-model.trim="form.correctionReason" rows="2" maxlength="1000" placeholder="说明本次变更原因"></textarea></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <footer class="form-actions"><button class="ghost-button" type="button" @click="editing = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '提交认定' }}</button></footer>
      </form>
      <details v-if="history.length > 1" class="classification-history"><summary>查看历史变更（{{ history.length }}）</summary><ol><li v-for="item in history" :key="item.id"><b>{{ item.level }} 类 · 第 {{ item.revision }} 版</b><span>{{ item.recognitionReason }}</span><small>{{ dateTime(item.createdAt) }}{{ item.correctionReason ? ` · ${item.correctionReason}` : '' }}</small></li></ol></details>
    </template>
  </section>
</template>

<style scoped>
.classification-panel{display:grid;gap:20px}.classification-current{display:grid;grid-template-columns:auto 1fr minmax(220px,.45fr);align-items:center;gap:20px;padding:20px;border:1px solid #e4eaf5;border-radius:20px;background:linear-gradient(120deg,#fff,#f7f9ff)}.classification-current>b{display:grid;place-items:center;width:58px;height:58px;border-radius:18px;font-size:27px}.level-a{background:#ffe9e4;color:#bd3f2e}.level-b{background:#e7efff;color:#2e58c6}.level-c{background:#eef1f5;color:#637087}.classification-current p{margin:8px 0;color:#536078}.classification-current small{color:#8791a6}.classification-current aside{display:grid;gap:6px;padding-left:20px;border-left:1px solid #dfe5f0}.classification-current aside span{font-size:12px;color:#7e899f}.classification-form{display:grid;gap:16px}.classification-form>label{display:grid;gap:7px;font-weight:650}.level-picker{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.level-picker label{display:flex;align-items:center;gap:10px;padding:14px;border:1px solid #dfe5f0;border-radius:14px;cursor:pointer}.level-picker label.selected{border-color:#6687e8;background:#f2f5ff}.level-picker input{display:none}.level-picker b{font-size:20px}.classification-form fieldset{display:grid;gap:8px;border:1px solid #e1e7f1;border-radius:16px;padding:16px}.evidence-option{display:flex;gap:10px;padding:10px;border-radius:10px;background:#f7f9fc}.evidence-option span{display:grid;gap:3px}.evidence-option small{color:#7e899f}.classification-history ol{display:grid;gap:10px;margin:14px 0 0;padding:0;list-style:none}.classification-history li{display:grid;gap:4px;padding:12px;border-left:3px solid #9db3ef;background:#f8faff}.classification-history small{color:#7e899f}@media(max-width:800px){.classification-current{grid-template-columns:auto 1fr}.classification-current aside{grid-column:1/-1;border-left:0;border-top:1px solid #dfe5f0;padding:14px 0 0}.level-picker{grid-template-columns:1fr}}
</style>
