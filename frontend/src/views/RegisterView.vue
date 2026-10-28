<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'

interface TeamOption { id: string; name: string }
const form = reactive({ username: '', realName: '', password: '', confirmPassword: '', registrationCode: '', teamId: '' })
const teams = ref<TeamOption[]>([])
const loadingTeams = ref(false)
const busy = ref(false)
const error = ref('')
const registeredName = ref('')

async function submit() {
  error.value = ''
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (!form.teamId) {
    error.value = '请先验证注册码并选择要加入的团队'
    return
  }
  busy.value = true
  try {
    await api('/api/v1/auth/register', {
      method: 'POST',
      body: jsonBody({
        username: form.username,
        realName: form.realName,
        password: form.password,
        registrationCode: form.registrationCode,
        teamId: form.teamId,
      }),
    })
    registeredName.value = form.realName
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '注册失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

async function loadTeams() {
  error.value = ''
  if (!form.registrationCode) { error.value = '请先填写注册码'; return }
  loadingTeams.value = true
  try {
    const result = await api<{ teams: TeamOption[] }>('/api/v1/auth/registration-options', {
      method: 'POST', body: jsonBody({ registrationCode: form.registrationCode }),
    })
    teams.value = result.teams
    form.teamId = result.teams.length === 1 ? result.teams[0].id : ''
  } catch (reason) {
    teams.value = []; form.teamId = ''
    error.value = reason instanceof ApiError ? reason.message : '注册码验证失败'
  } finally { loadingTeams.value = false }
}

watch(() => form.registrationCode, () => { teams.value = []; form.teamId = '' })
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="brand brand-light"><span class="brand-mark">Y</span><strong>英格客户云</strong></div>
      <div>
        <p class="eyebrow light">JOIN YINGGE TEAM</p>
        <h1>从真实协作开始，<br>一起完成每个目标。</h1>
        <p>使用管理员提供的注册码加入团队。注册账户固定为成员权限，组织与数据范围均由服务端确定。</p>
      </div>
      <div class="security-note">注册码校验 · 成员权限 · 全程审计</div>
    </section>
    <section class="login-panel">
      <div v-if="registeredName" class="login-card registration-success" role="status">
        <span class="success-icon">✓</span>
        <p class="eyebrow">注册完成</p>
        <h2>欢迎，{{ registeredName }}</h2>
        <p class="muted">成员账户已经创建，现在可以使用用户名和密码登录工作台。</p>
        <RouterLink class="primary-button wide" to="/login">前往登录</RouterLink>
      </div>
      <form v-else class="login-card" @submit.prevent="submit">
        <div><p class="eyebrow">团队注册</p><h2>创建成员账户</h2><p class="muted">请填写真实姓名，并使用管理员发放的注册码。</p></div>
        <label>注册码<div class="input-action-row"><input v-model.trim="form.registrationCode" autocomplete="one-time-code" maxlength="100" required autofocus placeholder="YG-XXXX-XXXX-XXXX-XXXX"><button type="button" class="soft-button" :disabled="loadingTeams" @click="loadTeams">{{ loadingTeams ? '验证中…' : '验证注册码' }}</button></div></label>
        <label>加入团队<select v-model="form.teamId" required :disabled="!teams.length"><option value="">{{ teams.length ? '请选择团队' : '请先验证注册码' }}</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select><small class="field-hint">注册成功后以普通成员身份加入所选团队</small></label>
        <label>用户名<input v-model.trim="form.username" autocomplete="username" maxlength="100" required placeholder="用于登录"></label>
        <label>真实姓名<input v-model.trim="form.realName" autocomplete="name" maxlength="120" required placeholder="请填写本人真实姓名"></label>
        <label>密码<input v-model="form.password" type="password" autocomplete="new-password" maxlength="200" required placeholder="可使用任意非空密码"><small class="field-hint">不限制复杂度</small></label>
        <label>确认密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" maxlength="200" required placeholder="再次输入密码"></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button wide" :disabled="busy">{{ busy ? '正在注册…' : '注册为团队成员' }}</button>
        <p class="auth-switch">已有账户？<RouterLink to="/login">返回登录</RouterLink></p>
      </form>
    </section>
  </main>
</template>

<style scoped>
@media (max-width: 900px) {
  .login-page { min-height: 100dvh; grid-template-columns: 1fr; }
  .login-story { min-height: auto; padding: max(24px, env(safe-area-inset-top)) 22px 22px; }
  .login-story > div:nth-child(2) { display: none; }
  .login-panel { align-items: flex-start; padding: 24px 14px calc(32px + env(safe-area-inset-bottom)); }
  .login-card { width: 100%; max-width: 560px; padding: 24px 18px; }
  .input-action-row { display: grid; grid-template-columns: 1fr; gap: 8px; }
  .input-action-row button { width: 100%; min-height: 46px; }
  .login-card input, .login-card select { min-height: 48px; font-size: 16px; }
  .login-card .primary-button { min-height: 50px; }
}
</style>
