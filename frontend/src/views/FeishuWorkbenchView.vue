<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime } from '../lib/format'
import type { Customer, Interaction, PageResponse } from '../types'

interface Bootstrap { bound: boolean; feishuDisplayName?: string; memberDisplayName: string; writable: boolean; viewer: boolean; customerTableUrl?: string; capabilities: string[] }
interface Duplicate { blocking: boolean; requiresConfirmation: boolean; hiddenExactMatches: number; matches: { customerId: string; fullName: string; reasons: string[] }[] }
interface Draft { id: string; status: string; duplicate: Duplicate; version: number; expiresAt: string }
interface Assignment { id: string; membershipId: string; memberDisplayName: string; status: string; resultSummary?: string; evidenceUrl?: string; reviewFeedback?: string; version: number }
interface Task { id: string; title: string; description: string; dueAt: string; status: string; assignmentCount: number; completedCount: number; claimable: boolean; myAssignment?: Assignment }

const bootstrap = ref<Bootstrap>()
const customers = ref<Customer[]>([])
const tasks = ref<Task[]>([])
const selected = ref<Customer>()
const timeline = ref<Interaction[]>([])
const search = ref('')
const busy = ref('')
const error = ref('')
const success = ref('')
const draftResult = ref<Draft>()
const overrideReason = ref('')
const draft = reactive({ fullName: '', nickname: '', phone: '', wechatId: '', qqId: '', school: '', highSchool: '', major: '', grade: '', city: '', sourceLocation: '', demandSummary: '', sourceChannel: '飞书', focusDirections: [] as string[] })
const interaction = reactive({ channel: '飞书', summary: '', details: '', outcome: '', nextFollowUpAt: '' })
const controlled = reactive({ currentStatus: '', intentionLevel: '', urgencyLevel: '', nextFollowUpAt: '', expectedVersion: 0 })
const taskDrafts = reactive<Record<string, string>>({})
const taskAssignments = reactive<Record<string, Assignment[]>>({})

const mine = computed(() => tasks.value.filter((item) => item.myAssignment))
const claimable = computed(() => tasks.value.filter((item) => item.claimable && !item.myAssignment))

