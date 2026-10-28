<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { useAuthStore } from '../stores/auth'

type Distribution = 'MANDATORY' | 'ASSIGNED' | 'SELF_CLAIM'
type AssignmentStatus = 'PENDING' | 'IN_PROGRESS' | 'SUBMITTED' | 'COMPLETED' | 'RETURNED'
interface Assignment { id: string; membershipId: string; memberDisplayName: string; assignmentSource: string; status: AssignmentStatus; resultSummary?: string; evidenceUrl?: string; reviewFeedback?: string }
interface Task { id: string; title: string; description: string; distributionType: Distribution; startAt: string; dueAt: string; maxClaimants?: number; status: 'PUBLISHED' | 'CLOSED'; assignmentCount: number; completedCount: number; overdue: boolean; claimable: boolean; myAssignment?: Assignment; createdAt?: string }
interface Member { membershipId: string; displayName: string; status: string; roles: string[] }
interface TaskMatrixRow { task: Task; assignments: Assignment[] }

function localInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

const auth = useAuthStore()
const tasks = ref<Task[]>([])
const members = ref<Member[]>([])
const taskMatrix = ref<TaskMatrixRow[]>([])
const adminTaskView = ref<'matrix' | 'list'>('matrix')
const selectedAssignments = ref<Assignment[]>([])
const selectedTaskId = ref('')
const error = ref('')
const success = ref('')
const createOpen = ref(false)
const resultDrafts = reactive<Record<string, { resultSummary: string; evidenceUrl: string }>>({})
const feedbackDrafts = reactive<Record<string, string>>({})
const now = new Date()
const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000)
const createForm = reactive({ title: '', description: '', distributionType: 'MANDATORY' as Distribution, startAt: localInput(now), dueAt: localInput(tomorrow), maxClaimants: 1, assigneeIds: [] as string[] })

const mine = computed(() => tasks.value.filter((task) => task.myAssignment))
const pool = computed(() => tasks.value.filter((task) => task.distributionType === 'SELF_CLAIM' && !task.myAssignment && task.status === 'PUBLISHED'))
const matrixMembers = computed(() => {
  const rows = new Map<string, Pick<Member, 'membershipId' | 'displayName'>>()
  members.value
    .filter((member) => member.status === 'ACTIVE' && member.roles.some((role) => role === 'MEMBER' || role === 'TEAM_MANAGER'))
    .forEach((member) => rows.set(member.membershipId, member))
  taskMatrix.value.forEach((row) => row.assignments.forEach((assignment) => {
    if (!rows.has(assignment.membershipId)) rows.set(assignment.membershipId, {
      membershipId: assignment.membershipId,
      displayName: assignment.memberDisplayName,
    })
  }))
  return [...rows.values()]
})
const matrixAssignments = computed(() => {
  const rows = new Map<string, Assignment>()
  taskMatrix.value.forEach((row) => row.assignments.forEach((assignment) => {
    rows.set(`${row.task.id}:${assignment.membershipId}`, assignment)
  }))
  return rows
})
const matrixDate = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric' }).format(new Date())
const typeName = (value: Distribution) => ({ MANDATORY: '强制性', ASSIGNED: '派发性', SELF_CLAIM: '自领取性' }[value])
const statusName = (value: AssignmentStatus) => ({ PENDING: '待开始', IN_PROGRESS: '进行中', SUBMITTED: '待验收', COMPLETED: '已完成', RETURNED: '已退回' }[value])
const dateTime = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
const matrixAssignment = (taskId: string, membershipId: string) => matrixAssignments.value.get(`${taskId}:${membershipId}`)
const matrixStatusName = (status: AssignmentStatus) => status === 'PENDING' ? '未完成' : statusName(status)
const matrixStatusClass = (status: AssignmentStatus) => `matrix-status-${status.toLowerCase().replace('_', '-')}`

