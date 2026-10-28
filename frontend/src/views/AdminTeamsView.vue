<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'

interface Team {
  id: string
  name: string
  active: boolean
  leaderMembershipId?: string
  leaderDisplayName?: string
  memberCount: number
}
interface Member {
  membershipId: string
  displayName: string
  username: string
  status: string
  roles: string[]
}

const teams = ref<Team[]>([])
const members = ref<Member[]>([])
const name = ref('')
const error = ref('')
const leaderError = ref('')
const savingTeamId = ref('')
const leaderSelections = reactive<Record<string, string>>({})

async function load() {
  const [teamRows, memberRows] = await Promise.all([
    api<Team[]>('/api/v1/admin/teams'), api<Member[]>('/api/v1/admin/members'),
  ])
  teams.value = teamRows
  members.value = memberRows.filter((member) => member.status === 'ACTIVE')
  teamRows.forEach((team) => { leaderSelections[team.id] = team.leaderMembershipId ?? '' })
}

async function create() {
  error.value = ''
  try {
    await api('/api/v1/admin/teams', { method: 'POST', body: jsonBody({ name: name.value }) })
    name.value = ''
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '创建失败' }
}

async function assignLeader(team: Team) {
  const membershipId = leaderSelections[team.id]
  leaderError.value = ''
  if (!membershipId) {
    leaderError.value = `请为“${team.name}”选择团队长`
    return
  }
  savingTeamId.value = team.id
  try {
    await api(`/api/v1/admin/teams/${team.id}/leader`, {
      method: 'PUT', body: jsonBody({ membershipId }),
    })
    await load()
  } catch (reason) { leaderError.value = reason instanceof ApiError ? reason.message : '团队长设置失败' }
  finally { savingTeamId.value = '' }
}

async function deleteTeam(team: Team) {
  leaderError.value = ''
  if (!window.confirm(`确认删除团队“${team.name}”？成员账号和历史记录不会删除，成员将变为未分组。`)) return
  try {
    await api(`/api/v1/admin/teams/${team.id}`, { method: 'DELETE' })
    await load()
  } catch (reason) { leaderError.value = reason instanceof ApiError ? reason.message : '团队删除失败' }
}

onMounted(load)
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">TEAM SCOPE</p><h1>团队设置</h1><p class="muted">每个团队指定一名团队长；团队长可查看本团队所有成员录入或负责的客户。</p></div></header>
    <form class="panel inline-form" @submit.prevent="create"><label>新团队名称<input v-model.trim="name" required placeholder="例如：示例市咨询组"></label><button class="primary-button">创建团队</button><p v-if="error" class="form-error">{{ error }}</p></form>
    <p v-if="leaderError" class="notice error">{{ leaderError }}</p>
    <div class="team-leader-grid">
      <article v-for="team in teams" :key="team.id" class="panel team-leader-card">
        <header><span class="team-icon">◇</span><div><p class="eyebrow">{{ team.memberCount }} MEMBERS</p><h2>{{ team.name }}</h2><span class="table-status" :class="{ ready: team.leaderMembershipId, failed: !team.leaderMembershipId }">{{ team.leaderMembershipId ? '已指定团队长' : '待指定团队长' }}</span></div></header>
        <div class="leader-current"><small>当前团队长</small><strong>{{ team.leaderDisplayName || '尚未指定' }}</strong><p>可查看团队全部成员的客户档案与跟进记录。</p></div>
        <form class="leader-select-form" @submit.prevent="assignLeader(team)">
          <label>指定或更换团队长<select v-model="leaderSelections[team.id]" required><option value="">请选择成员</option><option v-for="member in members" :key="member.membershipId" :value="member.membershipId">{{ member.displayName }}（{{ member.username }}）</option></select></label>
          <button class="secondary-button" :disabled="savingTeamId === team.id">{{ savingTeamId === team.id ? '保存中…' : '保存团队长' }}</button>
        </form>
        <button class="danger-link" type="button" @click="deleteTeam(team)">删除团队（不删除成员）</button>
      </article>
      <div v-if="!teams.length" class="empty-state panel">尚未创建团队。</div>
    </div>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .inline-form { grid-template-columns: 1fr; padding: 18px 16px; }
  .inline-form button { width: 100%; min-height: 48px; }
  .team-leader-grid { grid-template-columns: 1fr; }
  .team-leader-card { padding: 18px 16px; }
  .team-leader-card header { align-items: flex-start; }
  .leader-select-form { grid-template-columns: 1fr; }
  .leader-select-form button, .team-leader-card > .danger-link { width: 100%; min-height: 48px; }
}
</style>