function iso(value: string) { return value ? new Date(value).toISOString() : null }
function localInput(value?: string) {
  if (!value) return ''
  const date = new Date(value); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
function message(reason: unknown, fallback: string) { return reason instanceof ApiError ? `${reason.body.message}${reason.body.traceId ? `（${reason.body.traceId}）` : ''}` : fallback }

async function run(label: string, action: () => Promise<void>) {
  busy.value = label; error.value = ''; success.value = ''
  try { await action() } catch (reason) { error.value = message(reason, `${label}失败`) } finally { busy.value = '' }
}

async function load() {
  await run('加载', async () => {
    const [head, page, loadedTasks] = await Promise.all([
      api<Bootstrap>('/api/v1/feishu/workbench/bootstrap'),
      api<PageResponse<Customer>>(`/api/v1/feishu/workbench/customers?size=30&search=${encodeURIComponent(search.value)}`),
      api<Task[]>('/api/v1/feishu/workbench/tasks'),
    ])
    bootstrap.value = head; customers.value = page.items; tasks.value = loadedTasks
  })
}

async function openCustomer(customer: Customer) {
  await run('读取客户', async () => {
    const [detail, interactions] = await Promise.all([
      api<Customer>(`/api/v1/feishu/workbench/customers/${customer.id}`),
      api<Interaction[]>(`/api/v1/feishu/workbench/customers/${customer.id}/interactions`),
    ])
    selected.value = detail
    timeline.value = interactions
    Object.assign(controlled, {
      currentStatus: selected.value.currentStatus || '跟进中', intentionLevel: selected.value.intentionLevel || '未判断',
      urgencyLevel: selected.value.urgencyLevel || '普通', nextFollowUpAt: localInput(selected.value.nextFollowUpAt),
      expectedVersion: selected.value.version,
    })
  })
}

async function createDraft() {
  await run('客户查重', async () => {
    draftResult.value = await api<Draft>('/api/v1/feishu/workbench/customer-drafts', { method: 'POST', body: jsonBody(draft) })
    success.value = draftResult.value.duplicate.blocking ? '发现重复或冲突客户，请核对后决定是否提交。' : '查重完成，可确认创建客户。'
  })
}

async function confirmDraft() {
  if (!draftResult.value) return
  await run('确认建档', async () => {
    const created = await api<Customer>(`/api/v1/feishu/workbench/customer-drafts/${draftResult.value!.id}/confirm`, {
      method: 'POST', body: jsonBody({ ownerMembershipId: null, teamId: null, visibility: 'PRIVATE', duplicateOverrideReason: overrideReason.value || null, expectedVersion: draftResult.value!.version }),
    })
    success.value = `客户“${created.fullName}”已创建。`; draftResult.value = undefined; overrideReason.value = ''
    Object.assign(draft, { fullName: '', nickname: '', phone: '', wechatId: '', qqId: '', school: '', highSchool: '', major: '', grade: '', city: '', sourceLocation: '', demandSummary: '', sourceChannel: '飞书', focusDirections: [] })
    await load()
  })
}

async function addInteraction() {
  if (!selected.value) return
  await run('新增沟通', async () => {
    await api(`/api/v1/feishu/workbench/customers/${selected.value!.id}/interactions`, { method: 'POST', body: jsonBody({
      contactedAt: null, channel: interaction.channel, summary: interaction.summary, details: interaction.details || null,
      outcome: interaction.outcome || null, nextFollowUpAt: iso(interaction.nextFollowUpAt), interactionType: 'CUSTOMER_REPLY',
      tagIds: null, tagUpdateRequested: false,
    }) })
    success.value = '沟通记录已追加到英格云时间线。'; Object.assign(interaction, { channel: '飞书', summary: '', details: '', outcome: '', nextFollowUpAt: '' })
    await openCustomer(selected.value!)
  })
}

async function updateControlled() {
  if (!selected.value) return
  await run('更新跟进状态', async () => {
    selected.value = await api<Customer>(`/api/v1/feishu/workbench/customers/${selected.value!.id}/controlled-fields`, { method: 'PATCH', body: jsonBody({ ...controlled, nextFollowUpAt: iso(controlled.nextFollowUpAt) }) })
    controlled.expectedVersion = selected.value.version; success.value = '受控字段已通过英格云权限校验并更新。'
  })
}

async function claimTask(item: Task) { await run('领取任务', async () => { await api(`/api/v1/feishu/workbench/tasks/${item.id}/claim`, { method: 'POST' }); await load() }) }
async function updateTask(item: Task, status: 'IN_PROGRESS' | 'SUBMITTED') { await run('更新任务', async () => { await api(`/api/v1/feishu/workbench/tasks/${item.id}/my-assignment`, { method: 'PATCH', body: jsonBody({ status, resultSummary: taskDrafts[item.id] || null, evidenceUrl: null }) }); await load() }) }
async function loadAssignments(item: Task) { await run('读取验收项', async () => { taskAssignments[item.id] = await api<Assignment[]>(`/api/v1/feishu/workbench/tasks/${item.id}/assignments`) }) }
async function reviewTask(item: Task, assignment: Assignment, status: 'COMPLETED' | 'RETURNED') { await run('审核任务', async () => { await api(`/api/v1/feishu/workbench/tasks/${item.id}/review`, { method: 'POST', body: jsonBody({ assignmentId: assignment.id, status, feedback: null }) }); await loadAssignments(item); await load() }) }
async function closeTask(item: Task) { await run('关闭任务', async () => { await api(`/api/v1/feishu/workbench/tasks/${item.id}/close`, { method: 'POST' }); await load() }) }

onMounted(() => { void load() })
</script>

<template>
  <div class="page feishu-workbench">
    <header class="page-header"><div><p class="eyebrow">FEISHU COLLABORATION</p><h1>飞书协同工作台</h1><p class="muted">英格云负责权限、流程与审计；飞书用于便捷查询和安全操作。</p></div><a v-if="bootstrap?.customerTableUrl" class="ghost-button" :href="bootstrap.customerTableUrl" target="_blank" rel="noopener noreferrer">打开客户表</a></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>
    <section class="panel identity-strip"><span class="feishu-icon">飞</span><div><strong>{{ bootstrap?.feishuDisplayName || '飞书身份' }}</strong><p>已绑定英格云成员 {{ bootstrap?.memberDisplayName || '—' }}</p></div><span>{{ bootstrap?.writable ? '可协同编辑' : '只读' }}</span></section>

    <div class="workbench-grid">
      <section class="panel"><div class="panel-title"><div><p class="eyebrow">CUSTOMERS</p><h2>客户查询</h2></div></div><form class="search-row" @submit.prevent="load"><input v-model.trim="search" placeholder="姓名、学校或联系方式"><button class="primary-button">搜索</button></form><div class="customer-list"><button v-for="item in customers" :key="item.id" :class="['customer-row',{active:selected?.id===item.id}]" @click="openCustomer(item)"><strong>{{ item.fullName }}</strong><span>{{ item.school || '学校未填写' }} · {{ item.grade || '年级未填写' }}</span><small>{{ item.ownerDisplayName }} · {{ item.currentStatus || '未标注状态' }}</small></button><div v-if="!customers.length" class="empty-state">没有匹配客户。</div></div></section>

      <section class="panel customer-detail"><template v-if="selected"><div class="panel-title"><div><p class="eyebrow">CUSTOMER</p><h2>{{ selected.fullName }}</h2></div><RouterLink :to="`/customers/${selected.id}`">完整档案</RouterLink></div><p>{{ selected.demandSummary || '暂无需求摘要' }}</p><dl><div><dt>负责人</dt><dd>{{ selected.ownerDisplayName }}</dd></div><div><dt>学校</dt><dd>{{ selected.school || '—' }}</dd></div><div><dt>最近跟进</dt><dd>{{ selected.lastContactAt ? dateTime(selected.lastContactAt) : '—' }}</dd></div><div><dt>成单状态</dt><dd>{{ selected.dealState }}</dd></div></dl><div class="timeline-preview"><h3>沟通时间线</h3><article v-for="entry in timeline" :key="entry.id"><strong>{{ entry.summary }}</strong><span>{{ entry.channel }} · {{ dateTime(entry.contactedAt) }}</span><p v-if="entry.details">{{ entry.details }}</p></article><p v-if="!timeline.length" class="muted">暂无沟通记录。</p></div><form v-if="bootstrap?.writable" class="compact-form" @submit.prevent="updateControlled"><h3>受控跟进字段</h3><div class="form-grid two"><label>客户状态<input v-model.trim="controlled.currentStatus" required></label><label>意向<input v-model.trim="controlled.intentionLevel" required></label><label>紧迫度<input v-model.trim="controlled.urgencyLevel" required></label><label>下次跟进<input v-model="controlled.nextFollowUpAt" type="datetime-local"></label></div><button class="ghost-button" :disabled="Boolean(busy)">保存状态</button></form><form v-if="bootstrap?.writable" class="compact-form" @submit.prevent="addInteraction"><h3>追加沟通时间线</h3><div class="form-grid two"><label>渠道<input v-model.trim="interaction.channel" required></label><label>摘要<input v-model.trim="interaction.summary" maxlength="500" required></label></div><label>沟通详情<textarea v-model.trim="interaction.details" rows="3" maxlength="10000"></textarea></label><label>沟通结果<textarea v-model.trim="interaction.outcome" rows="2" maxlength="4000"></textarea></label><label>下次跟进<input v-model="interaction.nextFollowUpAt" type="datetime-local"></label><button class="primary-button" :disabled="Boolean(busy)">确认追加</button></form></template><div v-else class="empty-state">选择一位客户查看详情和操作。</div></section>
    </div>

    <section v-if="bootstrap?.writable" class="panel"><div class="panel-title"><div><p class="eyebrow">CUSTOMER DRAFT</p><h2>飞书快速建档</h2></div><span>先查重，再确认</span></div><form class="compact-form" @submit.prevent="createDraft"><div class="form-grid three"><label>姓名 *<input v-model.trim="draft.fullName" required maxlength="160"></label><label>手机号<input v-model.trim="draft.phone" maxlength="80"></label><label>微信<input v-model.trim="draft.wechatId" maxlength="120"></label><label>QQ<input v-model.trim="draft.qqId" maxlength="120"></label><label>大学<input v-model.trim="draft.school" maxlength="200"></label><label>专业<input v-model.trim="draft.major" maxlength="160"></label></div><label>需求摘要<textarea v-model.trim="draft.demandSummary" rows="3" maxlength="4000"></textarea></label><button class="ghost-button" :disabled="Boolean(busy)">查重并生成草稿</button></form><div v-if="draftResult" class="draft-confirm"><strong>{{ draftResult.duplicate.blocking ? '发现重复风险' : '查重通过' }}</strong><p v-for="match in draftResult.duplicate.matches" :key="match.customerId">{{ match.fullName }}：{{ match.reasons.join('、') }}</p><label v-if="draftResult.duplicate.blocking">确认继续创建的原因<textarea v-model.trim="overrideReason" rows="2" maxlength="500" required></textarea></label><button class="primary-button" :disabled="draftResult.duplicate.blocking && !overrideReason" @click="confirmDraft">确认创建为私有客户</button></div></section>

    <section class="panel"><div class="panel-title"><div><p class="eyebrow">TASKS</p><h2>我的任务</h2></div></div><article v-for="item in mine" :key="item.id" class="task-card"><div><strong>{{ item.title }}</strong><p>{{ item.description }}</p><small>截止 {{ dateTime(item.dueAt) }} · {{ item.myAssignment?.status }}</small></div><textarea v-model.trim="taskDrafts[item.id]" rows="2" placeholder="提交时填写成果说明"></textarea><div><button class="ghost-button" @click="updateTask(item,'IN_PROGRESS')">进行中</button><button class="primary-button" @click="updateTask(item,'SUBMITTED')">提交验收</button></div></article><article v-for="item in claimable" :key="item.id" class="task-card"><div><strong>{{ item.title }}</strong><p>{{ item.description }}</p><small>截止 {{ dateTime(item.dueAt) }}</small></div><button class="primary-button" @click="claimTask(item)">领取任务</button></article><div v-if="bootstrap?.capabilities.includes('TASK_REVIEW')" class="review-list"><h3>任务验收</h3><article v-for="item in tasks" :key="`review-${item.id}`" class="review-card"><div><strong>{{ item.title }}</strong><small>{{ item.status }} · 已完成 {{ item.completedCount }}/{{ item.assignmentCount }}</small></div><div><button class="ghost-button" @click="loadAssignments(item)">查看提交</button><button v-if="bootstrap?.capabilities.includes('TASK_CLOSE') && item.status !== 'CLOSED'" class="ghost-button" @click="closeTask(item)">关闭任务</button></div><div v-if="taskAssignments[item.id]" class="assignment-list"><article v-for="assignment in taskAssignments[item.id]" :key="assignment.id"><div><strong>{{ assignment.memberDisplayName }}</strong><p>{{ assignment.resultSummary || '暂无成果说明' }}</p><small>{{ assignment.status }}</small></div><div v-if="assignment.status === 'SUBMITTED'"><button class="ghost-button" @click="reviewTask(item,assignment,'RETURNED')">退回</button><button class="primary-button" @click="reviewTask(item,assignment,'COMPLETED')">验收通过</button></div></article></div></article></div><div v-if="!mine.length && !claimable.length && !bootstrap?.capabilities.includes('TASK_REVIEW')" class="empty-state">当前没有待办或可领取任务。</div></section>
  </div>
</template>

<style scoped>
.feishu-workbench{display:grid;gap:20px}.identity-strip{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:14px}.identity-strip p{margin:4px 0 0;color:var(--muted)}.feishu-icon{display:grid;place-items:center;width:44px;height:44px;border-radius:14px;color:#fff;background:linear-gradient(145deg,#2f80ed,#00b899);font-weight:800}.workbench-grid{display:grid;grid-template-columns:minmax(280px,.75fr) minmax(0,1.25fr);gap:20px}.search-row{display:grid;grid-template-columns:1fr auto;gap:10px}.customer-list{display:grid;gap:8px;max-height:640px;overflow:auto;margin-top:16px}.customer-row{display:grid;gap:5px;text-align:left;padding:14px;border:1px solid var(--line);border-radius:14px;background:var(--surface);cursor:pointer}.customer-row span,.customer-row small{color:var(--muted)}.customer-row.active{border-color:#5b7cfa;background:#f3f6ff}.customer-detail dl{display:grid;grid-template-columns:1fr 1fr;gap:10px}.customer-detail dl div{padding:12px;border-radius:12px;background:#f6f8fc}.customer-detail dt{color:var(--muted);font-size:12px}.customer-detail dd{margin:5px 0 0}.timeline-preview{display:grid;gap:9px;margin-top:18px}.timeline-preview article{display:grid;gap:4px;padding:12px;border-left:3px solid #5b7cfa;background:#f7f9fd;border-radius:0 12px 12px 0}.timeline-preview span,.timeline-preview p,.review-card small{color:var(--muted)}.timeline-preview p{margin:2px 0;white-space:pre-wrap;overflow-wrap:anywhere}.compact-form{display:grid;gap:13px;padding-top:18px;margin-top:18px;border-top:1px solid var(--line)}.form-grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}.draft-confirm{display:grid;gap:10px;margin-top:16px;padding:16px;border-radius:15px;background:#f5f8ff}.task-card{display:grid;grid-template-columns:minmax(0,1fr) minmax(220px,.5fr) auto;gap:14px;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)}.task-card p{margin:5px 0;color:var(--muted)}.task-card>div:last-child,.review-card>div:nth-child(2),.assignment-list article>div:last-child{display:flex;gap:8px}.review-list,.assignment-list{display:grid;gap:10px;margin-top:18px}.review-card{display:grid;gap:10px;padding:14px;border:1px solid var(--line);border-radius:14px}.review-card>div:first-child{display:grid;gap:5px}.assignment-list article{display:grid;grid-template-columns:1fr auto;gap:12px;align-items:center;padding:12px;background:#f7f9fd;border-radius:12px}.assignment-list p{margin:4px 0;color:var(--muted)}@media(max-width:900px){.workbench-grid{grid-template-columns:1fr}.form-grid.three{grid-template-columns:1fr}.task-card,.assignment-list article{grid-template-columns:1fr}.customer-list{max-height:420px}.customer-detail dl{grid-template-columns:1fr}.identity-strip{grid-template-columns:auto 1fr}.identity-strip>span:last-child{grid-column:2}.search-row{grid-template-columns:1fr}.search-row button,.compact-form button,.task-card button,.review-card button{min-height:46px}}
</style>
