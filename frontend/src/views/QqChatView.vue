<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, jsonBody } from '../lib/api'
import { idempotencyKey, localInput, toIso } from '../lib/format'
import type { Customer, PageResponse, QqConnection, QqConversationDraft } from '../types'

interface WorkbenchBootstrap {
  status: string
  maskedQq?: string
  workbenchUrl: string
  websocketUrl: string
  rawRetentionDays: number
  exportQuotaBytes: number
}

interface WorkbenchSync {
  chatType: number
  peerUid: string
  peerQq: string
  sessionName: string
  rangeStart: number
  rangeEnd: number
  messageCount: number
}

interface PendingSync {
  sync: WorkbenchSync
  targetSenderUid: string
  targetSenderQq: string
  groupContextConfirmed: boolean
}

interface GroupMember {
  uid: string
  uin?: string
  nick: string
  cardName?: string
  role: 'owner' | 'admin' | 'member'
}

interface QqPreview {
  importId: string
  customerId: string
  chatType: number
  peerUid: string
  peerQq: string
  sessionName?: string
  targetSenderUid?: string
  targetSenderQq?: string
  groupContextConfirmed: boolean
  messageCount: number
  rangeStart: string
  rangeEnd: string
  expiresAt: string
  draft: QqConversationDraft
  messages: unknown[]
}

const RESUME_KEY = 'yingge.qq-workbench.pending-sync.v1'
const route = useRoute()
const router = useRouter()
const connection = ref<QqConnection | null>()
const bootstrap = ref<WorkbenchBootstrap>()
const workbench = ref<HTMLIFrameElement>()
const workbenchRevision = ref(0)
const workbenchRecoveryInFlight = ref(false)
let lastWorkbenchRecoveryAt = 0
const WORKBENCH_RECOVERY_COOLDOWN_MS = 30_000
const sync = ref<WorkbenchSync>()
const drawerOpen = ref(false)
const customerOptions = ref<Customer[]>([])
const selectedCustomerId = ref('')
const customerSearch = ref('')
const customerSearchBusy = ref(false)
const exactMatchCount = ref(0)
const groupMembers = ref<GroupMember[]>([])
const groupMemberSearch = ref('')
const targetSenderUid = ref('')
const targetSenderQq = ref('')
const groupContextConfirmed = ref(false)
const bindMissingQq = ref(false)
const preview = ref<QqPreview>()
const range = reactive({ start: localInput(new Date(Date.now() - 7 * 86400000).toISOString()), end: localInput() })
const draft = reactive<QqConversationDraft>({ summary: '', customerQuotes: [], goalsMotivation: '', actionsTaken: '', mainConcerns: '', decisionParticipants: '', productFit: '', suggestedFocus: '', explicitTaboos: '', outcome: '', nextStep: '', uncertainties: [] })
const busy = ref(false)
const error = ref('')
const success = ref('')
const optionalDraftOpen = ref(!isCompactViewport())

function isCompactViewport() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(max-width: 760px)').matches
}

const connected = computed(() => connection.value?.status === 'CONNECTED')
const isGroup = computed(() => sync.value?.chatType === 2)
const chatTypeLabel = computed(() => isGroup.value ? '群聊' : sync.value?.chatType === 1 ? '好友私聊' : '临时会话 / 服务号')
const selectedCustomer = computed(() => customerOptions.value.find((item) => item.id === selectedCustomerId.value))
const normalizedTargetQq = computed(() => normalizeQq(isGroup.value ? targetSenderQq.value : (targetSenderQq.value || sync.value?.peerQq || '')))
const customerConflict = computed(() => {
  const customerQq = normalizeQq(selectedCustomer.value?.qqId || '')
  return Boolean(customerQq && normalizedTargetQq.value && customerQq !== normalizedTargetQq.value)
})
const filteredGroupMembers = computed(() => {
  const term = groupMemberSearch.value.trim().toLowerCase()
  if (!term) return groupMembers.value
  return groupMembers.value.filter((member) => [member.cardName, member.nick, member.uin, member.uid]
    .some((value) => String(value || '').toLowerCase().includes(term)))
})
const canPreview = computed(() => Boolean(sync.value && selectedCustomerId.value && normalizedTargetQq.value
  && !customerConflict.value && (!isGroup.value || (targetSenderUid.value && groupContextConfirmed.value))))

