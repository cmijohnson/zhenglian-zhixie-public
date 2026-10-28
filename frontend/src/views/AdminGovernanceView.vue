<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'
import AdminMarketGovernancePanel from '../components/AdminMarketGovernancePanel.vue'

interface Hook { id: string; name: string; eventTypes: string[]; active: boolean; secretConfigured: boolean; version: number; createdAt: string; updatedAt: string }
interface Delivery { id: string; channelId: string; channelName: string; eventType: string; title: string; status: string; attemptCount: number; lastErrorCode?: string; nextAttemptAt: string; deliveredAt?: string; createdAt: string }

const eventOptions = [
  ['CUSTOMER_CREATED','新增客户'], ['CUSTOMER_QUALITY_CHANGED','客户等级升降'], ['CUSTOMER_POOL_RELEASED','客户释放'],
  ['CUSTOMER_TEMP_CLAIMED','临时认领'], ['CUSTOMER_RETURNED_TO_POOL','认领失败回池'], ['SECOND_LINE_REQUIRED','缺少A类二线'],
  ['SECOND_LINE_CONTACT_AUTH','二线联系授权'], ['SECOND_LINE_TAKEOVER','二线接任'], ['BUSINESS_WON_PENDING','成单待确认'],
  ['BUSINESS_WON','成单确认'], ['BUSINESS_LOST_TO_COMPETITOR','友商抢单'], ['MEMBER_DEPARTURE','成员离队'], ['GOVERNANCE_TASK','其他治理待办'],
  ['A_CLASS_STALE','A类客户3天未跟进'],
] as const

const hooks = ref<Hook[]>([])
const deliveries = ref<Delivery[]>([])
const editingId = ref('')
const form = reactive({ name: '', webhookUrl: '', eventTypes: [] as string[], active: true, expectedVersion: undefined as number | undefined })
const busy = ref(false); const error = ref(''); const success = ref('')
const failedCount = computed(() => deliveries.value.filter((item) => item.status === 'FAILED').length)

async function load() {
  try {
    const [loadedHooks, loadedDeliveries] = await Promise.all([
      api<Hook[]>('/api/v1/admin/wecom-webhooks'), api<Delivery[]>('/api/v1/admin/wecom-webhooks/deliveries?size=100'),
    ])
    hooks.value = loadedHooks; deliveries.value = loadedDeliveries
  } catch (reason) { error.value = message(reason, '治理通知设置加载失败') }
}

function edit(item: Hook) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, webhookUrl: '', eventTypes: [...item.eventTypes], active: item.active, expectedVersion: item.version })
}

function reset() { editingId.value = ''; Object.assign(form, { name: '', webhookUrl: '', eventTypes: [], active: true, expectedVersion: undefined }) }

async function save() {
  await run(async () => {
    const path = editingId.value ? `/api/v1/admin/wecom-webhooks/${editingId.value}` : '/api/v1/admin/wecom-webhooks'
    const body = { ...form, webhookUrl: form.webhookUrl || (editingId.value ? undefined : '') }
    await api(path, { method: editingId.value ? 'PUT' : 'POST', body: jsonBody(body) })
    success.value = editingId.value ? 'Webhook 订阅已更新，密钥未填写时保持原值。' : 'Webhook 通道已创建。'
    reset(); await load()
  })
}

async function disable(item: Hook) {
  if (!window.confirm(`确认停用“${item.name}”？待发送通知不会再投递到该群。`)) return
  await run(async () => { await api(`/api/v1/admin/wecom-webhooks/${item.id}`, { method: 'DELETE' }); await load(); success.value = 'Webhook 已停用。' })
}

async function retry(item: Delivery) {
  await run(async () => { await api(`/api/v1/admin/wecom-webhooks/deliveries/${item.id}/retry`, { method: 'POST' }); await load(); success.value = '投递已重新进入队列。' })
}

async function run(action: () => Promise<void>) { busy.value = true; error.value = ''; success.value = ''; try { await action() } catch (reason) { error.value = message(reason, '操作失败') } finally { busy.value = false } }
function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? reason.message : fallback }
onMounted(load)
</script>

