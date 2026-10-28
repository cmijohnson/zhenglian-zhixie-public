<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'

interface Configuration {
  id: string; appId: string; secretConfigured: boolean; baseAppToken: string; tableId?: string
  tableName: string; active: boolean; oauthEnabled: boolean; inboundSyncEnabled: boolean
  schemaState: string; eventState: string; lastEventAt?: string; lastEventErrorCode?: string; riskAcknowledgedAt: string
  lastTestedAt?: string; lastSuccessAt?: string; lastErrorCode?: string
  pendingCount: number; permanentFailureCount: number; version: number
  createdAt: string; updatedAt: string
}
interface FieldStatus {
  key: string; expectedName: string; remoteName?: string; expectedType: number
  remoteType?: number; present: boolean; compatible: boolean
}
interface Schema { provisioned: boolean; state: string; fields: FieldStatus[]; checkedAt: string }
interface Preview {
  totalCustomers: number; privateCustomers: number; archivedCustomers: number
  sensitiveFields: string[]; warning: string
}
interface Run {
  id: string; type: string; status: string; totalCount: number; successCount: number
  failedCount: number; skippedCount: number; startedAt?: string; completedAt?: string
  lastErrorCode?: string; createdAt: string
}
interface Failure { queueId: string; customerId: string; attemptCount: number; errorCode?: string; nextAttemptAt: string; updatedAt: string }
interface InboundStatus { connectionState: string; lastEventAt?: string; lastEventErrorCode?: string; pendingCount: number; appliedCount: number; conflictCount: number; rejectedCount: number; failedCount: number }
interface InboundEvent { inboxId: string; eventType: string; actionKind: string; recordId: string; status: string; attemptCount: number; reasonCode?: string; customerId?: string; actorMembershipId?: string; changedFields: string[]; receivedAt: string; processedAt?: string }
interface Binding { id: string; memberDisplayName: string; feishuDisplayName?: string; feishuUserId: string; active: boolean; boundAt: string; lastLoginAt?: string; revokedAt?: string }
interface BotSettings { id: string; enabled: boolean; submissionChatId: string; submissionChatName: string; broadcastChatId: string; broadcastChatName: string; botName?: string; defaultBonusCents: number; broadcastTitle: string; leaderboardTitle: string; leaderboardFooter: string; privacyAcknowledgedAt?: string; quickCustomerCreationEnabled: boolean; financialVisibilityAcknowledgedAt?: string; version: number; updatedAt: string }
interface Chat { chatId: string; name: string; description?: string }
interface DealParticipant { membershipId: string; displayName: string; role: string; bonusCents: number }
interface DealConfirmation { id: string; customerId: string; customerName: string; status: string; source: string; confirmationChannel?: string; confirmedAt?: string; orderAmountCents?: number; totalBonusCents: number; needsAttribution: boolean; participants: DealParticipant[]; version: number }
interface DealPage { content: DealConfirmation[]; page: number; size: number; totalElements: number; totalPages: number }
interface LeaderboardEntry { rank: number; membershipId: string; displayName: string; dealCount: number; primaryOrderAmountCents: number; primaryBonusCents: number }
interface Leaderboard { from: string; to: string; historicalDealCount: number; historicalOrderAmountCents: number; historicalBonusCents: number; entries: LeaderboardEntry[]; previewText: string }
interface MessageFailure { id: string; purpose: string; chatId: string; attemptCount: number; errorCode?: string; createdAt: string; updatedAt: string }
interface Member { membershipId: string; displayName: string; status: string; roles: string[] }

const configuration = ref<Configuration>()
const schema = ref<Schema>({ provisioned: false, state: 'NOT_PROVISIONED', fields: [], checkedAt: '' })
const runs = ref<Run[]>([])
const failures = ref<Failure[]>([])
const inboundStatus = ref<InboundStatus>({ connectionState: 'DISABLED', pendingCount: 0, appliedCount: 0, conflictCount: 0, rejectedCount: 0, failedCount: 0 })
const inboundEvents = ref<InboundEvent[]>([])
const bindings = ref<Binding[]>([])
const eventFilter = ref('')
const botSettings = ref<BotSettings>()
const chats = ref<Chat[]>([])
const dealConfirmations = ref<DealConfirmation[]>([])
const legacyConfirmations = ref<DealConfirmation[]>([])
const leaderboard = ref<Leaderboard>()
const messageFailures = ref<MessageFailure[]>([])
const members = ref<Member[]>([])
const form = reactive({ appId: '', appSecret: '', baseAppToken: '', active: false, oauthEnabled: false, inboundSyncEnabled: false, riskAcknowledged: false, expectedVersion: undefined as number | undefined })
const botForm = reactive({ enabled: false, submissionChatId: '', broadcastChatId: '', defaultBonusYuan: '0', broadcastTitle: '🎉 英格科技成单播报 🎉', leaderboardTitle: '🌹英格科技我为你操碎了心🌹', leaderboardFooter: '👀看看又是哪个小笨蛋饿死了👀\n🔨每日任务：激活 登记 对接🔨', privacyAcknowledged: false, quickCustomerCreationEnabled: false, financialVisibilityAcknowledged: false, expectedVersion: 0 })
const attribution = reactive({ requestId: '', customerName: '', primaryMembershipId: '', assistantMembershipIds: [] as string[], orderAmountYuan: '', totalBonusYuan: '0', expectedVersion: 0 })
const busy = ref('')
const error = ref('')
const success = ref('')