async function load() {
  error.value = ''
  try {
    connection.value = await api<QqConnection | null>('/api/v1/qq-connections/me')
    if (connected.value) bootstrap.value = await api<WorkbenchBootstrap>('/api/v1/qq-workbench/bootstrap')
    await restoreAfterCustomerCreate()
  } catch (reason) { error.value = message(reason, 'QQ 工作台加载失败') }
}

async function connect() {
  busy.value = true; error.value = ''
  try { connection.value = await api<QqConnection>('/api/v1/qq-connections', { method: 'POST' }) }
  catch (reason) { error.value = message(reason, '无法创建 QQ 连接') }
  finally { busy.value = false }
}

async function refresh(): Promise<boolean> {
  error.value = ''; success.value = ''
  try {
    connection.value = await api<QqConnection | null>('/api/v1/qq-connections/me')
    bootstrap.value = connected.value ? await api<WorkbenchBootstrap>('/api/v1/qq-workbench/bootstrap') : undefined
    if (connected.value) workbenchRevision.value += 1
    return true
  } catch (reason) {
    bootstrap.value = undefined
    error.value = message(reason, '连接状态刷新失败')
    return false
  }
}

async function revoke() {
  if (!window.confirm('确认撤销当前工作 QQ 连接？之后需要重新扫码，未完成导出也会停止。')) return
  try {
    await api<void>('/api/v1/qq-connections/me', { method: 'DELETE' })
    connection.value = null
    bootstrap.value = undefined
    closeDrawer()
  } catch (reason) { error.value = message(reason, '撤销 QQ 连接失败') }
}

function handleWorkbenchMessage(event: MessageEvent) {
  if (event.origin !== window.location.origin || event.source !== workbench.value?.contentWindow) return
  const data = event.data as { type?: string; version?: number; payload?: unknown }
  if (data?.type === 'yingge:qq-auth-required') {
    void recoverWorkbenchConnection()
    return
  }
  if (data?.type !== 'yingge:qq-sync' || data.version !== 1 || !validSync(data.payload)) return
  void prepareSync(data.payload)
}

async function recoverWorkbenchConnection() {
  const now = Date.now()
  if (workbenchRecoveryInFlight.value || now - lastWorkbenchRecoveryAt < WORKBENCH_RECOVERY_COOLDOWN_MS) return
  workbenchRecoveryInFlight.value = true
  lastWorkbenchRecoveryAt = now
  try {
    if (!await refresh()) return
    if (connected.value) {
      success.value = 'QQ 工作台会话已刷新，正在重新加载。'
    } else {
      error.value = '工作 QQ 连接已中断，请重新扫码后再进入工作台。'
    }
  } finally {
    workbenchRecoveryInFlight.value = false
  }
}

async function prepareSync(payload: WorkbenchSync) {
  sync.value = { ...payload, peerQq: normalizeQq(payload.peerQq) }
  preview.value = undefined
  success.value = ''
  selectedCustomerId.value = ''
  customerOptions.value = []
  exactMatchCount.value = 0
  targetSenderUid.value = ''
  targetSenderQq.value = isGroup.value ? '' : sync.value.peerQq
  groupContextConfirmed.value = false
  bindMissingQq.value = false
  const now = Date.now()
  range.start = localInput(new Date(payload.rangeStart > 0 ? payload.rangeStart : now - 7 * 86400000).toISOString())
  range.end = localInput(new Date(payload.rangeEnd > 0 ? payload.rangeEnd : now).toISOString())
  drawerOpen.value = true
  try {
    if (isGroup.value) await loadGroupMembers(payload.peerUid)
    else await matchCustomer(sync.value.peerQq)
  } catch (reason) { error.value = message(reason, '无法读取 CRM 匹配信息') }
  await nextTick()
}

async function loadGroupMembers(groupCode: string) {
  const response = await api<{ success?: boolean; data?: GroupMember[] }>(`/api/v1/qq-workbench/qce/api/groups/${encodeURIComponent(groupCode)}/members`)
  groupMembers.value = Array.isArray(response.data) ? response.data : []
}

async function chooseGroupMember() {
  const member = groupMembers.value.find((item) => item.uid === targetSenderUid.value)
  targetSenderQq.value = normalizeQq(member?.uin || '')
  selectedCustomerId.value = ''
  customerOptions.value = []
  exactMatchCount.value = 0
  bindMissingQq.value = false
  if (targetSenderQq.value) await matchCustomer(targetSenderQq.value)
}

