<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'
import type { Customer, PageResponse } from '../types'

interface Bootstrap { feishuDisplayName?: string; memberDisplayName: string; writable: boolean }
interface Member { membershipId: string; displayName: string; teamNames?: string }
interface CollaborationMember { membershipId: string; displayName: string; teamNames?: string }
interface Collaboration { canTransferOwnership: boolean; transferCandidates: CollaborationMember[] }
interface DealAllocation { membershipId: string; bonusCents: number }
interface DealDraft {
  id: string; customerId: string; customerName: string; ownerMembershipId: string; ownerDisplayName: string
  primaryMembershipId: string; assistantMembershipIds: string[]; orderAmountCents: number
  totalBonusCents: number; allocations: DealAllocation[]; status: string; confirmedRequestId?: string
  version: number; expiresAt: string
}
interface DealParticipant { membershipId: string; displayName: string; role: string; bonusCents: number }
interface DealSummary { requestId: string; customerId: string; status: string; confirmedAt: string; orderAmountCents: number; totalBonusCents: number; participants: DealParticipant[] }
interface ConfirmationResult { confirmation: DealSummary; broadcastStatus: string }
interface LeaderboardEntry { rank: number; displayName: string; dealCount: number; primaryOrderAmountCents: number; primaryBonusCents: number }
interface Leaderboard { from: string; to: string; historicalDealCount: number; historicalOrderAmountCents: number; historicalBonusCents: number; entries: LeaderboardEntry[] }
interface CustomerDraft { id: string; duplicate: { blocking: boolean; hiddenExactMatches: number; matches: { customerId: string; fullName: string; reasons: string[] }[] }; version: number }

const route = useRoute()
const bootstrap = ref<Bootstrap>()
const members = ref<Member[]>([])
const customers = ref<Customer[]>([])
const selected = ref<Customer>()
const collaboration = ref<Collaboration>()
const draft = ref<DealDraft>()
const result = ref<ConfirmationResult>()
const leaderboard = ref<Leaderboard>()
const tab = ref<'deal' | 'leaderboard' | 'help'>('deal')
const search = ref('')
const busy = ref('')
const error = ref('')
const success = ref('')
const orderAmount = ref('')
const totalBonus = ref('')
const allocations = reactive<Record<string, string>>({})
const createOpen = ref(false)
const customerDraft = ref<CustomerDraft>()
const duplicateReason = ref('')
const quick = reactive({ fullName: '', school: '', ownerMembershipId: '' })

const primary = computed({
  get: () => draft.value?.primaryMembershipId || '',
  set: (value: string) => { if (draft.value) draft.value.primaryMembershipId = value; rebalance() },
})
const assistants = computed({
  get: () => draft.value?.assistantMembershipIds || [],
  set: (value: string[]) => { if (draft.value) draft.value.assistantMembershipIds = value.slice(0, 2); rebalance() },
})
const participants = computed(() => {
  if (!draft.value) return []
  return [draft.value.primaryMembershipId, ...draft.value.assistantMembershipIds]
    .filter((id, index, all) => Boolean(id) && all.indexOf(id) === index)
    .map((id) => ({ id, name: memberName(id) }))
})
const allocationSum = computed(() => participants.value.reduce((sum, person) => sum + yuanToCents(allocations[person.id]), 0))
const totalBonusCents = computed(() => yuanToCents(totalBonus.value))
const allocationValid = computed(() => totalBonusCents.value > 0 && allocationSum.value === totalBonusCents.value)

