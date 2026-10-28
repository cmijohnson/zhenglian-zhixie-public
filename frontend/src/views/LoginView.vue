<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, jsonBody } from '../lib/api'
import { useAuthStore } from '../stores/auth'
import type { Me } from '../types'

interface FeishuLoginOption { integrationId: string; organizationName: string }
interface FeishuLoginOptions { available: boolean; organizations: FeishuLoginOption[]; httpsRequired: boolean }

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const feishuOptions = ref<FeishuLoginOptions>({ available: false, organizations: [], httpsRequired: true })
const selectedIntegration = ref('')
const confirmationMode = computed(() => route.query.feishuStatus === 'confirm' && typeof route.query.feishuIntent === 'string')

async function submit() {
  busy.value = true
  error.value = ''
  try {
    if (confirmationMode.value) {
      const user = await api<Me>('/api/v1/auth/feishu/binding/confirm', {
        method: 'POST',
        body: jsonBody({ intentId: route.query.feishuIntent, username: username.value, password: password.value }),
      })
      auth.adopt(user)
    } else {
      await auth.login(username.value, password.value)
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '登录失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

function loginWithFeishu() {
  error.value = ''
  const integration = selectedIntegration.value || feishuOptions.value.organizations[0]?.integrationId
  if (!integration) {
    error.value = '当前没有可用的飞书登录组织'
    return
  }
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  const query = new URLSearchParams({ purpose: 'LOGIN', integrationId: integration, returnTo: redirect })
  window.location.assign(`/api/v1/auth/feishu/start?${query.toString()}`)
}

onMounted(async () => {
  if (confirmationMode.value) return
  try {
    feishuOptions.value = await api<FeishuLoginOptions>('/api/v1/auth/feishu/options')
    selectedIntegration.value = feishuOptions.value.organizations[0]?.integrationId || ''
  } catch {
    feishuOptions.value = { available: false, organizations: [], httpsRequired: true }
  }
})
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="brand brand-light"><span class="brand-mark">Y</span><strong>英格客户云</strong></div>
      <div>
        <p class="eyebrow light">YINGGE TEAM CLOUD</p>
        <h1>把每一次沟通，<br>变成清晰的下一步。</h1>
        <p>客户档案、跟进提醒与团队协作集中在一处，关键业务规则始终由服务端保护。</p>
      </div>
      <div class="security-note">安全会话 · 权限隔离 · 全程审计</div>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="submit">
        <div><p class="eyebrow">{{ confirmationMode ? '绑定确认' : '欢迎回来' }}</p><h2>{{ confirmationMode ? '绑定现有英格云账号' : '登录工作台' }}</h2><p class="muted">{{ confirmationMode ? '飞书身份已确认，请验证一次现有账号。系统不会自动创建账号或加入组织。' : '使用团队账户登录。' }}</p></div>
        <label>用户名<input v-model.trim="username" autocomplete="username" required autofocus placeholder="请输入用户名"></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="请输入密码"></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button wide" :disabled="busy">{{ busy ? '正在验证…' : confirmationMode ? '确认并绑定' : '安全登录' }}</button>
        <template v-if="!confirmationMode">
          <div class="login-divider"><span>或</span></div>
          <label v-if="feishuOptions.organizations.length > 1">登录组织
            <select v-model="selectedIntegration">
              <option v-for="item in feishuOptions.organizations" :key="item.integrationId" :value="item.integrationId">{{ item.organizationName }}</option>
            </select>
          </label>
          <button type="button" class="feishu-login wide" :disabled="!feishuOptions.available" @click="loginWithFeishu"><span class="feishu-mark">飞</span>使用飞书登录</button>
          <p v-if="!feishuOptions.available" class="transport-note">飞书快捷登录暂未开放，功能入口已保留。</p>
          <p v-else-if="feishuOptions.httpsRequired" class="transport-note">飞书快捷登录仅在可信 HTTPS 入口启用。</p>
        </template>
        <p v-if="!confirmationMode" class="auth-switch">还没有账户？<RouterLink to="/register">使用注册码注册</RouterLink></p>
      </form>
    </section>
  </main>
</template>

<style scoped>
@media (max-width: 900px) {
  .login-page { min-height: 100dvh; grid-template-columns: 1fr; }
  .login-story { min-height: auto; padding: max(24px, env(safe-area-inset-top)) 22px 22px; }
  .login-story > div:nth-child(2) { display: none; }
  .login-panel { align-items: flex-start; padding: 28px 16px calc(32px + env(safe-area-inset-bottom)); }
  .login-card { width: 100%; max-width: 520px; padding: 26px 20px; }
  .login-card input { min-height: 48px; font-size: 16px; }
  .login-card .primary-button { min-height: 50px; }
}
.login-divider{display:flex;align-items:center;gap:12px;color:var(--muted);font-size:12px}.login-divider::before,.login-divider::after{content:"";height:1px;flex:1;background:var(--line)}.feishu-login{min-height:48px;border:1px solid #b9c8e8;border-radius:14px;background:#fff;color:#1f2a44;font-weight:750;display:flex;align-items:center;justify-content:center;gap:9px;cursor:pointer}.feishu-login:disabled{opacity:.62;cursor:not-allowed}.feishu-mark{display:inline-grid;place-items:center;width:25px;height:25px;border-radius:8px;background:linear-gradient(145deg,#2f80ed,#00c9a7);color:#fff;font-size:13px}.transport-note{margin:0;text-align:center;color:#9a5b10;font-size:12px}.login-card select{min-height:48px;font-size:16px}
</style>