async function matchCustomer(qq: string) {
  const normalized = normalizeQq(qq)
  if (!normalized) return
  const page = await api<PageResponse<Customer>>(`/api/v1/customers?search=${encodeURIComponent(normalized)}&size=100&sort=updatedAt,desc`)
  customerOptions.value = page.items
  const exact = page.items.filter((item) => normalizeQq(item.qqId || '') === normalized)
  exactMatchCount.value = exact.length
  if (exact.length === 1) selectedCustomerId.value = exact[0].id
}

async function searchCustomers() {
  const term = customerSearch.value.trim()
  if (!term) return
  customerSearchBusy.value = true
  try {
    const page = await api<PageResponse<Customer>>(`/api/v1/customers?search=${encodeURIComponent(term)}&size=100&sort=updatedAt,desc`)
    customerOptions.value = page.items
    if (page.items.length === 1) selectedCustomerId.value = page.items[0].id
  } catch (reason) { error.value = message(reason, '客户搜索失败') }
  finally { customerSearchBusy.value = false }
}

function quickCreate() {
  if (!sync.value || !normalizedTargetQq.value) return
  const pending: PendingSync = {
    sync: sync.value,
    targetSenderUid: targetSenderUid.value,
    targetSenderQq: normalizedTargetQq.value,
    groupContextConfirmed: groupContextConfirmed.value,
  }
  sessionStorage.setItem(RESUME_KEY, JSON.stringify(pending))
  const member = groupMembers.value.find((item) => item.uid === targetSenderUid.value)
  void router.push({
    path: '/customers/new',
    query: {
      nickname: member?.cardName || member?.nick || sync.value.sessionName,
      qqId: normalizedTargetQq.value,
      sourceChannel: 'QQ',
      returnTo: '/qq-chat',
    },
  })
}

async function restoreAfterCustomerCreate() {
  if (route.query.resume !== '1' || typeof route.query.customerId !== 'string') return
  const pending = sessionStorage.getItem(RESUME_KEY)
  if (!pending) return
  sessionStorage.removeItem(RESUME_KEY)
  try {
    const stored = JSON.parse(pending) as Partial<PendingSync>
    if (!validSync(stored.sync)) return
    await prepareSync(stored.sync)
    targetSenderUid.value = typeof stored.targetSenderUid === 'string' ? stored.targetSenderUid : ''
    targetSenderQq.value = typeof stored.targetSenderQq === 'string' ? stored.targetSenderQq : stored.sync.peerQq
    groupContextConfirmed.value = stored.groupContextConfirmed === true
    const customer = await api<Customer>(`/api/v1/customers/${route.query.customerId}`)
    customerOptions.value = [customer]
    selectedCustomerId.value = customer.id
    bindMissingQq.value = !customer.qqId
    await router.replace('/qq-chat')
  } catch { error.value = '快速建档已完成，但 QQ 同步上下文无法恢复，请从工作台再次点击“同步到客户云”。' }
}

async function createPreview() {
  if (!canPreview.value || !sync.value) { error.value = '请完成目标成员、客户档案和必要确认后再生成草稿。'; return }
  busy.value = true; error.value = ''; success.value = ''
  try {
    const value = await api<QqPreview>('/api/v1/qq-imports/preview', {
      method: 'POST',
      body: jsonBody({
        customerId: selectedCustomerId.value,
        chatType: sync.value.chatType,
        peerUid: sync.value.peerUid,
        peerQq: sync.value.peerQq || normalizedTargetQq.value,
        sessionName: sync.value.sessionName,
        targetSenderUid: isGroup.value ? targetSenderUid.value : sync.value.peerUid,
        targetSenderQq: normalizedTargetQq.value,
        groupContextConfirmed: isGroup.value && groupContextConfirmed.value,
        rangeStart: toIso(range.start),
        rangeEnd: toIso(range.end),
        bindMissingQq: bindMissingQq.value,
      }),
    })
    preview.value = value
    optionalDraftOpen.value = !isCompactViewport()
    Object.assign(draft, value.draft, {
      customerQuotes: [...(value.draft.customerQuotes ?? [])],
      uncertainties: [...(value.draft.uncertainties ?? [])],
    })
  } catch (reason) { error.value = message(reason, 'QQ 对话读取或智能整理失败') }
  finally { busy.value = false }
}