function apiMessage(reason: unknown, fallback: string) {
  return reason instanceof ApiError
    ? `${reason.body.message}${reason.body.traceId ? `（追踪号 ${reason.body.traceId}）` : ''}`
    : fallback
}
async function run(label: string, action: () => Promise<void>) {
  busy.value = label; error.value = ''; success.value = ''
  try { await action() } catch (reason) { error.value = apiMessage(reason, `${label}失败`) } finally { busy.value = '' }
}
function memberName(id: string) { return members.value.find((item) => item.membershipId === id)?.displayName || '未知成员' }
function centsToYuan(cents: number | undefined) { return cents ? (cents / 100).toFixed(2).replace(/\.00$/, '') : '' }
function yuanToCents(value: string | undefined) {
  if (!value || !/^\d+(?:\.\d{1,2})?$/.test(value.trim())) return -1
  const [whole, decimals = ''] = value.trim().split('.')
  const cents = Number(whole) * 100 + Number((decimals + '00').slice(0, 2))
  return Number.isSafeInteger(cents) ? cents : -1
}
function participantIds() { return participants.value.map((item) => item.id) }
function rebalance() {
  const active = new Set(participantIds())
  Object.keys(allocations).forEach((id) => { if (!active.has(id)) delete allocations[id] })
  participants.value.forEach((person) => { if (!(person.id in allocations)) allocations[person.id] = '0' })
  if (draft.value?.primaryMembershipId && totalBonusCents.value >= 0) {
    const assistantCents = draft.value.assistantMembershipIds.reduce((sum, id) => sum + Math.max(0, yuanToCents(allocations[id])), 0)
    allocations[draft.value.primaryMembershipId] = centsToYuan(Math.max(0, totalBonusCents.value - assistantCents)) || '0'
  }
}
function hydrateDraft(value: DealDraft) {
  draft.value = value
  orderAmount.value = centsToYuan(value.orderAmountCents)
  totalBonus.value = centsToYuan(value.totalBonusCents)
  Object.keys(allocations).forEach((key) => delete allocations[key])
  value.allocations.forEach((item) => { allocations[item.membershipId] = centsToYuan(item.bonusCents) || '0' })
  if (!quick.ownerMembershipId && value.ownerMembershipId) quick.ownerMembershipId = value.ownerMembershipId
}