const ready = computed(() => schema.value.state === 'READY' && Boolean(configuration.value?.tableId))
const feishuTableUrl = computed(() => {
  const base = configuration.value?.baseAppToken
  const table = configuration.value?.tableId
  if (!base || !table) return ''
  return `https://feishu.cn/base/${encodeURIComponent(base)}?table=${encodeURIComponent(table)}`
})
const latestRun = computed(() => runs.value[0])
let statusPoll: number | undefined

async function loadOperationalStatus() {
  const [loadedConfiguration, loadedRuns, loadedFailures, loadedInbound, loadedEvents, loadedBindings] = await Promise.all([
    api<Configuration | undefined>('/api/v1/admin/feishu-integration'),
    api<Run[]>('/api/v1/admin/feishu-integration/runs?size=50'),
    api<Failure[]>('/api/v1/admin/feishu-integration/failures?size=100'),
    api<InboundStatus>('/api/v1/admin/feishu-integration/events/status'),
    api<InboundEvent[]>(`/api/v1/admin/feishu-integration/events?size=100${eventFilter.value ? `&status=${eventFilter.value}` : ''}`),
    api<Binding[]>('/api/v1/admin/feishu-bindings'),
  ])
  configuration.value = loadedConfiguration
  runs.value = loadedRuns
  failures.value = loadedFailures
  inboundStatus.value = loadedInbound
  inboundEvents.value = loadedEvents
  bindings.value = loadedBindings
  await loadDealBot(false)
}

async function loadDealBot(includeChats = true) {
  const requests: Promise<unknown>[] = [
    api<BotSettings | undefined>('/api/v1/admin/feishu-deal-bot'),
    api<DealPage>('/api/v1/admin/deal-confirmations?page=0&size=50'),
    api<DealPage>('/api/v1/admin/deal-confirmations/legacy?page=0&size=50'),
    api<Leaderboard>('/api/v1/admin/deal-leaderboard'),
    api<MessageFailure[]>('/api/v1/admin/feishu-message-failures?size=100'),
    api<Member[]>('/api/v1/admin/members'),
  ]
  if (includeChats && configuration.value) requests.push(api<Chat[]>('/api/v1/admin/feishu-deal-bot/chats'))
  const values = await Promise.all(requests)
  const loadedBot = values[0] as BotSettings | undefined
  if (loadedBot && typeof loadedBot.version === 'number') {
    botSettings.value = loadedBot
    Object.assign(botForm, {
      enabled: loadedBot.enabled, submissionChatId: loadedBot.submissionChatId || '', broadcastChatId: loadedBot.broadcastChatId || '',
      defaultBonusYuan: (loadedBot.defaultBonusCents / 100).toFixed(2), broadcastTitle: loadedBot.broadcastTitle,
      leaderboardTitle: loadedBot.leaderboardTitle, leaderboardFooter: loadedBot.leaderboardFooter,
      privacyAcknowledged: Boolean(loadedBot.privacyAcknowledgedAt),
      quickCustomerCreationEnabled: loadedBot.quickCustomerCreationEnabled,
      financialVisibilityAcknowledged: Boolean(loadedBot.financialVisibilityAcknowledgedAt), expectedVersion: loadedBot.version,
    })
  }
  const dealPage = values[1] as DealPage
  const legacyPage = values[2] as DealPage
  dealConfirmations.value = Array.isArray(dealPage?.content) ? dealPage.content : []
  legacyConfirmations.value = Array.isArray(legacyPage?.content) ? legacyPage.content : []
  leaderboard.value = (values[3] as Leaderboard)?.previewText ? values[3] as Leaderboard : undefined
  messageFailures.value = Array.isArray(values[4]) ? values[4] as MessageFailure[] : []
  members.value = Array.isArray(values[5]) ? (values[5] as Member[]).filter((item) => item.status === 'ACTIVE' && item.roles.some((role) => ['ORG_ADMIN', 'TEAM_MANAGER', 'MEMBER'].includes(role))) : []
  if (includeChats && Array.isArray(values[6])) chats.value = values[6] as Chat[]
}

async function load() {
  error.value = ''
  try {
    const [loadedConfiguration, loadedRuns, loadedFailures, loadedInbound, loadedEvents, loadedBindings] = await Promise.all([
      api<Configuration | undefined>('/api/v1/admin/feishu-integration'),
      api<Run[]>('/api/v1/admin/feishu-integration/runs?size=50'),
      api<Failure[]>('/api/v1/admin/feishu-integration/failures?size=100'),
      api<InboundStatus>('/api/v1/admin/feishu-integration/events/status'),
      api<InboundEvent[]>('/api/v1/admin/feishu-integration/events?size=100'),
      api<Binding[]>('/api/v1/admin/feishu-bindings'),
    ])
    configuration.value = loadedConfiguration
    runs.value = loadedRuns
    failures.value = loadedFailures
    inboundStatus.value = loadedInbound
    inboundEvents.value = loadedEvents
    bindings.value = loadedBindings
    if (loadedConfiguration) {
      Object.assign(form, {
        appId: loadedConfiguration.appId,
        appSecret: '',
        baseAppToken: loadedConfiguration.baseAppToken,
        active: loadedConfiguration.active,
        oauthEnabled: loadedConfiguration.oauthEnabled,
        inboundSyncEnabled: loadedConfiguration.inboundSyncEnabled,
        riskAcknowledged: true,
        expectedVersion: loadedConfiguration.version,
      })
      schema.value = await api<Schema>('/api/v1/admin/feishu-integration/schema')
    }
    await loadDealBot(true)
  } catch (reason) {
    error.value = message(reason, '飞书同步配置加载失败')
  }
}