async function load() {
  let loaded: Task[]
  if (auth.isAdmin) {
    const [matrixRows, adminMembers] = await Promise.all([
      api<TaskMatrixRow[]>('/api/v1/tasks/matrix'),
      api<Member[]>('/api/v1/admin/members'),
    ])
    taskMatrix.value = matrixRows
    members.value = adminMembers
    loaded = matrixRows.map((row) => row.task)
  } else {
    loaded = await api<Task[]>('/api/v1/tasks')
  }
  tasks.value = [...loaded].sort((left, right) => {
    const leftTime = new Date(left.createdAt ?? left.startAt).getTime()
    const rightTime = new Date(right.createdAt ?? right.startAt).getTime()
    return rightTime - leftTime
  })
  for (const task of tasks.value) {
    if (task.myAssignment) resultDrafts[task.id] = { resultSummary: task.myAssignment.resultSummary ?? '', evidenceUrl: task.myAssignment.evidenceUrl ?? '' }
  }
}

async function createTask() {
  error.value = ''; success.value = ''
  try {
    await api('/api/v1/tasks', { method: 'POST', body: jsonBody({
      ...createForm,
      startAt: new Date(createForm.startAt).toISOString(), dueAt: new Date(createForm.dueAt).toISOString(),
      maxClaimants: createForm.distributionType === 'SELF_CLAIM' ? createForm.maxClaimants : undefined,
      assigneeIds: createForm.distributionType === 'ASSIGNED' ? createForm.assigneeIds : [],
    }) })
    success.value = '任务已发布并完成分发。'; createOpen.value = false
    Object.assign(createForm, { title: '', description: '', distributionType: 'MANDATORY', assigneeIds: [] })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '任务发布失败' }
}

async function claim(task: Task) {
  error.value = ''
  try { await api(`/api/v1/tasks/${task.id}/claim`, { method: 'POST' }); await load() }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '任务领取失败' }
}

async function updateMine(task: Task, status: 'IN_PROGRESS' | 'SUBMITTED') {
  error.value = ''; success.value = ''
  const draft = resultDrafts[task.id] ?? { resultSummary: '', evidenceUrl: '' }
  try {
    await api(`/api/v1/tasks/${task.id}/my-assignment`, { method: 'PATCH', body: jsonBody({ status, ...draft }) })
    success.value = status === 'SUBMITTED' ? '任务已提交验收。' : '任务已标记为进行中。'; await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '任务状态更新失败' }
}

async function showAssignments(task: Task) {
  selectedTaskId.value = task.id
  selectedAssignments.value = await api<Assignment[]>(`/api/v1/tasks/${task.id}/assignments`)
}

async function review(assignment: Assignment, status: 'COMPLETED' | 'RETURNED') {
  error.value = ''
  try {
    await api(`/api/v1/tasks/${selectedTaskId.value}/assignments/${assignment.id}`, {
      method: 'PATCH', body: jsonBody({ status, feedback: feedbackDrafts[assignment.id] }),
    })
    const task = tasks.value.find((item) => item.id === selectedTaskId.value)
    if (task) await showAssignments(task)
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '任务验收失败' }
}

async function closeTask(task: Task) {
  await api(`/api/v1/tasks/${task.id}/close`, { method: 'POST' }); await load()
}

async function deleteTask(task: Task) {
  if (!window.confirm(`确认删除任务“${task.title}”？尚未开始的分配会一并移除，此操作不可撤销。`)) return
  error.value = ''; success.value = ''
  try {
    await api(`/api/v1/tasks/${task.id}`, { method: 'DELETE' })
    if (selectedTaskId.value === task.id) {
      selectedTaskId.value = ''
      selectedAssignments.value = []
    }
    success.value = '任务已删除。'
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '任务删除失败' }
}

const taskNumber = (index: number) => String(tasks.value.length - index).padStart(3, '0')