async function loadBase() {
  await run('加载快捷成单', async () => {
    const [head, people, current, board] = await Promise.all([
      api<Bootstrap>('/api/v1/feishu/workbench/bootstrap'),
      api<Member[]>('/api/v1/feishu/workbench/members'),
      api<DealDraft | undefined>('/api/v1/feishu/workbench/deal-drafts/current'),
      api<Leaderboard>('/api/v1/feishu/workbench/deal-leaderboard'),
    ])
    bootstrap.value = head; members.value = people; leaderboard.value = board
    quick.ownerMembershipId = people[0]?.membershipId || ''
    if (current) {
      hydrateDraft(current)
      selected.value = await api<Customer>(`/api/v1/feishu/workbench/customers/${current.customerId}`)
      await loadCollaboration(current.customerId)
    }
    const customerId = typeof route.query.customerId === 'string' ? route.query.customerId : ''
    if (customerId && (!current || current.customerId === customerId)) await chooseCustomerId(customerId, !current)
    else if (customerId && current?.customerId !== customerId) error.value = '已有另一位客户的成单草稿，请先完成或取消。'
  })
}
async function searchCustomers() {
  await run('搜索客户', async () => {
    const page = await api<PageResponse<Customer>>(`/api/v1/feishu/workbench/customers?size=20&search=${encodeURIComponent(search.value)}`)
    customers.value = page.items
  })
}
async function loadCollaboration(customerId: string) {
  collaboration.value = await api<Collaboration>(`/api/v1/feishu/workbench/customers/${customerId}/collaboration`)
}
async function chooseCustomer(customer: Customer) { await chooseCustomerId(customer.id, true) }
async function chooseCustomerId(customerId: string, start: boolean) {
  const customer = await api<Customer>(`/api/v1/feishu/workbench/customers/${customerId}`)
  selected.value = customer
  await loadCollaboration(customer.id)
  if (start) hydrateDraft(await api<DealDraft>('/api/v1/feishu/workbench/deal-drafts', { method: 'POST', body: jsonBody({ customerId }) }))
}
async function transferOwner(targetMembershipId: string) {
  if (!selected.value || !targetMembershipId || targetMembershipId === selected.value.ownerMembershipId) return
  const target = collaboration.value?.transferCandidates.find((item) => item.membershipId === targetMembershipId)
  if (!window.confirm(`确认将“${selected.value.fullName}”从 ${selected.value.ownerDisplayName} 转接给 ${target?.displayName || '新负责人'}？`)) return
  await run('转接负责人', async () => {
    selected.value = await api<Customer>(`/api/v1/feishu/workbench/customers/${selected.value!.id}/transfer`, {
      method: 'POST', body: jsonBody({ targetMembershipId, expectedVersion: selected.value!.version, reason: '飞书快捷成单页面转接' }),
    })
    await loadCollaboration(selected.value.id)
    if (draft.value) { draft.value.ownerMembershipId = selected.value.ownerMembershipId; draft.value.ownerDisplayName = selected.value.ownerDisplayName }
    success.value = `负责人已转接给 ${selected.value.ownerDisplayName}`
  })
}
async function saveDraft() {
  if (!draft.value) return
  const order = yuanToCents(orderAmount.value); const bonus = totalBonusCents.value
  if (order <= 0 || bonus <= 0) { error.value = '订单金额和奖金总额都必须大于 0，最多保留两位小数。'; return }
  if (!allocationValid.value) { error.value = '每位参与人的奖金分配合计必须严格等于奖金总额。'; return }
  await run('保存成单草稿', async () => {
    const updated = await api<DealDraft>(`/api/v1/feishu/workbench/deal-drafts/${draft.value!.id}`, {
      method: 'PUT', body: jsonBody({
        primaryMembershipId: draft.value!.primaryMembershipId,
        assistantMembershipIds: draft.value!.assistantMembershipIds,
        orderAmountCents: order,
        totalBonusCents: bonus,
        allocations: participants.value.map((person) => ({ membershipId: person.id, bonusCents: yuanToCents(allocations[person.id]) })),
        expectedVersion: draft.value!.version,
      }),
    })
    hydrateDraft(updated); success.value = '草稿已安全保存，30 分钟内可继续。'
  })
}
async function confirmDeal() {
  await saveDraft()
  if (!draft.value || error.value) return
  if (!window.confirm(`确认“${draft.value.customerName}”已经成单？确认后将立即更新客户并进入广播队列。`)) return
  await run('确认成单', async () => {
    const key = `feishu-web-${crypto.randomUUID()}`
    result.value = await api<ConfirmationResult>(`/api/v1/feishu/workbench/deal-drafts/${draft.value!.id}/confirm`, {
      method: 'POST', headers: { 'Idempotency-Key': key }, body: jsonBody({ expectedVersion: draft.value!.version }),
    })
    success.value = '成单已确认，客户状态、奖金台账和排行榜已更新。'
    draft.value = undefined
    leaderboard.value = await api<Leaderboard>('/api/v1/feishu/workbench/deal-leaderboard')
  })
}
async function cancelDraft() {
  if (!draft.value || !window.confirm('仅取消当前成单草稿；已快速创建的客户不会删除。确认继续？')) return
  await run('取消草稿', async () => {
    await api(`/api/v1/feishu/workbench/deal-drafts/${draft.value!.id}`, { method: 'DELETE' })
    draft.value = undefined; selected.value = undefined; collaboration.value = undefined
    success.value = '成单草稿已取消。'
  })
}
async function checkDuplicate() {
  if (!quick.fullName.trim()) return
  await run('客户查重', async () => {
    customerDraft.value = await api<CustomerDraft>('/api/v1/feishu/workbench/customer-drafts', { method: 'POST', body: jsonBody({
      fullName: quick.fullName, nickname: '', phone: '', wechatId: '', qqId: '', school: quick.school,
      highSchool: '', major: '', grade: '', city: '', sourceLocation: '', demandSummary: '',
      focusDirections: [], sourceChannel: '飞书临时建档', nextFollowUpAt: null,
    }) })
    success.value = customerDraft.value.duplicate.blocking ? '发现同名或冲突客户，请先核对。' : '查重通过，可以创建。'
  })
}
async function createCustomer() {
  if (!customerDraft.value) return
  if (customerDraft.value.duplicate.blocking && duplicateReason.value.trim().length < 2) { error.value = '发现重复风险时，请填写继续创建原因。'; return }
  await run('创建客户', async () => {
    const created = await api<Customer>(`/api/v1/feishu/workbench/customer-drafts/${customerDraft.value!.id}/confirm`, { method: 'POST', body: jsonBody({
      ownerMembershipId: quick.ownerMembershipId || null, teamId: null, visibility: 'ORGANIZATION',
      duplicateOverrideReason: duplicateReason.value || null, expectedVersion: customerDraft.value!.version,
    }) })
    customerDraft.value = undefined; createOpen.value = false
    await chooseCustomer(created)
    success.value = `正式客户“${created.fullName}”已创建并进入成单草稿。`
  })
}