function yuanToCents(value: string) {
  const amount = Number(value)
  if (!Number.isFinite(amount) || amount < 0 || !/^\d+(\.\d{1,2})?$/.test(value.trim())) throw new Error('奖金必须是非负数且最多两位小数')
  return Math.round(amount * 100)
}

async function saveDealBot() {
  await runAction('保存成单机器人', async () => {
    await api('/api/v1/admin/feishu-deal-bot', { method: 'PUT', body: jsonBody({
      ...botForm, defaultBonusCents: yuanToCents(botForm.defaultBonusYuan), expectedVersion: botSettings.value?.version || 0,
    }) })
    success.value = '成单机器人配置已保存，群名变更不会影响 chat_id 绑定。'
  })
}

async function testDealBot() {
  await runAction('测试成单机器人', async () => {
    await api('/api/v1/admin/feishu-deal-bot/test', { method: 'POST' })
    success.value = '两个飞书群均已收到不落业务数据的连接测试消息。'
  })
}

async function publishLeaderboard() {
  await runAction('发布今日榜单', async () => {
    await api('/api/v1/admin/feishu-deal-bot/publish-leaderboard', { method: 'POST' })
    success.value = '今日榜单已进入可重试发送队列。'
  })
}

function openAttribution(item: DealConfirmation) {
  Object.assign(attribution, { requestId: item.id, customerName: item.customerName, primaryMembershipId: '', assistantMembershipIds: [], orderAmountYuan: item.orderAmountCents ? yuan(item.orderAmountCents) : '', totalBonusYuan: '0', expectedVersion: item.version })
}

async function completeAttribution() {
  await runAction('补录成单归属', async () => {
    const totalBonusCents = yuanToCents(attribution.totalBonusYuan)
    const orderAmountCents = attribution.orderAmountYuan ? yuanToCents(attribution.orderAmountYuan) : undefined
    const ids = [attribution.primaryMembershipId, ...attribution.assistantMembershipIds]
    await api(`/api/v1/admin/deal-confirmations/${attribution.requestId}/complete-attribution`, {
      method: 'POST', body: jsonBody({ primaryMembershipId: attribution.primaryMembershipId,
        assistantMembershipIds: attribution.assistantMembershipIds, orderAmountCents, totalBonusCents,
        allocations: ids.map((membershipId, index) => ({ membershipId, bonusCents: index === 0 ? totalBonusCents : 0 })), expectedVersion: attribution.expectedVersion }),
    })
    attribution.requestId = ''
    success.value = '历史成单归属已补录，从原成单日期计入榜单和奖金台账。'
  })
}

async function reverseDeal(item: DealConfirmation) {
  const reason = window.prompt(`请填写冲正「${item.customerName}」的原因：`)?.trim()
  if (!reason) return
  await runAction('冲正成单', async () => {
    await api(`/api/v1/admin/deal-confirmations/${item.id}/reverse`, { method: 'POST', body: jsonBody({ reason, expectedVersion: item.version }) })
    success.value = '已追加冲正流水并将更正广播加入队列，原记录未删除。'
  })
}

async function retryMessage(item: MessageFailure) {
  await runAction('重试飞书消息', async () => {
    await api(`/api/v1/admin/feishu-message-failures/${item.id}/retry`, { method: 'POST' })
    success.value = '消息已重新进入发送队列。'
  })
}

async function runAction(label: string, action: () => Promise<void>) {
  busy.value = label; error.value = ''; success.value = ''
  try { await action(); await load() } catch (reason) { error.value = message(reason, `${label}失败`) }
  finally { busy.value = '' }
}

async function save() {
  await runAction('保存配置', async () => {
    const saved = await api<Configuration>('/api/v1/admin/feishu-integration', {
      method: 'PUT',
      body: jsonBody({
        ...form,
        appSecret: form.appSecret || undefined,
      }),
    })
    configuration.value = saved
    form.appSecret = ''
    success.value = '配置已安全保存，App Secret 不会再次回显。'
  })
}

async function testConnection() {
  await runAction('测试连接', async () => {
    await api('/api/v1/admin/feishu-integration/test', { method: 'POST' })
    success.value = '飞书应用凭据和目标 Base 访问正常。'
  })
}

function authorizeInboundSubscription() {
  if (!configuration.value?.id) return
  const query = new URLSearchParams({
    purpose: 'SUBSCRIBE',
    integrationId: configuration.value.id,
    returnTo: '/admin/feishu?feishuSubscription=ready',
  })
  window.location.assign(`/api/v1/auth/feishu/start?${query.toString()}`)
}

async function provision() {
  await runAction('初始化数据表', async () => {
    await api('/api/v1/admin/feishu-integration/provision', { method: 'POST' })
    success.value = '“英格云客户档案”数据表和标准字段已准备完成。'
  })
}

async function changeActive() {
  const target = !Boolean(configuration.value?.active)
  await runAction(target ? '启用同步' : '暂停同步', async () => {
    await api('/api/v1/admin/feishu-integration', {
      method: 'PUT',
      body: jsonBody({ ...form, active: target, inboundSyncEnabled: target ? form.inboundSyncEnabled : false, appSecret: undefined, expectedVersion: configuration.value?.version }),
    })
    success.value = target ? '飞书自动同步已启用。' : '飞书自动同步已暂停，既有飞书记录不会删除。'
  })
}

async function retryInbound(item: InboundEvent) {
  await runAction('重试入站事件', async () => {
    await api(`/api/v1/admin/feishu-integration/events/${item.inboxId}/retry`, { method: 'POST' })
    success.value = '该事件已重新进入权限与版本校验队列。'
  })
}