onMounted(() => load().catch(() => { error.value = '任务中心暂时无法加载' }))
</script>

<template>
  <div class="page task-page">
    <header class="page-header"><div><p class="eyebrow">TASK DISTRIBUTION</p><h1>任务中心</h1><p class="muted">强制任务自动覆盖全部成员，派发任务指定到人，自领取任务进入公共任务池。</p></div><button v-if="auth.isAdmin" class="primary-button" @click="createOpen = !createOpen">＋ 分发任务</button></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>
    <form v-if="createOpen" class="panel task-create form-grid" @submit.prevent="createTask">
      <div class="panel-title"><div><p class="eyebrow">ADMIN</p><h2>创建并分发任务</h2></div></div>
      <div class="form-grid two"><label>任务标题<input v-model.trim="createForm.title" maxlength="200" required></label><label>分发方式<select v-model="createForm.distributionType"><option value="MANDATORY">强制性 · 全员自动分配</option><option value="ASSIGNED">派发性 · 指定成员</option><option value="SELF_CLAIM">自领取性 · 公共任务池</option></select></label></div>
      <label>任务说明<textarea v-model.trim="createForm.description" rows="5" maxlength="6000" required></textarea></label>
      <div class="form-grid two"><label>开始时间<input v-model="createForm.startAt" type="datetime-local" required></label><label>截止时间<input v-model="createForm.dueAt" type="datetime-local" required></label></div>
      <label v-if="createForm.distributionType === 'SELF_CLAIM'">领取人数上限<input v-model.number="createForm.maxClaimants" type="number" min="1" required></label>
      <div v-if="createForm.distributionType === 'ASSIGNED'" class="member-picker"><strong>选择派发成员</strong><label v-for="member in members.filter((item) => item.status === 'ACTIVE' && item.roles.some((role) => role === 'MEMBER' || role === 'TEAM_MANAGER'))" :key="member.membershipId" class="check-label"><input v-model="createForm.assigneeIds" type="checkbox" :value="member.membershipId">{{ member.displayName }}</label></div>
      <button class="primary-button">发布任务</button>
    </form>

    <section class="panel task-section"><div class="panel-title"><div><p class="eyebrow">MY REQUIRED TASKS</p><h2>我的必须与已领取任务</h2></div><span>{{ mine.length }} 项</span></div>
      <article v-for="task in mine" :key="task.id" :class="['task-card', { overdue: task.overdue }]">
        <header><span :class="['badge', task.distributionType === 'MANDATORY' ? 'badge-red' : task.distributionType === 'ASSIGNED' ? 'badge-amber' : 'badge-green']">{{ typeName(task.distributionType) }}</span><span class="muted">截止 {{ dateTime(task.dueAt) }}</span></header>
        <h3>{{ task.title }}</h3><p>{{ task.description }}</p><p v-if="task.myAssignment?.reviewFeedback" class="notice error">退回说明：{{ task.myAssignment.reviewFeedback }}</p>
        <div class="task-status-line"><strong>{{ statusName(task.myAssignment!.status) }}</strong><span v-if="task.overdue">已逾期</span></div>
        <div v-if="task.myAssignment?.status !== 'COMPLETED'" class="task-submit"><textarea v-model.trim="resultDrafts[task.id].resultSummary" rows="3" placeholder="提交验收前填写成果说明"></textarea><input v-model.trim="resultDrafts[task.id].evidenceUrl" type="url" placeholder="成果链接（可选）"><div><button class="ghost-button" @click="updateMine(task, 'IN_PROGRESS')">标记进行中</button><button class="primary-button" @click="updateMine(task, 'SUBMITTED')">提交验收</button></div></div>
      </article><div v-if="!mine.length" class="empty-state">目前没有分配或领取的任务。</div>
    </section>

    <section class="panel task-section"><div class="panel-title"><div><p class="eyebrow">OPTIONAL POOL</p><h2>可领取任务</h2></div><span>{{ pool.length }} 项</span></div><article v-for="task in pool" :key="task.id" class="task-card"><header><span class="badge badge-green">自领取性</span><span class="muted">{{ task.assignmentCount }}/{{ task.maxClaimants ?? '不限' }} 人已领取</span></header><h3>{{ task.title }}</h3><p>{{ task.description }}</p><footer><span>截止 {{ dateTime(task.dueAt) }}</span><button class="primary-button" :disabled="!task.claimable" @click="claim(task)">{{ task.claimable ? '领取任务' : '暂不可领取' }}</button></footer></article><div v-if="!pool.length" class="empty-state small">当前没有开放领取的任务。</div></section>

    <template v-if="auth.canReviewOperations">
      <section class="panel task-section task-review-overview">
        <div class="panel-title">
          <div><p class="eyebrow">REVIEW OVERVIEW</p><h2>{{ auth.isAdmin ? '全部任务进度' : '本团队任务验收' }}</h2></div>
          <div v-if="auth.isAdmin" class="view-switch" data-testid="task-admin-view-switch" aria-label="任务管理展示模式">
            <button type="button" :class="{ active: adminTaskView === 'matrix' }" :aria-pressed="adminTaskView === 'matrix'" @click="adminTaskView = 'matrix'">矩阵</button>
            <button type="button" :class="{ active: adminTaskView === 'list' }" :aria-pressed="adminTaskView === 'list'" @click="adminTaskView = 'list'">列表</button>
          </div>
          <span v-else>最新任务优先</span>
        </div>
        <div v-if="auth.isAdmin && adminTaskView === 'matrix' && taskMatrix.length" class="task-matrix-wrap table-wrap" data-testid="task-matrix">
          <p class="mobile-scroll-hint" aria-hidden="true">左右滑动查看成员状态，任务名称会保持在左侧</p>
          <table class="task-matrix-table">
            <thead><tr><th>序号</th><th>公共任务 · {{ matrixDate }}</th><th>截止时间</th><th>备注</th><th v-for="member in matrixMembers" :key="member.membershipId" :title="member.displayName">{{ member.displayName }}</th><th>操作</th></tr></thead>
            <tbody><tr v-for="(row, index) in taskMatrix" :key="row.task.id">
              <td>{{ index + 1 }}</td>
              <td><span class="badge">{{ typeName(row.task.distributionType) }}</span><strong :title="row.task.title">{{ row.task.title }}</strong></td>
              <td>{{ dateTime(row.task.dueAt) }}</td>
              <td class="matrix-note" :title="row.task.description">{{ row.task.description }}</td>
              <td v-for="member in matrixMembers" :key="member.membershipId" class="matrix-member-cell">
                <button v-if="matrixAssignment(row.task.id, member.membershipId)" type="button" :class="['matrix-status', matrixStatusClass(matrixAssignment(row.task.id, member.membershipId)!.status)]" :title="`${member.displayName} · ${matrixStatusName(matrixAssignment(row.task.id, member.membershipId)!.status)}`" @click="showAssignments(row.task)">{{ matrixStatusName(matrixAssignment(row.task.id, member.membershipId)!.status) }}</button>
                <span v-else class="matrix-empty">—</span>
              </td>
              <td class="matrix-actions"><button class="text-button" @click="showAssignments(row.task)">验收</button><button v-if="row.task.status === 'PUBLISHED'" class="text-button" @click="closeTask(row.task)">关闭</button><button class="danger-link task-delete" @click="deleteTask(row.task)">删除</button></td>
            </tr></tbody>
          </table>
        </div>
        <div v-else-if="auth.isAdmin && adminTaskView === 'matrix'" class="empty-state small">当前还没有任务矩阵数据。</div>
        <template v-else>
          <article v-for="(task, index) in tasks" :key="task.id" class="task-admin-row"><span class="task-sequence">#{{ taskNumber(index) }}</span><div><span class="badge">{{ typeName(task.distributionType) }}</span><strong>{{ task.title }}</strong><small>{{ task.completedCount }}/{{ task.assignmentCount }} 已验收 · {{ task.status === 'CLOSED' ? '已关闭' : `截止 ${dateTime(task.dueAt)}` }}</small></div><button class="soft-button" @click="showAssignments(task)">查看验收</button><button v-if="auth.isAdmin && task.status === 'PUBLISHED'" class="text-button" @click="closeTask(task)">关闭任务</button><button v-if="auth.isAdmin" class="danger-link task-delete" @click="deleteTask(task)">删除</button></article>
        </template>
      </section>
      <section v-if="selectedTaskId" class="panel task-section"><div class="panel-title"><div><p class="eyebrow">REVIEW</p><h2>成员提交与验收</h2></div></div><article v-for="assignment in selectedAssignments" :key="assignment.id" class="review-row task-review-row"><div><strong>{{ assignment.memberDisplayName }} · {{ statusName(assignment.status) }}</strong><p>{{ assignment.resultSummary || '尚未填写成果' }}</p><a v-if="assignment.evidenceUrl" :href="assignment.evidenceUrl" target="_blank" rel="noopener noreferrer">查看成果链接 ↗</a></div><input v-model.trim="feedbackDrafts[assignment.id]" placeholder="退回时填写原因"><button class="soft-button" :disabled="assignment.status !== 'SUBMITTED'" @click="review(assignment, 'COMPLETED')">验收完成</button><button class="ghost-button" :disabled="assignment.status !== 'SUBMITTED'" @click="review(assignment, 'RETURNED')">退回</button></article><div v-if="!selectedAssignments.length" class="empty-state small">该任务还没有分配或领取记录。</div></section>
    </template>
  </div>