<template>
  <div class="page governance-page">
    <header class="page-header"><div><p class="eyebrow">MARKET GOVERNANCE</p><h1>市场治理台</h1><p class="muted">站内通知是唯一事实来源；企业微信群只接收脱敏摘要和系统链接。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>
    <AdminMarketGovernancePanel />
    <div class="metric-grid mini"><article><span>Webhook 通道</span><strong>{{ hooks.filter((item) => item.active).length }}</strong><small>当前启用</small></article><article class="danger"><span>投递失败</span><strong>{{ failedCount }}</strong><small>可手动重发</small></article><article><span>事件类型</span><strong>{{ eventOptions.length }}</strong><small>按通道订阅</small></article></div>
    <div class="governance-grid">
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">WECOM CHANNELS</p><h2>企业微信通知通道</h2></div><button v-if="editingId" class="text-button" @click="reset">取消编辑</button></div>
        <div class="hook-list"><article v-for="item in hooks" :key="item.id"><div><strong>{{ item.name }}</strong><small>{{ item.active ? '启用' : '停用' }} · {{ item.eventTypes.length }} 类事件 · 密钥已安全保存</small></div><button class="ghost-button" @click="edit(item)">编辑</button><button v-if="item.active" class="danger-link" @click="disable(item)">停用</button></article><div v-if="!hooks.length" class="empty-state">尚未配置企业微信通知。</div></div>
      </section>
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">CHANNEL EDITOR</p><h2>{{ editingId ? '编辑通道' : '新增通道' }}</h2></div></div>
        <div class="form-grid"><label>通道名称<input v-model.trim="form.name" maxlength="120" placeholder="例如：客户等级变更群"></label><label>企业微信机器人 Webhook<input v-model.trim="form.webhookUrl" type="password" autocomplete="off" :placeholder="editingId ? '留空表示不更换' : 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…'"><small>仅允许企业微信官方 HTTPS 地址，保存后不再回显。</small></label><label class="checkbox-line"><input v-model="form.active" type="checkbox">启用此通道</label></div>
        <div class="event-picker"><label v-for="option in eventOptions" :key="option[0]"><input v-model="form.eventTypes" type="checkbox" :value="option[0]"><span>{{ option[1] }}</span></label></div>
        <footer class="form-actions"><button class="primary-button" :disabled="busy || !form.name || !form.eventTypes.length || (!editingId && !form.webhookUrl)" @click="save">{{ editingId ? '保存修改' : '创建通道' }}</button></footer>
      </section>
      <section class="panel span-2"><div class="panel-title"><div><p class="eyebrow">DELIVERY LEDGER</p><h2>Webhook 投递台账</h2></div><span>最多自动尝试 3 次</span></div>
        <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>时间</th><th>通道</th><th>事件</th><th>标题</th><th>状态</th><th>尝试</th><th>错误</th><th>操作</th></tr></thead><tbody><tr v-for="item in deliveries" :key="item.id"><td data-label="时间">{{ dateTime(item.createdAt) }}</td><td data-label="通道">{{ item.channelName }}</td><td data-label="事件"><code>{{ item.eventType }}</code></td><td data-label="标题">{{ item.title }}</td><td data-label="状态"><span :class="['table-status', item.status === 'DELIVERED' ? 'ready' : item.status === 'FAILED' ? 'failed' : 'pending']">{{ item.status }}</span></td><td data-label="尝试">{{ item.attemptCount }}/3</td><td data-label="错误">{{ item.lastErrorCode || '—' }}</td><td data-label="操作"><button v-if="item.status === 'FAILED'" class="text-button retry-button" @click="retry(item)">重发</button><span v-else>—</span></td></tr></tbody></table><div v-if="!deliveries.length" class="empty-state">暂无投递记录。</div></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.governance-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}.hook-list{display:grid;gap:10px}.hook-list article{display:grid;grid-template-columns:1fr auto auto;align-items:center;gap:10px;padding:14px;border:1px solid var(--line);border-radius:12px}.hook-list small{display:block;margin-top:5px;color:var(--muted)}.hook-list .danger-link{margin:0}.checkbox-line{display:flex!important;align-items:center;gap:9px}.checkbox-line input{width:auto}.event-picker{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:18px}.event-picker label{display:flex;align-items:center;gap:8px;padding:9px;border:1px solid #e8ebf2;border-radius:9px;font-size:11px}.event-picker input{width:auto}.form-grid small{color:var(--muted);font-weight:400}@media(max-width:900px){.governance-grid{grid-template-columns:1fr}.span-2{grid-column:auto}.event-picker{grid-template-columns:1fr}.hook-list article{grid-template-columns:1fr 1fr}.hook-list article>div{grid-column:1/-1}.hook-list article button{min-height:44px}.table-wrap{padding:10px;overflow:visible}.retry-button{min-height:44px;padding:0 16px;border-radius:10px;background:#eef3ff}}
</style>