async function revokeBinding(item: Binding) {
  if (!window.confirm(`确认撤销 ${item.memberDisplayName} 的飞书绑定？密码登录不会受影响。`)) return
  await runAction('撤销绑定', async () => {
    await api(`/api/v1/admin/feishu-bindings/${item.id}`, { method: 'DELETE' })
    success.value = '飞书账号绑定已撤销。'
  })
}

async function initialSync() {
  await runAction('首次同步', async () => {
    const preview = await api<Preview>('/api/v1/admin/feishu-integration/initial-sync/preview', { method: 'POST' })
    const confirmed = window.confirm([
      `即将同步 ${preview.totalCustomers} 位客户，其中私有客户 ${preview.privateCustomers} 位、归档客户 ${preview.archivedCustomers} 位。`,
      `完整字段：${preview.sensitiveFields.join('、')}。`, preview.warning,
    ].join('\n'))
    if (!confirmed) return
    await api('/api/v1/admin/feishu-integration/initial-sync/start', { method: 'POST' })
    success.value = '首次同步已进入后台队列，系统会分批完成。'
  })
}

async function syncNow() {
  await runAction('立即同步', async () => {
    await api('/api/v1/admin/feishu-integration/sync-now', { method: 'POST' })
    success.value = '全量同步任务已进入后台队列。'
  })
}

async function reconcile() {
  await runAction('重新校验', async () => {
    await api('/api/v1/admin/feishu-integration/reconcile', { method: 'POST' })
    success.value = '一致性校验任务已进入后台队列。'
  })
}

async function retry(item: Failure) {
  await runAction('重试失败项', async () => {
    await api(`/api/v1/admin/feishu-integration/failures/${item.queueId}/retry`, { method: 'POST' })
    success.value = '失败记录已重新进入同步队列。'
  })
}

function message(reason: unknown, fallback: string) {
  if (reason instanceof ApiError) return `${reason.body.message}${reason.body.traceId ? `（${reason.body.traceId}）` : ''}`
  return reason instanceof Error ? reason.message : fallback
}

function yuan(cents: number) { return (cents / 100).toFixed(2).replace(/\.00$/, '') }

onMounted(() => {
  if (new URLSearchParams(window.location.search).get('feishuSubscription') === 'ready') {
    success.value = '文档所有者授权已完成，事件长连接将自动恢复。'
  }
  void load()
  statusPoll = window.setInterval(() => {
    if (document.visibilityState === 'visible') {
      void loadOperationalStatus().catch((reason) => { error.value = message(reason, '同步进度刷新失败') })
    }
  }, 10_000)
})
onUnmounted(() => { if (statusPoll !== undefined) window.clearInterval(statusPoll) })
</script>