watch(totalBonus, () => rebalance())
onMounted(() => { void loadBase() })
</script>

<template>
  <main class="deal-workbench">
    <header class="deal-header">
      <RouterLink class="back-link" to="/feishu">← 飞书工作台</RouterLink>
      <div><p class="eyebrow">FEISHU QUICK DEAL</p><h1>快捷成单</h1><p>独立成单入口；确认后立即生效并加入全员广播。</p></div>
      <RouterLink class="crm-link" to="/feishu/crm?returnTo=/dashboard">完整 CRM</RouterLink>
    </header>
    <nav class="tabs"><button :class="{ active: tab === 'deal' }" @click="tab='deal'">成单操作</button><button :class="{ active: tab === 'leaderboard' }" @click="tab='leaderboard'">本月榜单</button><button :class="{ active: tab === 'help' }" @click="tab='help'">使用说明</button></nav>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice success">{{ success }}</p>

    <template v-if="tab === 'deal'">
      <section v-if="result" class="success-panel panel">
        <span class="success-icon">✓</span><div><p class="eyebrow">DEAL CONFIRMED</p><h2>成单确认完成</h2><p>确认时间 {{ dateTime(result.confirmation.confirmedAt) }} · 广播 {{ result.broadcastStatus }}</p></div>
        <RouterLink :to="`/feishu/crm?returnTo=/customers/${result.confirmation.customerId}`">打开客户档案</RouterLink>
      </section>

      <section v-if="!draft" class="panel finder">
        <div class="section-title"><div><span>01</span><div><h2>选择客户</h2><p>搜索已有客户，或快速创建正式客户。</p></div></div><button class="secondary" @click="createOpen=!createOpen">{{ createOpen ? '收起建档' : '快速建档' }}</button></div>
        <form class="search-form" @submit.prevent="searchCustomers"><input v-model.trim="search" placeholder="姓名、昵称、手机号后四位、微信、QQ或学校" maxlength="100"><button class="primary" :disabled="Boolean(busy)">搜索</button></form>
        <div v-if="customers.length" class="search-results"><button v-for="customer in customers" :key="customer.id" class="customer-card" @click="chooseCustomer(customer)"><strong>{{ customer.fullName }}</strong><span>{{ customer.school || '学校未填写' }} · 负责人 {{ customer.ownerDisplayName }}</span><small>最近跟进 {{ customer.lastContactAt ? dateTime(customer.lastContactAt) : '暂无' }}</small></button></div>

        <div v-if="createOpen" class="quick-create">
          <h3>创建正式 CRM 客户</h3><div class="form-grid"><label>客户姓名 *<input v-model.trim="quick.fullName" maxlength="160"></label><label>学校<input v-model.trim="quick.school" maxlength="200"></label><label>负责人 *<select v-model="quick.ownerMembershipId"><option v-for="person in members" :key="person.membershipId" :value="person.membershipId">{{ person.displayName }}{{ person.teamNames ? ` · ${person.teamNames}` : '' }}</option></select></label></div>
          <button v-if="!customerDraft" class="secondary" :disabled="!quick.fullName || Boolean(busy)" @click="checkDuplicate">先查重</button>
          <div v-else class="duplicate-box"><strong>{{ customerDraft.duplicate.blocking ? '发现重复风险' : '查重通过' }}</strong><p v-for="match in customerDraft.duplicate.matches" :key="match.customerId">{{ match.fullName }}：{{ match.reasons.join('、') }}</p><label v-if="customerDraft.duplicate.blocking">继续新建原因<textarea v-model.trim="duplicateReason" rows="2" maxlength="500"></textarea></label><button class="primary" @click="createCustomer">确认创建并成单</button></div>
        </div>
      </section>

      <template v-else>
        <section class="panel customer-summary"><div class="section-title"><div><span>01</span><div><h2>{{ draft.customerName }}</h2><p>{{ selected?.school || '学校未填写' }} · 当前负责人 {{ draft.ownerDisplayName }}</p></div></div><RouterLink :to="`/feishu/crm?returnTo=/customers/${draft.customerId}`">完整档案</RouterLink></div>
          <label v-if="collaboration?.canTransferOwnership">调整负责人<select :value="selected?.ownerMembershipId" @change="transferOwner(($event.target as HTMLSelectElement).value)"><option :value="selected?.ownerMembershipId">{{ selected?.ownerDisplayName }}（当前）</option><option v-for="candidate in collaboration.transferCandidates" :key="candidate.membershipId" :value="candidate.membershipId">{{ candidate.displayName }}{{ candidate.teamNames ? ` · ${candidate.teamNames}` : '' }}</option></select></label>
        </section>

        <section class="panel"><div class="section-title"><div><span>02</span><div><h2>成单人员</h2><p>主成单人计入开单数，协助人最多两名。</p></div></div></div><div class="form-grid"><label>主成单人<select v-model="primary"><option v-for="person in members" :key="person.membershipId" :value="person.membershipId">{{ person.displayName }}</option></select></label><fieldset><legend>协助人（最多 2 名）</legend><label v-for="person in members.filter(item => item.membershipId !== primary)" :key="person.membershipId" class="check-row"><input v-model="assistants" type="checkbox" :value="person.membershipId" :disabled="!assistants.includes(person.membershipId) && assistants.length >= 2"><span>{{ person.displayName }}</span></label></fieldset></div></section>

        <section class="panel"><div class="section-title"><div><span>03</span><div><h2>金额与奖金</h2><p>金额最多两位小数，个人奖金合计必须与奖金总额一致。</p></div></div></div><div class="form-grid"><label>订单金额（元）<input v-model.trim="orderAmount" inputmode="decimal" placeholder="例如 3000"></label><label>奖金总额（元）<input v-model.trim="totalBonus" inputmode="decimal" placeholder="例如 500"></label></div><div class="allocation-list"><label v-for="person in participants" :key="person.id"><span>{{ person.name }}{{ person.id === primary ? ' · 主成单人' : ' · 协助' }}</span><input v-model.trim="allocations[person.id]" inputmode="decimal"><em>元</em></label></div><p :class="['sum-line',{invalid:totalBonus && !allocationValid}]">已分配 {{ centsToYuan(Math.max(0,allocationSum)) || '0' }} 元 / 共 {{ totalBonus || '0' }} 元</p></section>

        <section class="panel confirm-panel"><div><p class="eyebrow">FINAL REVIEW</p><h2>确认后立即生效</h2><p>将更新客户“已成单”状态、时间线、奖金台账和排行榜，并进入全员广播队列。</p></div><div class="confirm-actions"><button class="danger-ghost" @click="cancelDraft">取消草稿</button><button class="secondary" @click="saveDraft">保存草稿</button><button class="primary" :disabled="Boolean(busy) || !allocationValid" @click="confirmDeal">确认成单</button></div></section>
      </template>
    </template>

    <section v-else-if="tab === 'leaderboard'" class="panel leaderboard"><div class="section-title"><div><span>榜</span><div><h2>本月开单功臣</h2><p>{{ leaderboard?.from }} 至 {{ leaderboard?.to }}</p></div></div></div><div class="kpis"><article><strong>{{ leaderboard?.historicalDealCount || 0 }}</strong><span>历史有效成单</span></article><article><strong>¥{{ centsToYuan(leaderboard?.historicalOrderAmountCents) || 0 }}</strong><span>累计订单额</span></article><article><strong>¥{{ centsToYuan(leaderboard?.historicalBonusCents) || 0 }}</strong><span>累计奖金</span></article></div><ol><li v-for="entry in leaderboard?.entries" :key="entry.rank"><span class="rank">{{ entry.rank }}</span><strong>{{ entry.displayName }}</strong><span>{{ entry.dealCount }} 单</span><span>订单 ¥{{ centsToYuan(entry.primaryOrderAmountCents) || 0 }}</span><span>奖金 ¥{{ centsToYuan(entry.primaryBonusCents) || 0 }}</span></li><li v-if="!leaderboard?.entries.length" class="empty">本月还没有有效成单。</li></ol></section>

    <section v-else class="panel help"><h2>两种入口怎么选？</h2><article><strong>完整 CRM</strong><p>用于客户档案、沟通时间线、提醒、任务、日报、公共池等日常工作。</p></article><article><strong>快捷成单</strong><p>只处理成单：客户、负责人、贡献人员、订单金额、奖金与确认广播。</p></article><h3>机器人命令</h3><p><code>/crm</code> 打开完整 CRM，<code>/deal-web</code> 打开本页；原有 <code>/search</code>、<code>/deal</code> 等群内命令继续可用。</p></section>
  </main>