</template>

<style scoped>
.mobile-scroll-hint{display:none}
@media(max-width:700px){
  .page-header{align-items:stretch}.page-header>.primary-button{width:100%;min-height:46px}
  .task-create .form-grid.two{grid-template-columns:1fr}
  .member-picker{display:grid;grid-template-columns:1fr 1fr}.member-picker>strong{grid-column:1/-1}.member-picker label{min-width:0;min-height:44px}
  .task-card>header,.task-card>footer{align-items:flex-start;flex-direction:column}
  .task-card>footer .primary-button{width:100%;min-height:44px}
  .task-submit{grid-template-columns:1fr}.task-submit>div{display:grid;grid-template-columns:1fr 1fr}.task-submit button{min-height:44px}
  .task-review-overview>.panel-title{padding-inline:16px;align-items:flex-start;flex-wrap:wrap}.task-review-overview .view-switch{width:100%}.task-review-overview .view-switch button{flex:1;min-height:42px}
  .mobile-scroll-hint{position:sticky;left:0;z-index:7;display:block;width:min(calc(100vw - 64px),420px);margin:0;padding:9px 14px;color:#58657d;background:#eef3ff;font-size:11px;line-height:1.5}
  .task-matrix-wrap{max-height:70vh;overscroll-behavior:contain;scrollbar-gutter:stable;touch-action:pan-x pan-y}
  .task-matrix-table th:first-child,.task-matrix-table td:first-child{min-width:44px;width:44px}
  .task-matrix-table th:nth-child(2),.task-matrix-table td:nth-child(2){left:44px;min-width:180px;max-width:180px}
  .task-matrix-table td:nth-child(2) strong{max-width:145px}
  .task-admin-row{grid-template-columns:42px minmax(0,1fr);align-items:start;padding:16px 0}.task-admin-row>button{grid-column:2;width:100%;min-height:42px}.task-admin-row>.danger-link{justify-self:start}
  .task-review-row{display:grid;grid-template-columns:1fr}.task-review-row input,.task-review-row button{width:100%;min-height:44px}
}
</style>