<template>
  <div class="page-shell feishu-page">
    <header class="page-heading">
      <div><p class="eyebrow">FEISHU BITABLE</p><h1>飞书多维表格</h1><p>英格云是唯一数据源；每小时同步一次，每天 02:30 自动校验。</p></div>
      <span :class="['integration-state', configuration?.active ? 'ready' : 'paused']">{{ configuration?.active ? '同步中' : '未启用' }}</span>
    </header>

    <p v-if="error" class="inline-alert error">{{ error }}</p>
    <p v-if="success" class="inline-alert success">{{ success }}</p>

    <section class="risk-panel">
      <strong>完整联系方式与私有客户同步提示</strong>
      <p>手机号、微信号、QQ 号以及私有客户会进入同一张飞书表。同步后由飞书 Base 的分享权限控制可见范围。</p>
    </section>

    <div class="metric-grid">
      <article><small>表结构</small><strong>{{ schema.state }}</strong><span>{{ schema.fields.filter((item) => item.compatible).length }}/{{ schema.fields.length || 34 }} 字段</span></article>
      <article><small>待同步</small><strong>{{ configuration?.pendingCount || 0 }}</strong><span>连续修改自动合并</span></article>
      <article><small>永久失败</small><strong>{{ configuration?.permanentFailureCount || failures.length }}</strong><span>最多自动尝试 5 次</span></article>
      <article><small>最近成功</small><strong>{{ configuration?.lastSuccessAt ? dateTime(configuration.lastSuccessAt) : '—' }}</strong><span>{{ configuration?.lastErrorCode || '暂无错误' }}</span></article>
      <article><small>最近任务</small><strong>{{ latestRun?.status || '—' }}</strong><span>{{ latestRun ? `${latestRun.successCount}/${latestRun.totalCount}` : '尚未执行' }}</span></article>
      <article><small>事件连接</small><strong>{{ inboundStatus.connectionState }}</strong><span>{{ inboundStatus.lastEventAt ? dateTime(inboundStatus.lastEventAt) : '尚未收到事件' }}</span></article>
      <article><small>入站待处理</small><strong>{{ inboundStatus.pendingCount }}</strong><span>冲突 {{ inboundStatus.conflictCount }} · 拒绝 {{ inboundStatus.rejectedCount }}</span></article>
    </div>

    <div class="integration-grid">
      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">CONNECTION</p><h2>企业自建应用</h2></div></div>
        <div class="form-grid one-column">
          <label>App ID<input v-model.trim="form.appId" maxlength="128" placeholder="cli_xxxxxxxxx"></label>
          <label>App Secret<input v-model.trim="form.appSecret" type="password" maxlength="512" autocomplete="off" :placeholder="configuration ? '留空表示不更换' : '仅保存一次，之后不回显'"></label>
          <label>Base App Token<input v-model.trim="form.baseAppToken" maxlength="256" placeholder="bascnxxxxxxxxx"></label>
          <label class="risk-check"><input v-model="form.oauthEnabled" type="checkbox"><span><strong>启用飞书账号绑定与快捷登录</strong><br>正式使用必须配置可信 HTTPS OAuth 回调；不会自动注册英格云账号。</span></label>
          <label class="risk-check"><input v-model="form.inboundSyncEnabled" type="checkbox" :disabled="!form.active"><span><strong>启用安全双向协同</strong><br>只接受已绑定成员的安全字段修改；受控字段、删除和越权操作会拒绝并恢复。</span></label>
          <label class="risk-check"><input v-model="form.riskAcknowledged" type="checkbox"><span>我确认完整联系方式和私有客户将进入同一张飞书表，并已限制该 Base 的分享权限。</span></label>
        </div>
        <footer class="form-actions">
          <button class="primary-button" :disabled="Boolean(busy) || !form.appId || !form.baseAppToken || (!configuration && !form.appSecret) || !form.riskAcknowledged" @click="save">保存配置</button>
          <button class="ghost-button" :disabled="Boolean(busy) || !configuration" @click="testConnection">测试连接</button>
        </footer>
      </section>

      <section class="panel">
        <div class="panel-title"><div><p class="eyebrow">PROVISIONING</p><h2>数据表与字段</h2></div><code>{{ configuration?.tableId || '尚未创建' }}</code></div>
        <p class="panel-copy">系统只在指定 Base 内创建“英格云客户档案”表，不会新建 Base，也不会删除飞书中的记录。</p>
        <div class="field-summary" v-if="schema.fields.length">
          <span v-for="field in schema.fields" :key="field.key" :class="field.compatible ? 'ok' : 'bad'">{{ field.remoteName || field.expectedName }}</span>
        </div>
        <div v-else class="empty-state">保存并测试连接后，初始化标准客户表。</div>
        <footer class="form-actions">
          <button class="primary-button" :disabled="Boolean(busy) || !configuration" @click="provision">{{ ready ? '修复/复核表结构' : '初始化数据表' }}</button>
          <a v-if="ready" class="ghost-button open-feishu-link" :href="feishuTableUrl" target="_blank" rel="noopener noreferrer">打开飞书客户表</a>
          <button v-if="ready" :class="configuration?.active ? 'danger-link' : 'ghost-button'" :disabled="Boolean(busy)" @click="changeActive">{{ configuration?.active ? '暂停同步' : '启用同步' }}</button>
        </footer>
      </section>
    </div>

    <section class="panel sync-actions">
      <div class="panel-title"><div><p class="eyebrow">SYNC OPERATIONS</p><h2>同步操作</h2></div><span>整点后第 5 分钟自动执行</span></div>
      <div class="action-row">
        <button class="ghost-button" :disabled="Boolean(busy) || !configuration?.active || !configuration?.inboundSyncEnabled || !ready" @click="authorizeInboundSubscription">授权 Base 所有者订阅事件</button>
        <button class="primary-button" :disabled="Boolean(busy) || !configuration?.active || !ready" @click="initialSync">首次全量同步</button>
        <button class="ghost-button" :disabled="Boolean(busy) || !configuration?.active || !ready" @click="syncNow">立即全量同步</button>
        <button class="ghost-button" :disabled="Boolean(busy) || !configuration?.active || !ready" @click="reconcile">重新校验一致性</button>
        <button class="ghost-button" :disabled="Boolean(busy)" @click="load">刷新状态</button>
      </div>
    </section>

    <section class="panel deal-bot-panel">
      <div class="panel-title"><div><p class="eyebrow">FEISHU DEAL BOT</p><h2>成单机器人与全员广播</h2></div><span :class="['integration-state', botSettings?.enabled ? 'ready' : 'paused']">{{ botSettings?.enabled ? '已启用' : '未启用' }}</span></div>
      <p class="panel-copy">提交群支持搜索、快速建档、负责人转接、订单与奖金设置、确认和榜单查询；广播群只接收机器人成单播报和每天 22:00 榜单。实际绑定使用 chat_id，修改群名不影响运行。</p>
      <details class="bot-tutorial">
        <summary>查看群聊使用教程与 Slash 命令</summary>
        <p>在“成单提交”群内发送 <code>@机器人 /help</code> 可随时获取交互卡片。常用流程：</p>
        <ol>
          <li><code>/search 关键词</code> 后用 <code>/select 1</code> 选择已有客户，或用 <code>/new 客户名 @负责人</code> 快速建档。</li>
          <li><code>/deal</code> 发起成单，再填写 <code>/primary</code>、<code>/assist</code>、<code>/order</code>、<code>/bonus</code> 和 <code>/split</code>。</li>
          <li><code>/current</code> 检查草稿，确认无误后执行 <code>/confirm</code>；负责人转接使用 <code>/owner @成员</code> 并在卡片中二次确认。</li>
          <li><code>/mine</code> 查看本人数据，<code>/leaderboard</code> 查看月榜，<code>/status</code> 检查绑定与会话。</li>
        </ol>
        <p>快速建档创建的是正式客户；取消成单不会删除客户。确认后的成单不可在群里覆盖，只能由管理员冲正。</p>
      </details>
      <div class="form-grid bot-grid">
        <label>成单提交群<select v-model="botForm.submissionChatId"><option value="">请选择</option><option v-for="chat in chats" :key="chat.chatId" :value="chat.chatId">{{ chat.name || chat.chatId }}</option></select></label>
        <label>全员广播群<select v-model="botForm.broadcastChatId"><option value="">请选择</option><option v-for="chat in chats" :key="chat.chatId" :value="chat.chatId">{{ chat.name || chat.chatId }}</option></select></label>
        <label>默认奖金（元）<input v-model.trim="botForm.defaultBonusYuan" inputmode="decimal" placeholder="0.00"></label>
        <label>成单广播标题<input v-model.trim="botForm.broadcastTitle" maxlength="300"></label>
        <label>榜单标题<input v-model.trim="botForm.leaderboardTitle" maxlength="300"></label>
        <label class="wide-field">榜单结尾<textarea v-model="botForm.leaderboardFooter" maxlength="500" rows="3"></textarea></label>
      </div>
      <label class="risk-check"><input v-model="botForm.privacyAcknowledged" type="checkbox"><span><strong>我确认全员广播会公开客户完整姓名</strong><br>手机号、微信、QQ、沟通正文与客户画像不会进入广播；目标群仅向获授权员工开放。</span></label>
      <label class="risk-check"><input v-model="botForm.financialVisibilityAcknowledged" type="checkbox"><span><strong>我确认订单金额和奖金按组织口径公开</strong><br>金额会显示在成单提交群、全员广播、榜单及有权访问的客户档案中。</span></label>
      <label class="risk-check"><input v-model="botForm.quickCustomerCreationEnabled" type="checkbox"><span><strong>允许 /new 快速创建正式客户</strong><br>新客户默认全组织可见；取消成单草稿不会删除已经创建的客户。</span></label>
      <label class="risk-check"><input v-model="botForm.enabled" type="checkbox"><span><strong>启用成单机器人</strong><br>飞书员工确认将立即生效；网页和 Android 仍保持管理员审核流程。</span></label>
      <footer class="form-actions">
        <button class="primary-button" :disabled="Boolean(busy) || !configuration || !botForm.submissionChatId || !botForm.broadcastChatId || !botForm.privacyAcknowledged || !botForm.financialVisibilityAcknowledged" @click="saveDealBot">保存机器人配置</button>
        <button class="ghost-button" :disabled="Boolean(busy) || !botSettings?.enabled" @click="testDealBot">向两群发送测试</button>
        <button class="ghost-button" :disabled="Boolean(busy) || !botSettings?.enabled" @click="publishLeaderboard">立即发布榜单</button>
      </footer>
    </section>

    <section class="panel leaderboard-panel">
      <div class="panel-title"><div><p class="eyebrow">DEAL LEADERBOARD</p><h2>本月开单榜与奖金台账</h2></div><span>{{ leaderboard?.historicalDealCount || 0 }} 单 · 订单 ¥{{ yuan(leaderboard?.historicalOrderAmountCents || 0) }} · 奖金 ¥{{ yuan(leaderboard?.historicalBonusCents || 0) }}</span></div>
      <div class="leaderboard-layout">
        <ol class="leaderboard-list">
          <li v-for="item in leaderboard?.entries || []" :key="item.membershipId"><strong>{{ item.rank }}. {{ item.displayName }}</strong><span>{{ item.dealCount }} 单 · 订单 ¥{{ yuan(item.primaryOrderAmountCents) }} · 主成单奖金 ¥{{ yuan(item.primaryBonusCents) }}</span></li>
          <li v-if="!leaderboard?.entries.length" class="empty-state">本月暂无已补全归属的成单。</li>
        </ol>
        <pre class="leaderboard-preview">{{ leaderboard?.previewText || '配置机器人后可预览每日榜单。' }}</pre>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">LEGACY ATTRIBUTION</p><h2>历史成单待补录</h2></div><span>{{ legacyConfirmations.length }} 项</span></div>
      <p class="panel-copy">历史成单继续计入累计成单总数；补录主成单人、协助人和奖金后，才进入个人榜单及累计奖金。</p>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>客户</th><th>来源</th><th>原成单时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in legacyConfirmations" :key="item.id"><td data-label="客户">{{ item.customerName }}</td><td data-label="来源">{{ item.source }}</td><td data-label="原成单时间">{{ item.confirmedAt ? dateTime(item.confirmedAt) : '—' }}</td><td data-label="操作"><button class="text-button" :disabled="Boolean(busy)" @click="openAttribution(item)">补录归属</button></td></tr>
      </tbody></table><div v-if="!legacyConfirmations.length" class="empty-state">暂无待补录历史成单。</div></div>
      <div v-if="attribution.requestId" class="attribution-editor">
        <h3>补录：{{ attribution.customerName }}</h3>
        <div class="form-grid bot-grid">
          <label>主成单人<select v-model="attribution.primaryMembershipId"><option value="">请选择</option><option v-for="member in members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label>
          <label>协助人（最多 2 名）<select v-model="attribution.assistantMembershipIds" multiple><option v-for="member in members.filter((item) => item.membershipId !== attribution.primaryMembershipId)" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}</option></select></label>
          <label>订单金额（元，可不补）<input v-model.trim="attribution.orderAmountYuan" inputmode="decimal" placeholder="历史未知可留空"></label>
          <label>奖金总额（元）<input v-model.trim="attribution.totalBonusYuan" inputmode="decimal"></label>
        </div>
        <p class="panel-copy">本次快速补录默认将全部奖金记给主成单人，协助人记 0 元；补录后不可覆盖，只能通过冲正处理。</p>
        <footer class="form-actions"><button class="primary-button" :disabled="Boolean(busy) || !attribution.primaryMembershipId || attribution.assistantMembershipIds.length > 2" @click="completeAttribution">确认补录</button><button class="ghost-button" @click="attribution.requestId = ''">取消</button></footer>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">DEAL CONFIRMATIONS</p><h2>成单记录与冲正</h2></div><span>{{ dealConfirmations.length }} 项</span></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>客户</th><th>状态</th><th>渠道</th><th>参与人</th><th>订单金额</th><th>奖金</th><th>时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in dealConfirmations" :key="item.id"><td data-label="客户">{{ item.customerName }}</td><td data-label="状态">{{ item.status }}<br><small v-if="item.needsAttribution">待补录</small></td><td data-label="渠道">{{ item.confirmationChannel || item.source }}</td><td data-label="参与人">{{ item.participants.map((person) => `${person.role === 'PRIMARY' ? '主' : '协'}·${person.displayName}`).join('、') || '—' }}</td><td data-label="订单金额">{{ item.orderAmountCents == null ? '未补录' : `¥${yuan(item.orderAmountCents)}` }}</td><td data-label="奖金">¥{{ yuan(item.totalBonusCents) }}</td><td data-label="时间">{{ item.confirmedAt ? dateTime(item.confirmedAt) : '—' }}</td><td data-label="操作"><button v-if="item.status === 'APPROVED'" class="danger-link" :disabled="Boolean(busy)" @click="reverseDeal(item)">冲正</button><span v-else>—</span></td></tr>
      </tbody></table></div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">MESSAGE OUTBOX</p><h2>广播失败与重试</h2></div><span>{{ messageFailures.length }} 项</span></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>类型</th><th>群聊</th><th>尝试</th><th>错误</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in messageFailures" :key="item.id"><td data-label="类型">{{ item.purpose }}</td><td data-label="群聊"><code>{{ item.chatId }}</code></td><td data-label="尝试">{{ item.attemptCount }}/5</td><td data-label="错误">{{ item.errorCode || '—' }}</td><td data-label="更新时间">{{ dateTime(item.updatedAt) }}</td><td data-label="操作"><button class="text-button" :disabled="Boolean(busy)" @click="retryMessage(item)">重试</button></td></tr>
      </tbody></table><div v-if="!messageFailures.length" class="empty-state">暂无发送失败。</div></div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">INBOUND EVENTS</p><h2>双向协同事件</h2></div><span>{{ inboundEvents.length }} 项</span></div>
      <p class="panel-copy">只记录事件标识、字段名、结果与错误码，不在事件账本保存客户字段内容。被拒绝或冲突的修改不会覆盖英格云。</p>
      <div class="event-toolbar"><label>结果筛选<select v-model="eventFilter" @change="loadOperationalStatus"><option value="">全部</option><option value="RECEIVED">待处理</option><option value="APPLIED">已应用</option><option value="CONFLICT">冲突</option><option value="REJECTED">已拒绝</option><option value="RESTORED">已恢复</option><option value="FAILED">失败</option></select></label><button class="ghost-button" :disabled="Boolean(busy)" @click="loadOperationalStatus">刷新事件</button></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>接收时间</th><th>动作</th><th>记录</th><th>结果</th><th>字段</th><th>原因</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in inboundEvents" :key="item.inboxId"><td data-label="接收时间">{{ dateTime(item.receivedAt) }}</td><td data-label="动作">{{ item.actionKind }}</td><td data-label="记录"><code>{{ item.recordId }}</code></td><td data-label="结果"><span class="table-status">{{ item.status }}</span></td><td data-label="字段">{{ item.changedFields.join('、') || '—' }}</td><td data-label="原因">{{ item.reasonCode || '—' }}</td><td data-label="操作"><button v-if="['CONFLICT','REJECTED','RESTORED','FAILED'].includes(item.status)" class="text-button" :disabled="Boolean(busy)" @click="retryInbound(item)">重新校验</button><span v-else>—</span></td></tr>
      </tbody></table><div v-if="!inboundEvents.length" class="empty-state">尚无飞书入站事件。</div></div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">IDENTITY BINDINGS</p><h2>飞书账号绑定</h2></div><span>{{ bindings.filter((item) => item.active).length }} 个有效绑定</span></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>英格云成员</th><th>飞书身份</th><th>状态</th><th>绑定时间</th><th>最近登录</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in bindings" :key="item.id"><td data-label="英格云成员">{{ item.memberDisplayName }}</td><td data-label="飞书身份">{{ item.feishuDisplayName || '飞书用户' }}<br><code>{{ item.feishuUserId.slice(0,4) }}…{{ item.feishuUserId.slice(-4) }}</code></td><td data-label="状态">{{ item.active ? '有效' : '已撤销' }}</td><td data-label="绑定时间">{{ dateTime(item.boundAt) }}</td><td data-label="最近登录">{{ item.lastLoginAt ? dateTime(item.lastLoginAt) : '—' }}</td><td data-label="操作"><button v-if="item.active" class="danger-link" :disabled="Boolean(busy)" @click="revokeBinding(item)">撤销</button><span v-else>—</span></td></tr>
      </tbody></table><div v-if="!bindings.length" class="empty-state">尚无成员绑定飞书账号。</div></div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">SYNC RUNS</p><h2>同步任务</h2></div></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>创建时间</th><th>类型</th><th>状态</th><th>总数</th><th>成功</th><th>跳过</th><th>失败</th><th>错误</th></tr></thead><tbody>
        <tr v-for="item in runs" :key="item.id"><td data-label="创建时间">{{ dateTime(item.createdAt) }}</td><td data-label="类型">{{ item.type }}</td><td data-label="状态"><span :class="['table-status', item.status === 'COMPLETED' ? 'ready' : item.status.includes('ERROR') || item.status === 'FAILED' ? 'failed' : 'pending']">{{ item.status }}</span></td><td data-label="总数">{{ item.totalCount }}</td><td data-label="成功">{{ item.successCount }}</td><td data-label="跳过">{{ item.skippedCount }}</td><td data-label="失败">{{ item.failedCount }}</td><td data-label="错误">{{ item.lastErrorCode || '—' }}</td></tr>
      </tbody></table><div v-if="!runs.length" class="empty-state">暂无同步任务。</div></div>
    </section>

    <section class="panel">
      <div class="panel-title"><div><p class="eyebrow">FAILURE LEDGER</p><h2>失败记录</h2></div><span>{{ failures.length }} 项</span></div>
      <div class="table-wrap"><table class="mobile-card-table"><thead><tr><th>客户 ID</th><th>尝试</th><th>错误</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in failures" :key="item.queueId"><td data-label="客户 ID"><code>{{ item.customerId }}</code></td><td data-label="尝试">{{ item.attemptCount }}/5</td><td data-label="错误">{{ item.errorCode || '—' }}</td><td data-label="更新时间">{{ dateTime(item.updatedAt) }}</td><td data-label="操作"><button class="text-button" :disabled="Boolean(busy)" @click="retry(item)">重试</button></td></tr>
      </tbody></table><div v-if="!failures.length" class="empty-state">暂无永久失败记录。</div></div>
    </section>
  </div>