async function confirm() {
  if (!preview.value) return
  busy.value = true; error.value = ''
  try {
    const result = await api<{ interactionId: string }>(`/api/v1/qq-imports/${preview.value.importId}/confirm`, {
      method: 'POST',
      body: jsonBody({ idempotencyKey: idempotencyKey(), draft: { ...draft, nextFollowUpAt: toIso(draft.nextFollowUpAt) } }),
    })
    success.value = 'QQ 对话已写入同一份客户档案的统一时间线。'
    sessionStorage.removeItem(RESUME_KEY)
    await router.push(`/customers/${preview.value.customerId}#interaction-${result.interactionId}`)
  } catch (reason) { error.value = message(reason, '确认写入失败') }
  finally { busy.value = false }
}

function closeDrawer() {
  drawerOpen.value = false
  sync.value = undefined
  preview.value = undefined
  groupMembers.value = []
}

function validSync(value: unknown): value is WorkbenchSync {
  if (!value || typeof value !== 'object') return false
  const item = value as Partial<WorkbenchSync>
  return Number.isInteger(item.chatType) && Number(item.chatType) > 0
    && typeof item.peerUid === 'string' && item.peerUid.length > 0 && item.peerUid.length <= 256
    && typeof item.peerQq === 'string' && item.peerQq.length <= 120
    && typeof item.sessionName === 'string' && item.sessionName.length <= 240
    && typeof item.rangeStart === 'number' && Number.isFinite(item.rangeStart)
    && typeof item.rangeEnd === 'number' && Number.isFinite(item.rangeEnd)
    && typeof item.messageCount === 'number' && item.messageCount >= 0
}

function normalizeQq(value: string) { return value.trim().replace(/\s+/g, '') }
function split(value: string) { return value.split(/\n|；/).map((item) => item.trim()).filter(Boolean) }
function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? `${reason.message}${reason.body.traceId ? `（${reason.body.traceId}）` : ''}` : fallback }

onMounted(() => { window.addEventListener('message', handleWorkbenchMessage); void load() })
onBeforeUnmount(() => window.removeEventListener('message', handleWorkbenchMessage))
</script>

