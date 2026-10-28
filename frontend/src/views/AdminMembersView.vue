<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { dateTime, toIso } from '../lib/format'

interface Member { membershipId: string; username: string; displayName: string; status: string; roles: string[]; teams: string[]; teamIds: string[]; ownedCustomerCount: number }
interface Team { id: string; name: string; active: boolean }
interface RegistrationCode { id: string; codeHint: string; maxUses: number; usedCount: number; expiresAt?: string; status: string; createdAt: string; lastUsedAt?: string }
interface CreatedRegistrationCode extends RegistrationCode { code: string }
const members = ref<Member[]>([])
const teams = ref<Team[]>([])
const codes = ref<RegistrationCode[]>([])
const open = ref(false)
const error = ref('')
const codeError = ref('')
const createdCode = ref('')
const form = reactive({ username: '', displayName: '', initialPassword: '', role: 'MEMBER', teamId: '' })
const codeForm = reactive({ maxUses: 1, expiresAt: '' })
const editingMemberId = ref('')
const editForm = reactive({ displayName: '', role: 'MEMBER', status: 'ACTIVE', teamId: '' })
const transferTargets = reactive<Record<string, string>>({})
async function load() {
  [members.value, teams.value, codes.value] = await Promise.all([
    api<Member[]>('/api/v1/admin/members'), api<Team[]>('/api/v1/admin/teams'),
    api<RegistrationCode[]>('/api/v1/admin/registration-codes'),
  ])
}
async function create() {
  error.value = ''
  try { await api('/api/v1/admin/members', { method: 'POST', body: jsonBody({ ...form, teamId: form.teamId || undefined }) }); open.value = false; Object.assign(form, { username: '', displayName: '', initialPassword: '', role: 'MEMBER', teamId: '' }); await load() }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '创建成员失败' }
}
async function createRegistrationCode() {
  codeError.value = ''
  createdCode.value = ''
  try {
    const result = await api<CreatedRegistrationCode>('/api/v1/admin/registration-codes', {
      method: 'POST', body: jsonBody({ maxUses: codeForm.maxUses, expiresAt: toIso(codeForm.expiresAt) }),
    })
    createdCode.value = result.code
    codes.value = await api<RegistrationCode[]>('/api/v1/admin/registration-codes')
  } catch (reason) { codeError.value = reason instanceof ApiError ? reason.message : '创建注册码失败' }
}
async function disableRegistrationCode(id: string) {
  codeError.value = ''
  try {
    await api(`/api/v1/admin/registration-codes/${id}/disable`, { method: 'POST' })
    codes.value = await api<RegistrationCode[]>('/api/v1/admin/registration-codes')
  } catch (reason) { codeError.value = reason instanceof ApiError ? reason.message : '停用注册码失败' }
}
function startEdit(member: Member) {
  editingMemberId.value = member.membershipId
  Object.assign(editForm, { displayName: member.displayName, role: member.roles[0] ?? 'MEMBER', status: member.status, teamId: member.teamIds[0] ?? '' })
}
async function saveMember() {
  error.value = ''
  try {
    await api(`/api/v1/admin/members/${editingMemberId.value}`, {
      method: 'PATCH', body: jsonBody({ ...editForm, teamId: editForm.teamId || undefined }),
    })
    editingMemberId.value = ''; await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '成员修改失败' }
}
async function deleteMember(member: Member) {
  error.value = ''
  const transferCustomerToMembershipId = transferTargets[member.membershipId] || undefined
  if (member.ownedCustomerCount > 0 && !transferCustomerToMembershipId) {
    error.value = `“${member.displayName}”仍负责 ${member.ownedCustomerCount} 位客户，请先选择客户接收账号。`; return
  }
  const transferText = member.ownedCustomerCount > 0 ? `，并把 ${member.ownedCustomerCount} 位客户转移给所选账号` : ''
  if (!window.confirm(`确认删除成员“${member.displayName}”${transferText}？账号将停用，历史记录保留。`)) return
  try {
    await api(`/api/v1/admin/members/${member.membershipId}/delete`, {
      method: 'POST', body: jsonBody({ transferCustomerToMembershipId }),
    })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '成员删除失败' }
}
function codeStatus(status: string) {
  return ({ ACTIVE: '可用', EXHAUSTED: '次数已满', EXPIRED: '已过期', DISABLED: '已停用' } as Record<string, string>)[status] ?? status
}
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><p class="eyebrow">ORGANIZATION ADMIN</p><h1>成员账户</h1><p class="muted">管理员可直接创建账户，也可发放注册码让成员填写真实姓名后注册。</p></div><button class="primary-button" @click="open = !open">＋ 添加成员</button></header>
    <form v-if="open" class="panel inline-create form-grid five" @submit.prevent="create"><label>用户名<input v-model.trim="form.username" required></label><label>姓名<input v-model.trim="form.displayName" required></label><label>初始密码<input v-model="form.initialPassword" type="password" maxlength="200" required><small class="field-hint">不限制复杂度</small></label><label>角色<select v-model="form.role"><option>MEMBER</option><option>VIEWER</option><option>TEAM_MANAGER</option><option>ORG_ADMIN</option></select></label><label>团队<select v-model="form.teamId"><option value="">暂不分配</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label><p v-if="error" class="form-error span-5">{{ error }}</p><button class="primary-button">创建账户</button></form>
    <section class="panel registration-code-panel">
      <div class="panel-title"><div><p class="eyebrow">REGISTRATION CODES</p><h2>注册码管理</h2></div><span>注册后固定为 MEMBER</span></div>
      <form class="registration-code-form" @submit.prevent="createRegistrationCode">
        <label>最大使用次数<input v-model.number="codeForm.maxUses" type="number" min="1" max="1000" required></label>
        <label>有效期（可选）<input v-model="codeForm.expiresAt" type="datetime-local"></label>
        <button class="primary-button">生成注册码</button>
      </form>
      <div v-if="createdCode" class="registration-code-reveal" role="status"><div><strong>注册码仅在生成时完整显示，请立即保存</strong><small>数据库不会保存明文注册码</small></div><input :value="createdCode" readonly aria-label="新生成的注册码"></div>
      <p v-if="codeError" class="form-error">{{ codeError }}</p>
      <div v-if="codes.length" class="registration-code-list">
        <article v-for="code in codes" :key="code.id">
          <div><strong>{{ code.codeHint }}</strong><small>创建于 {{ dateTime(code.createdAt) }}<template v-if="code.expiresAt"> · 到期 {{ dateTime(code.expiresAt) }}</template></small></div>
          <span>{{ code.usedCount }}/{{ code.maxUses }} 次</span>
          <span class="table-status" :class="{ ready: code.status === 'ACTIVE', failed: code.status !== 'ACTIVE' }">{{ codeStatus(code.status) }}</span>
          <button v-if="code.status === 'ACTIVE'" class="text-button danger" type="button" @click="disableRegistrationCode(code.id)">停用</button>
        </article>
      </div>
      <p v-else class="empty-state small">尚未创建注册码</p>
    </section>
    <form v-if="editingMemberId" class="panel form-grid five" @submit.prevent="saveMember">
      <div class="panel-title span-5"><div><p class="eyebrow">MEMBER EDIT</p><h2>修改成员</h2></div><button type="button" class="text-button" @click="editingMemberId = ''">取消</button></div>
      <label>真实姓名<input v-model.trim="editForm.displayName" maxlength="120" required></label>
      <label>角色<select v-model="editForm.role"><option>MEMBER</option><option>VIEWER</option><option>TEAM_MANAGER</option><option>ORG_ADMIN</option></select></label>
      <label>状态<select v-model="editForm.status"><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select></label>
      <label>团队<select v-model="editForm.teamId"><option value="">不分配团队</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label>
      <button class="primary-button">保存修改</button>
    </form>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section class="panel table-wrap"><table class="mobile-card-table"><thead><tr><th>成员</th><th>用户名</th><th>角色</th><th>团队</th><th>状态</th><th>客户转移与操作</th></tr></thead><tbody><tr v-for="member in members" :key="member.membershipId"><td data-label="成员"><div class="person-cell"><span class="avatar small">{{ member.displayName.slice(0, 1) }}</span><strong>{{ member.displayName }}</strong></div><small>{{ member.ownedCustomerCount }} 位负责客户</small></td><td data-label="用户名">{{ member.username }}</td><td data-label="角色">{{ member.roles.join('、') }}</td><td data-label="团队">{{ member.teams.join('、') || '—' }}</td><td data-label="状态"><span :class="['table-status', { ready: member.status === 'ACTIVE', failed: member.status !== 'ACTIVE' }]">{{ member.status }}</span></td><td data-label="客户转移与操作"><div class="member-actions"><select v-if="member.ownedCustomerCount > 0" v-model="transferTargets[member.membershipId]" aria-label="客户接收账号"><option value="">删除前选择客户接收账号</option><option v-for="target in members.filter((item) => item.membershipId !== member.membershipId && item.status === 'ACTIVE')" :key="target.membershipId" :value="target.membershipId">{{ target.displayName }}</option></select><button class="text-button" type="button" @click="startEdit(member)">修改</button><button class="danger-link" type="button" @click="deleteMember(member)">删除</button></div></td></tr></tbody></table></section>
  </div>
</template>

<style scoped>
@media(max-width:900px){
  .inline-create{margin-bottom:16px}
  .table-wrap{padding:12px;overflow:visible}
  .member-actions{display:grid;grid-template-columns:1fr 1fr;min-width:0;width:100%;gap:10px}
  .member-actions select{grid-column:1/-1;min-width:0;width:100%}
  .member-actions .text-button,.member-actions .danger-link{min-height:44px;margin:0;border-radius:10px;background:#f4f7fd}
}
</style>
