<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import type { ConsultationFailureReason, ConsultationSettings } from '../types'

interface FollowUpPolicy { aClassStaleEnabled: boolean; staleAfterHours: number; notifyAt: string; qqEnabled: boolean; abAutoReleaseEnabled: boolean; abAutoReleaseEffectiveFrom?: string; version: number }
interface AdminQqConnection { id: string; membershipId: string; memberName: string; status: string; maskedQq?: string; lastHeartbeatAt?: string; createdAt: string }

const policy = reactive<FollowUpPolicy>({ aClassStaleEnabled: true, staleAfterHours: 72, notifyAt: '09:00', qqEnabled: false, abAutoReleaseEnabled: false, version: 0 })
const consultation = reactive<ConsultationSettings>({ businessStart: '09:00', businessEnd: '21:00', minimumLeadMinutes: 30, allowedDurations: [30,45,60,90], dailyCapacity: 6, feedbackDueHours: 48, version: 0 })
const durationText = ref('30,45,60,90')
const reasons = ref<ConsultationFailureReason[]>([])
const connections = ref<AdminQqConnection[]>([])
const newReason = reactive({ code: '', label: '', active: true, sortOrder: 100 })
const busy = ref(false)
const error = ref('')
const success = ref('')

async function load() {
  try {
    const [loadedPolicy, loadedConsultation, loadedReasons, loadedConnections] = await Promise.all([
      api<FollowUpPolicy>('/api/v1/admin/follow-up-policy'),
      api<ConsultationSettings>('/api/v1/admin/consultation-settings'),
      api<ConsultationFailureReason[]>('/api/v1/consultation-failure-reasons?includeInactive=true'),
      api<AdminQqConnection[]>('/api/v1/admin/qq-connections'),
    ])
    Object.assign(policy, loadedPolicy); Object.assign(consultation, loadedConsultation)
    durationText.value = loadedConsultation.allowedDurations.join(',')
    reasons.value = loadedReasons; connections.value = loadedConnections
  } catch (reason) { error.value = message(reason, '管理设置加载失败') }
}

async function savePolicy() {
  await run(async () => {
    const saved = await api<FollowUpPolicy>('/api/v1/admin/follow-up-policy', { method: 'PUT', body: jsonBody({
      aClassStaleEnabled: policy.aClassStaleEnabled, staleAfterHours: policy.staleAfterHours,
      notifyAt: policy.notifyAt, qqEnabled: policy.qqEnabled,
      abAutoReleaseEnabled: policy.abAutoReleaseEnabled, expectedVersion: policy.version,
    }) })
    Object.assign(policy, saved); success.value = 'A 类跟进和 QQ 接入策略已保存。'
  })
}

async function saveConsultation() {
  const durations = durationText.value.split(/[,，\s]+/).map(Number).filter((item) => Number.isFinite(item))
  await run(async () => {
    const saved = await api<ConsultationSettings>('/api/v1/admin/consultation-settings', { method: 'PUT', body: jsonBody({
      ...consultation, allowedDurations: durations, expectedVersion: consultation.version, version: undefined,
    }) })
    Object.assign(consultation, saved); durationText.value = saved.allowedDurations.join(','); success.value = '咨询容量与反馈规则已保存。'
  })
}

async function addReason() {
  await run(async () => {
    await api('/api/v1/admin/consultation-reasons', { method: 'POST', body: jsonBody(newReason) })
    Object.assign(newReason, { code: '', label: '', active: true, sortOrder: 100 }); await load(); success.value = '失败原因已添加。'
  })
}

async function revokeConnection(item: AdminQqConnection) {
  if (!window.confirm(`确认撤销 ${item.memberName} 的 QQ 连接？该员工需要重新扫码才能继续导入。`)) return
  await run(async () => {
    await api(`/api/v1/admin/qq-connections/${item.id}`, { method: 'DELETE' })
    await load(); success.value = 'QQ 连接已撤销。'
  })
}

async function saveReason(reason: ConsultationFailureReason) {
  await run(async () => { await api(`/api/v1/admin/consultation-reasons/${reason.id}`, { method: 'PUT', body: jsonBody({ code: reason.code, label: reason.label, active: reason.active, sortOrder: reason.sortOrder }) }); success.value = '失败原因已更新。' })
}

async function run(action: () => Promise<void>) { busy.value = true; error.value = ''; success.value = ''; try { await action() } catch (reason) { error.value = message(reason, '保存失败') } finally { busy.value = false } }
function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? reason.message : fallback }
onMounted(load)
</script>