<template>
  <div class="page qq-chat-page">
    <header class="page-header hero-header">
      <div><p class="eyebrow">QQ WORKBENCH</p><h1>QQ 对话工作台</h1><p class="muted">先搜索并预览好友、临时会话或群聊；需要入档时再匹配客户、生成 AI 草稿并由你确认。</p></div>
      <div v-if="connection" class="header-actions"><span class="status-pill">{{ connection.status }} · {{ connection.maskedQq || '账号已脱敏' }}</span><button class="ghost-button" @click="refresh">刷新连接</button><button class="danger-link" @click="revoke">撤销连接</button></div>
    </header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>

    <section v-if="!connection" class="panel empty-workbench">
      <p class="eyebrow">PRIVATE QQ INSTANCE</p><h2>连接你的工作 QQ</h2><p class="muted">每名员工使用独立实例和独立数据卷。二维码之外的长期凭据不会进入浏览器。</p>
      <button class="primary-button" :disabled="busy" @click="connect">创建连接并显示二维码</button>
    </section>

    <section v-else-if="!connected" class="panel pairing-workbench">
      <img v-if="connection.qrCodeDataUrl" :src="connection.qrCodeDataUrl" alt="QQ 登录二维码" class="qq-qr">
      <div><p class="eyebrow">PAIRING</p><h2>使用工作 QQ 扫码</h2><p class="muted">扫码完成后点击刷新；此二维码为一次性连接凭据。</p><button class="primary-button" @click="refresh">我已扫码，刷新状态</button></div>
    </section>

    <section v-else class="workbench-shell" aria-label="QQ Chat Exporter 工作台">
      <div class="workbench-toolbar"><div><strong>独立 QQ 实例已连接</strong><span>完整浏览、搜索、媒体、任务与导出均作用于你自己的实例</span></div><span>原文留存 {{ bootstrap?.rawRetentionDays ?? 30 }} 天 · 导出配额 {{ Math.round((bootstrap?.exportQuotaBytes ?? 0) / 1073741824) }} GB</span></div>
      <iframe :key="workbenchRevision" ref="workbench" :src="bootstrap?.workbenchUrl || '/qq-workbench/'" title="QQ Chat Exporter 完整工作台" class="workbench-frame" sandbox="allow-same-origin allow-scripts allow-forms allow-downloads allow-popups allow-modals"></iframe>
    </section>

    <div v-if="drawerOpen" class="qq-drawer-backdrop" @click.self="closeDrawer">
      <aside class="qq-crm-drawer" role="dialog" aria-modal="true" aria-label="同步 QQ 对话到客户云">
        <header class="drawer-header"><div><p class="eyebrow">SYNC TO CRM</p><h2>{{ sync?.sessionName || 'QQ 会话' }}</h2><p>{{ chatTypeLabel }} · 工作台当前约 {{ sync?.messageCount ?? 0 }} 条消息</p></div><button class="icon-button" aria-label="关闭" @click="closeDrawer">×</button></header>

        <div class="drawer-scroll">
          <section v-if="!preview" class="drawer-section">
            <h3>1. 确认目标客户</h3>
            <template v-if="isGroup">
              <label>搜索群成员<input v-model.trim="groupMemberSearch" placeholder="昵称、群名片或 QQ 号"></label>
              <label>目标成员 *<select v-model="targetSenderUid" @change="chooseGroupMember"><option value="">请选择本次要入档的客户</option><option v-for="member in filteredGroupMembers" :key="member.uid" :value="member.uid">{{ member.cardName || member.nick }} · {{ member.uin || 'QQ 未公开' }}</option></select></label>
            </template>
            <label>目标客户 QQ *<input v-model.trim="targetSenderQq" inputmode="numeric" :readonly="isGroup && Boolean(targetSenderUid)" placeholder="用于精确匹配 CRM 客户" @change="matchCustomer(targetSenderQq)"></label>
            <p v-if="exactMatchCount > 1" class="inline-warning">发现 {{ exactMatchCount }} 份相同 QQ 档案，系统不会自动关联，请先处理重复档案。</p>
            <p v-else-if="exactMatchCount === 1" class="inline-success">已按 QQ 唯一匹配到客户档案。</p>
            <div class="customer-search-row"><input v-model.trim="customerSearch" placeholder="也可按姓名、学校或联系方式搜索" @keydown.enter.prevent="searchCustomers"><button class="soft-button" :disabled="customerSearchBusy" @click="searchCustomers">{{ customerSearchBusy ? '搜索中…' : '搜索' }}</button></div>
            <label>CRM 客户 *<select v-model="selectedCustomerId" @change="bindMissingQq = !selectedCustomer?.qqId"><option value="">请选择客户档案</option><option v-for="customer in customerOptions" :key="customer.id" :value="customer.id">{{ customer.fullName }} · QQ {{ customer.qqId || '未填写' }}</option></select></label>
            <p v-if="customerConflict" class="inline-warning">所选客户档案 QQ 与目标成员不一致。为避免串档，请先修改档案或重新选择。</p>
            <label v-if="selectedCustomer && !selectedCustomer.qqId" class="checkbox-line"><input v-model="bindMissingQq" type="checkbox"> 我已核对身份，同意将 QQ {{ normalizedTargetQq || '—' }} 绑定到该客户档案</label>
            <button v-if="normalizedTargetQq && exactMatchCount === 0" class="ghost-button full-button" @click="quickCreate">＋ 使用当前 QQ 快速新建客户</button>
          </section>

          <section v-if="!preview" class="drawer-section">
            <h3>2. 选择入档范围</h3>
            <div class="form-grid two"><label>开始时间<input v-model="range.start" type="datetime-local"></label><label>结束时间<input v-model="range.end" type="datetime-local"></label></div>
            <label v-if="isGroup" class="checkbox-line context-confirm"><input v-model="groupContextConfirmed" type="checkbox"><span><strong>包含第三方对话，已确认具有业务必要性</strong><small>AI 只能把目标成员发言作为客户证据，其他群成员只作为上下文。</small></span></label>
            <button class="primary-button full-button" :disabled="busy || !canPreview" @click="createPreview">{{ busy ? '读取并生成草稿中…' : '生成 AI 草稿并准备入档' }}</button>
          </section>

          <section v-else class="drawer-section draft-section">
            <div class="draft-summary"><span>{{ preview.messageCount }} 条消息</span><span>原文 {{ new Date(preview.expiresAt).toLocaleDateString() }} 后自动清理</span></div>
            <h3>3. 人工核对结构化草稿</h3>
            <label>对话摘要 *<textarea v-model.trim="draft.summary" rows="4"></textarea></label>
            <label>客户关键原话<textarea :value="draft.customerQuotes.join('\n')" rows="4" @input="draft.customerQuotes = split(($event.target as HTMLTextAreaElement).value)"></textarea></label>
            <div class="draft-disclosure"><button type="button" :aria-expanded="optionalDraftOpen" @click="optionalDraftOpen = !optionalDraftOpen"><span><strong>画像分析字段</strong><small>目标、行动、顾虑、参与人、适配与禁忌</small></span><b>{{ optionalDraftOpen ? '收起' : '展开核对' }}</b></button><div v-show="optionalDraftOpen" class="form-grid two"><label>目标与动机<textarea v-model.trim="draft.goalsMotivation" rows="3"></textarea></label><label>已做行动<textarea v-model.trim="draft.actionsTaken" rows="3"></textarea></label><label>主要顾虑<textarea v-model.trim="draft.mainConcerns" rows="3"></textarea></label><label>决策参与人<textarea v-model.trim="draft.decisionParticipants" rows="3"></textarea></label><label>产品适配<textarea v-model.trim="draft.productFit" rows="3"></textarea></label><label>建议重点<textarea v-model.trim="draft.suggestedFocus" rows="3"></textarea></label><label>明确禁忌<textarea v-model.trim="draft.explicitTaboos" rows="3"></textarea></label></div></div>
            <label>沟通结果<textarea v-model.trim="draft.outcome" rows="3"></textarea></label>
            <label>下一步<textarea v-model.trim="draft.nextStep" rows="3"></textarea></label>
            <label>建议跟进时间<input v-model="draft.nextFollowUpAt" type="datetime-local"></label>
            <label>待核实事项<textarea :value="draft.uncertainties.join('\n')" rows="3" @input="draft.uncertainties = split(($event.target as HTMLTextAreaElement).value)"></textarea></label>
            <div class="drawer-actions"><button class="ghost-button" @click="preview = undefined">返回修改范围</button><button class="primary-button" :disabled="busy || !draft.summary" @click="confirm">确认并写入统一时间线</button></div>
          </section>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.qq-chat-page{max-width:none}.status-pill{padding:8px 14px;border-radius:999px;background:#eef3ff;color:#2e55c8}.empty-workbench{padding:56px;text-align:center}.empty-workbench .primary-button{margin-top:20px}.pairing-workbench{display:flex;align-items:center;justify-content:center;gap:44px;padding:42px}.qq-qr{width:240px;height:240px;object-fit:contain;border:1px solid #dfe5f2;border-radius:22px;background:#fff}.workbench-shell{overflow:hidden;border:1px solid rgba(126,145,183,.2);border-radius:24px;background:#fff;box-shadow:0 22px 70px rgba(35,52,91,.12)}.workbench-toolbar{min-height:58px;padding:12px 20px;display:flex;align-items:center;justify-content:space-between;gap:18px;border-bottom:1px solid #e8edf7;background:linear-gradient(90deg,#f9fbff,#f3f7ff);font-size:13px;color:#68748c}.workbench-toolbar div{display:flex;gap:12px;align-items:center}.workbench-toolbar strong{color:#17213a}.workbench-frame{display:block;width:100%;height:calc(100dvh - 235px);min-height:680px;border:0;background:#f7f8fa}.qq-drawer-backdrop{position:fixed;inset:0;z-index:90;background:rgba(13,21,39,.38);backdrop-filter:blur(5px);display:flex;justify-content:flex-end}.qq-crm-drawer{width:min(680px,100vw);height:100dvh;background:#f7f9fd;box-shadow:-30px 0 80px rgba(10,20,46,.24);display:flex;flex-direction:column}.drawer-header{padding:28px 30px 22px;background:#fff;border-bottom:1px solid #e4eaf4;display:flex;justify-content:space-between;gap:20px}.drawer-header h2{margin:2px 0 5px;overflow-wrap:anywhere}.drawer-header p{margin:0;color:#7c879d}.icon-button{width:44px;height:44px;flex:0 0 44px;border:0;border-radius:50%;font-size:26px;background:#f0f3f9;cursor:pointer}.drawer-scroll{padding:24px 28px 42px;overflow-y:auto;overscroll-behavior:contain}.drawer-section{padding:24px;margin-bottom:18px;border:1px solid #e2e8f3;border-radius:20px;background:#fff;box-shadow:0 12px 32px rgba(45,61,98,.06)}.drawer-section h3{margin:0 0 18px;color:#18213a}.drawer-section label{display:grid;gap:7px;margin:14px 0;color:#4e5a72;font-weight:600}.drawer-section input,.drawer-section select,.drawer-section textarea{width:100%;box-sizing:border-box}.customer-search-row{display:grid;grid-template-columns:1fr auto;gap:10px;margin:14px 0}.checkbox-line{display:flex!important;align-items:flex-start;gap:10px;font-weight:500!important}.checkbox-line input{width:auto;margin-top:3px}.context-confirm{padding:14px;border-radius:14px;background:#fff8e9;border:1px solid #f4dfae}.context-confirm span{display:grid;gap:4px}.context-confirm small{font-weight:400;line-height:1.5;color:#766744}.inline-warning,.inline-success{padding:10px 12px;border-radius:10px;font-size:13px;overflow-wrap:anywhere}.inline-warning{background:#fff1ef;color:#a5392b}.inline-success{background:#edf9f2;color:#237a4b}.full-button{width:100%;justify-content:center}.draft-summary{display:flex;justify-content:space-between;gap:12px;padding:10px 12px;margin-bottom:18px;border-radius:10px;background:#eef3ff;color:#3e5fb5;font-size:13px}.draft-disclosure{margin:16px 0;border:1px solid #e1e7f2;border-radius:14px;overflow:hidden}.draft-disclosure>button{display:flex;width:100%;min-height:58px;padding:11px 13px;align-items:center;justify-content:space-between;gap:12px;border:0;background:#f7f9fd;color:#27344e;text-align:left}.draft-disclosure>button span{display:grid;gap:3px}.draft-disclosure>button small{color:#7a8599;font-weight:500}.draft-disclosure>.form-grid{padding:2px 14px 14px}.drawer-actions{display:flex;justify-content:flex-end;gap:12px;margin-top:22px}@media(max-width:760px){.qq-chat-page{padding-inline:10px}.hero-header{align-items:flex-start}.hero-header h1{font-size:29px}.header-actions{width:100%;display:grid;grid-template-columns:1fr 1fr;gap:8px}.header-actions .status-pill{grid-column:1/-1;overflow-wrap:anywhere}.header-actions button{min-height:44px}.pairing-workbench{flex-direction:column;padding:22px 16px;text-align:center}.qq-qr{width:min(240px,75vw);height:auto;aspect-ratio:1}.pairing-workbench .primary-button{width:100%;min-height:48px}.workbench-shell{border-radius:16px}.workbench-frame{height:calc(100dvh - 226px);min-height:480px}.workbench-toolbar{min-height:auto;padding:10px 12px;align-items:flex-start;flex-direction:column;gap:7px;font-size:11px}.workbench-toolbar div{align-items:flex-start;flex-direction:column;gap:3px}.workbench-toolbar>span{overflow-wrap:anywhere}.qq-drawer-backdrop{z-index:110}.qq-crm-drawer{width:100vw;height:100dvh;box-shadow:none}.drawer-header{position:sticky;z-index:4;top:0;padding:14px 14px 12px;align-items:flex-start;backdrop-filter:blur(16px);background:#fffffff2}.drawer-header h2{font-size:20px}.drawer-scroll{padding:12px 12px calc(78px + env(safe-area-inset-bottom))}.drawer-section{padding:16px;margin-bottom:12px;border-radius:16px}.drawer-section input,.drawer-section select,.drawer-section textarea{font-size:16px}.drawer-section input,.drawer-section select{min-height:46px}.customer-search-row{grid-template-columns:1fr}.customer-search-row button,.full-button{min-height:48px;width:100%}.drawer-section .form-grid.two{grid-template-columns:1fr}.draft-summary{display:grid;gap:5px}.draft-disclosure>.form-grid{padding-inline:12px}.drawer-actions{position:sticky;z-index:3;bottom:calc(64px + env(safe-area-inset-bottom));display:grid;grid-template-columns:1fr;margin:22px -16px -16px;padding:12px 16px calc(12px + env(safe-area-inset-bottom));border-top:1px solid #e3e8f2;background:#fffffff2;backdrop-filter:blur(16px)}.drawer-actions button{width:100%;min-height:48px}}
.workbench-shell{overscroll-behavior:contain}
@media(max-width:760px){
  .qq-chat-page{padding-bottom:calc(78px + env(safe-area-inset-bottom))}
  .workbench-frame{height:max(420px,calc(100dvh - 300px));min-height:420px}
  .drawer-actions{bottom:0}
}
</style>