</template>

<style scoped>
.feishu-page{display:grid;gap:20px}.page-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.integration-state{padding:9px 14px;border-radius:999px;font-size:12px;font-weight:800}.integration-state.ready{color:#166534;background:#dcfce7}.integration-state.paused{color:#64748b;background:#eef2f7}.risk-panel{padding:18px 20px;border:1px solid #f2c7a5;background:#fff8ed;border-radius:18px}.risk-panel strong{color:#9a4a0b}.risk-panel p{margin:7px 0 0;color:#80532f}.metric-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px}.metric-grid article{display:grid;gap:7px;min-height:120px;padding:18px;border:1px solid var(--line);border-radius:18px;background:var(--surface)}.metric-grid small,.metric-grid span{color:var(--muted)}.metric-grid strong{font-size:20px;word-break:break-word}.integration-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}.one-column{grid-template-columns:1fr}.risk-check{display:flex!important;align-items:flex-start;gap:10px;padding:12px;border:1px solid #f2c7a5;border-radius:12px;background:#fffaf3;margin-top:12px}.risk-check strong{color:#9a4a0b}.risk-check input{width:auto;margin-top:2px}.panel-copy{color:var(--muted);line-height:1.7}.bot-tutorial{margin:16px 0;padding:14px 16px;border:1px solid var(--line);border-radius:14px;background:var(--soft);color:var(--muted);line-height:1.7}.bot-tutorial summary{cursor:pointer;color:var(--text);font-weight:750}.bot-tutorial ol{display:grid;gap:6px;padding-left:22px}.bot-tutorial code{overflow-wrap:anywhere;color:#3157d5}.field-summary{display:flex;flex-wrap:wrap;gap:7px;margin:18px 0}.field-summary span{padding:6px 9px;border-radius:8px;font-size:11px}.field-summary .ok{color:#166534;background:#dcfce7}.field-summary .bad{color:#b91c1c;background:#fee2e2}.action-row{display:flex;flex-wrap:wrap;gap:10px}.event-toolbar{display:flex;align-items:end;gap:12px;margin:16px 0}.event-toolbar label{max-width:240px}.open-feishu-link{display:inline-flex;align-items:center;justify-content:center;text-decoration:none}.panel-title code{max-width:220px;overflow-wrap:anywhere}.inline-alert{margin:0}.table-wrap{overflow:auto}.mobile-card-table code{word-break:break-all}.bot-grid{grid-template-columns:repeat(2,minmax(0,1fr));margin:18px 0}.wide-field{grid-column:1/-1}.leaderboard-layout{display:grid;grid-template-columns:minmax(280px,.7fr) minmax(360px,1.3fr);gap:20px;margin-top:18px}.leaderboard-list{display:grid;gap:10px;margin:0;padding:0;list-style:none}.leaderboard-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px;border:1px solid var(--line);border-radius:14px}.leaderboard-preview{margin:0;padding:18px;border-radius:16px;background:#0f172a;color:#e2e8f0;white-space:pre-wrap;word-break:break-word;font:inherit;line-height:1.7}.attribution-editor{margin-top:18px;padding:18px;border:1px solid var(--line);border-radius:16px;background:var(--soft)}.attribution-editor h3{margin-top:0}@media(max-width:1100px){.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.integration-grid,.leaderboard-layout{grid-template-columns:1fr}}@media(max-width:640px){.page-heading{display:grid}.metric-grid{grid-template-columns:1fr 1fr}.metric-grid article{min-height:100px;padding:14px}.metric-grid strong{font-size:16px}.form-actions,.action-row,.event-toolbar,.bot-grid{display:grid;grid-template-columns:1fr}.event-toolbar label{max-width:none}.form-actions button,.form-actions a,.action-row button,.event-toolbar button{width:100%;min-height:46px}.panel{padding:18px}.risk-panel{padding:15px}.bot-tutorial{padding:13px}.table-wrap{overflow:visible}.leaderboard-list li{align-items:flex-start;flex-direction:column}.wide-field{grid-column:auto}}
</style>