<template>
  <div class="page admin-follow-up-page">
    <header class="page-header"><div><p class="eyebrow">FOLLOW-UP GOVERNANCE</p><h1>跟进与咨询设置</h1><p class="muted">统一控制 A 类客户超时、QQ 接入、咨询预约容量和失败原因。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <div class="settings-grid">
      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">A-CLASS</p><h2>A 类双人跟进</h2></div></div>
        <div class="form-grid two">
          <label class="checkbox-line"><input v-model="policy.aClassStaleEnabled" type="checkbox">启用无有效时间线提醒</label>
          <label>无更新阈值（小时）<input v-model.number="policy.staleAfterHours" type="number" min="24" max="720" step="24"></label>
          <label>每日通知时间<input v-model="policy.notifyAt" type="time"></label>
          <label class="checkbox-line"><input v-model="policy.qqEnabled" type="checkbox">允许员工连接工作 QQ</label>
          <label class="checkbox-line span-2"><input v-model="policy.abAutoReleaseEnabled" type="checkbox">允许 A/B 客户因期限超时自动进入公共池</label>
          <p class="muted span-2">默认关闭；关闭不影响 A 类全屏提醒、期限统计和企业微信通知。重新开启后只处理开启时间之后到期的客户。</p>
        </div>
        <footer class="form-actions"><button class="primary-button" :disabled="busy" @click="savePolicy">保存跟进策略</button></footer>
      </section>
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">CAPACITY</p><h2>咨询预约规则</h2></div></div><div class="form-grid two"><label>营业开始<input v-model="consultation.businessStart" type="time"></label><label>营业结束<input v-model="consultation.businessEnd" type="time"></label><label>至少提前（分钟）<input v-model.number="consultation.minimumLeadMinutes" type="number" min="0"></label><label>每名顾问每日场次<input v-model.number="consultation.dailyCapacity" type="number" min="1" max="100"></label><label>结果回填时限（小时）<input v-model.number="consultation.feedbackDueHours" type="number" min="1" max="720"></label><label>允许时长（逗号分隔）<input v-model.trim="durationText" placeholder="30,45,60,90"></label></div><footer class="form-actions"><button class="primary-button" :disabled="busy" @click="saveConsultation">保存咨询规则</button></footer></section>
      <section class="panel span-2"><div class="panel-title"><div><p class="eyebrow">FAILURE REASONS</p><h2>未推进原因字典</h2></div></div><div class="reason-table"><div v-for="reason in reasons" :key="reason.id" class="reason-row"><code>{{ reason.code }}</code><input v-model.trim="reason.label" maxlength="160"><input v-model.number="reason.sortOrder" type="number"><label class="checkbox-line"><input v-model="reason.active" type="checkbox">启用</label><button class="ghost-button" @click="saveReason(reason)">保存</button></div><div class="reason-row new"><input v-model.trim="newReason.code" placeholder="CODE"><input v-model.trim="newReason.label" placeholder="显示名称"><input v-model.number="newReason.sortOrder" type="number"><label class="checkbox-line"><input v-model="newReason.active" type="checkbox">启用</label><button class="soft-button" :disabled="!newReason.code || !newReason.label" @click="addReason">添加</button></div></div></section>
      <section class="panel span-2"><div class="panel-title"><div><p class="eyebrow">QQ HEALTH</p><h2>QQ 连接健康</h2></div><span>管理员看不到 QCE Token</span></div><div v-if="!connections.length" class="empty-state">暂无员工连接。</div><div v-for="item in connections" :key="item.id" class="connection-row"><strong>{{ item.memberName }}</strong><span>{{ item.status }}</span><span>{{ item.maskedQq || '账号已脱敏' }}</span><small>{{ item.lastHeartbeatAt ? new Date(item.lastHeartbeatAt).toLocaleString() : '无心跳' }}</small><button class="danger-button" :disabled="busy" @click="revokeConnection(item)">撤销连接</button></div></section>
    </div>
  </div>
</template>

<style scoped>
.settings-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}.span-2{grid-column:span 2}.checkbox-line{display:flex;align-items:center;gap:9px}.checkbox-line input{width:auto}.reason-table{display:grid;gap:10px}.reason-row{display:grid;grid-template-columns:160px 1fr 100px 100px 90px;align-items:center;gap:10px}.reason-row code{overflow:hidden;text-overflow:ellipsis}.connection-row{display:grid;grid-template-columns:1fr 110px 150px 190px 110px;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid #e7ebf3}@media(max-width:900px){.settings-grid{grid-template-columns:1fr}.span-2{grid-column:auto}.reason-row,.connection-row{grid-template-columns:1fr;padding:14px;border:1px solid var(--line);border-radius:14px}.reason-row code{white-space:normal;overflow-wrap:anywhere}.form-actions{position:sticky;bottom:calc(72px + env(safe-area-inset-bottom));z-index:2;padding:10px 0;background:rgba(255,255,255,.95);backdrop-filter:blur(14px)}.form-actions button,.reason-row button,.connection-row button{width:100%;min-height:48px}.connection-row small{overflow-wrap:anywhere}}
</style>