</template>

<style scoped>
.deal-workbench{min-height:100dvh;padding:calc(24px + env(safe-area-inset-top)) max(18px,calc((100vw - 980px)/2)) calc(40px + env(safe-area-inset-bottom));background:linear-gradient(180deg,#edf3ff,#f7f8fb 36%);color:#17233d}.deal-header{display:grid;grid-template-columns:1fr auto;gap:12px;align-items:end;margin-bottom:18px}.deal-header>div{grid-column:1}.deal-header h1{margin:5px 0;font-size:clamp(34px,6vw,52px);letter-spacing:-.04em}.deal-header p{margin:0;color:#68758d}.back-link{grid-column:1/-1;justify-self:start;color:#52617b;text-decoration:none}.crm-link{grid-column:2;grid-row:2;padding:11px 15px;border:1px solid #cdd8ed;border-radius:13px;background:#fff;color:#304d9b;text-decoration:none;font-weight:700}.eyebrow{margin:0;color:#426ce5!important;font-size:12px;font-weight:800;letter-spacing:.16em}.tabs{display:flex;gap:6px;padding:5px;margin-bottom:18px;border:1px solid #dce3f0;border-radius:15px;background:#ffffffbf}.tabs button{flex:1;min-height:44px;border:0;border-radius:11px;background:transparent;color:#69758b;font-weight:700}.tabs button.active{background:#173c98;color:#fff}.panel{padding:clamp(20px,4vw,30px);margin-bottom:16px;border:1px solid #dce3ef;border-radius:24px;background:#fff;box-shadow:0 18px 48px #4156810d}.notice{padding:13px 16px;border-radius:13px;overflow-wrap:anywhere}.notice.error{background:#fff0f0;color:#a33535}.notice.success{background:#eaf9f4;color:#14745e}.section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:20px}.section-title>div{display:flex;gap:13px}.section-title>div>span{display:grid;place-items:center;flex:none;width:38px;height:38px;border-radius:12px;background:#e9efff;color:#365dca;font-weight:800}.section-title h2{margin:0 0 5px}.section-title p{margin:0;color:#77839a}.primary,.secondary,.danger-ghost{min-height:46px;padding:0 18px;border-radius:13px;font-weight:750;cursor:pointer}.primary{border:0;color:#fff;background:linear-gradient(135deg,#5079f5,#2048bd)}.secondary{border:1px solid #d2dbec;color:#284883;background:#fff}.danger-ghost{border:1px solid #f0c5c5;color:#ac3939;background:#fff}.search-form{display:grid;grid-template-columns:1fr auto;gap:10px}.search-results{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:15px}.customer-card{display:grid;gap:6px;padding:15px;border:1px solid #dbe3f2;border-radius:15px;background:#f9fbff;text-align:left;cursor:pointer}.customer-card span,.customer-card small{color:#6e7990}.quick-create{margin-top:18px;padding:18px;border-radius:18px;background:#f5f8ff}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.form-grid label,.customer-summary>label,.quick-create label{display:grid;gap:7px;color:#5b6880;font-weight:650}.form-grid input,.form-grid select,.customer-summary select,.quick-create input,.quick-create select,.quick-create textarea,.allocation-list input{width:100%;min-height:46px;padding:10px 12px;border:1px solid #cfd9ec;border-radius:12px;background:#fff;color:#17233d;font:inherit;font-size:16px;box-sizing:border-box}.quick-create h3{margin-top:0}.duplicate-box{display:grid;gap:10px;margin-top:14px}.customer-summary a,.success-panel a{color:#3459bf;font-weight:700;text-decoration:none}fieldset{padding:12px;border:1px solid #d6dfef;border-radius:14px}legend{padding:0 6px;color:#5b6880;font-weight:650}.check-row{display:flex!important;align-items:center;gap:9px;min-height:40px}.check-row input{width:20px!important;min-height:auto!important}.allocation-list{display:grid;gap:9px;margin-top:18px}.allocation-list label{display:grid;grid-template-columns:1fr minmax(100px,180px) auto;align-items:center;gap:8px}.allocation-list em{font-style:normal;color:#718098}.sum-line{text-align:right;color:#56705d}.sum-line.invalid{color:#b24040}.confirm-panel{display:flex;justify-content:space-between;align-items:center;gap:18px;border-color:#b9c9f0;background:linear-gradient(135deg,#f7f9ff,#edf3ff)}.confirm-panel h2{margin:6px 0}.confirm-panel p{margin:0;color:#67748d}.confirm-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}.success-panel{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:16px;border-color:#b9e5d8}.success-icon{display:grid;place-items:center;width:52px;height:52px;border-radius:50%;color:#fff;background:#13a779;font-size:25px}.success-panel h2{margin:5px 0}.kpis{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.kpis article{display:grid;gap:5px;padding:18px;border-radius:16px;background:#f4f7ff}.kpis strong{font-size:25px}.kpis span{color:#6b7890}.leaderboard ol{display:grid;gap:8px;padding:0;list-style:none}.leaderboard li{display:grid;grid-template-columns:40px 1fr repeat(3,auto);gap:12px;align-items:center;padding:14px;border-bottom:1px solid #e5eaf3}.rank{display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:#edf1fb}.leaderboard .empty{display:block;color:#748097;text-align:center}.help article{padding:16px 0;border-bottom:1px solid #e5eaf3}.help p{color:#69758d;line-height:1.7}.help code{padding:3px 6px;border-radius:6px;background:#eef2fa;color:#2e52b4}@media(max-width:680px){.deal-workbench{padding-left:14px;padding-right:14px}.deal-header{grid-template-columns:1fr}.crm-link{grid-column:1;grid-row:auto;justify-self:start}.search-results,.form-grid,.kpis{grid-template-columns:1fr}.search-form{grid-template-columns:1fr}.section-title{align-items:flex-start}.confirm-panel{display:grid}.confirm-actions{display:grid;grid-template-columns:1fr 1fr}.confirm-actions .primary{grid-column:1/-1}.success-panel{grid-template-columns:auto 1fr}.success-panel a{grid-column:2}.leaderboard li{grid-template-columns:38px 1fr}.leaderboard li span:nth-last-child(-n+3){grid-column:2}.allocation-list label{grid-template-columns:1fr 112px auto}.tabs{position:sticky;top:0;z-index:5}.panel{border-radius:20px}.primary,.secondary,.danger-ghost{min-height:48px}}@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto}}
</style>
