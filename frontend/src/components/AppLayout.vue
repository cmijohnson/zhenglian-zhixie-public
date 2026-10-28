<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import {
  bottomNavigationForRoles,
  commonNavigationForRoles,
  desktopNavigationForRoles,
  isNavigationActive,
  moreNavigationForRoles,
} from '../lib/navigation'
import { useAuthStore } from '../stores/auth'
import type { ProductRelease, Reminder } from '../types'
import MobileBottomNav from './MobileBottomNav.vue'
import MobileMoreSheet from './MobileMoreSheet.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const urgentReminders = ref<Reminder[]>([])
const urgentOpen = ref(false)
const pendingRelease = ref<ProductRelease | null>(null)
const releaseBusy = ref(false)
const releaseError = ref('')
const unreadNotifications = ref(0)
const keyboardOpen = ref(false)
const mobileMenuPushed = ref(false)
const releaseOpen = computed(() => pendingRelease.value !== null && !urgentOpen.value)
const roles = computed(() => auth.user?.roles ?? [])
const desktopGroups = computed(() => desktopNavigationForRoles(roles.value))
const mobileBottomItems = computed(() => bottomNavigationForRoles(roles.value))
const mobileMoreGroups = computed(() => moreNavigationForRoles(roles.value))
const mobileCommonItems = computed(() => commonNavigationForRoles(roles.value))
const mobileMoreOpen = computed(() => route.query.mobileMenu === '1')
const mobileMoreActive = computed(() => mobileMoreOpen.value
  || !mobileBottomItems.value.some((item) => isNavigationActive(route.path, item.to)))
let releasePoll: number | undefined
let navigationPoll: number | undefined
let keyboardTimer: number | undefined

async function logout() {
  await auth.logout()
  await router.push('/login')
}

async function loadUrgentReminders() {
  try {
    const rows = await api<Reminder[]>('/api/v1/reminders/urgent-a-class')
    urgentReminders.value = Array.isArray(rows) ? rows : []
    urgentOpen.value = urgentReminders.value.length > 0
  } catch {
    urgentReminders.value = []
  }
}

async function loadPendingRelease() {
  try {
    pendingRelease.value = await api<ProductRelease | undefined>('/api/v1/releases/current/unacknowledged') ?? null
  } catch {
    pendingRelease.value = null
  }
}

async function loadNotificationCount() {
  try {
    const inbox = await api<{ unreadCount?: number }>('/api/v1/notifications?unreadOnly=true&size=1')
    unreadNotifications.value = Number(inbox?.unreadCount ?? 0)
  } catch {
    unreadNotifications.value = 0
  }
}

async function acknowledgeRelease(openHistory = false) {
  if (!pendingRelease.value || releaseBusy.value) return
  releaseBusy.value = true
  releaseError.value = ''
  try {
    await api(`/api/v1/releases/${pendingRelease.value.id}/acknowledge`, { method: 'POST' })
    pendingRelease.value = null
    if (openHistory) await router.push('/releases')
  } catch {
    releaseError.value = '确认状态保存失败，请稍后重试。'
  } finally {
    releaseBusy.value = false
  }
}

function dismissUrgent() {
  urgentOpen.value = false
}

function handleWindowFocus() {
  void loadPendingRelease()
  void loadNotificationCount()
}

function isEditable(element: Element | null) {
  return element instanceof HTMLInputElement
    || element instanceof HTMLTextAreaElement
    || element instanceof HTMLSelectElement
    || (element instanceof HTMLElement && element.isContentEditable)
}

function updateKeyboardState() {
  if (window.innerWidth > 900 || !isEditable(document.activeElement)) {
    keyboardOpen.value = false
    return
  }
  const viewport = window.visualViewport
  keyboardOpen.value = !viewport || viewport.height < window.innerHeight - 120
}

function scheduleKeyboardUpdate() {
  if (keyboardTimer !== undefined) window.clearTimeout(keyboardTimer)
  keyboardTimer = window.setTimeout(updateKeyboardState, 80)
}

async function openMobileMore() {
  if (mobileMoreOpen.value) return
  mobileMenuPushed.value = true
  await router.push({ path: route.path, query: { ...route.query, mobileMenu: '1' }, hash: route.hash })
}

async function closeMobileMore() {
  if (!mobileMoreOpen.value) return
  if (mobileMenuPushed.value) {
    mobileMenuPushed.value = false
    router.back()
    return
  }
  const query = { ...route.query }
  delete query.mobileMenu
  await router.replace({ path: route.path, query, hash: route.hash })
}

async function selectMobileNavigation(path: string) {
  mobileMenuPushed.value = false
  await router.replace(path)
}

onMounted(() => {
  void loadUrgentReminders()
  void loadPendingRelease()
  void loadNotificationCount()
  releasePoll = window.setInterval(loadPendingRelease, 5 * 60 * 1000)
  navigationPoll = window.setInterval(loadNotificationCount, 60 * 1000)
  window.addEventListener('focus', handleWindowFocus)
  window.addEventListener('resize', scheduleKeyboardUpdate)
  window.visualViewport?.addEventListener('resize', scheduleKeyboardUpdate)
  document.addEventListener('focusin', scheduleKeyboardUpdate)
  document.addEventListener('focusout', scheduleKeyboardUpdate)
})

onBeforeUnmount(() => {
  if (releasePoll !== undefined) window.clearInterval(releasePoll)
  if (navigationPoll !== undefined) window.clearInterval(navigationPoll)
  if (keyboardTimer !== undefined) window.clearTimeout(keyboardTimer)
  window.removeEventListener('focus', handleWindowFocus)
  window.removeEventListener('resize', scheduleKeyboardUpdate)
  window.visualViewport?.removeEventListener('resize', scheduleKeyboardUpdate)
  document.removeEventListener('focusin', scheduleKeyboardUpdate)
  document.removeEventListener('focusout', scheduleKeyboardUpdate)
})

watch(() => route.path, () => {
  mobileMenuPushed.value = false
  void loadNotificationCount()
})

watch(mobileMoreOpen, (open) => {
  if (!open) mobileMenuPushed.value = false
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">Y</span>
        <div><strong>英格客户云</strong><small>营销团队工作台</small></div>
      </div>
      <nav class="nav-list">
        <template v-for="group in desktopGroups" :key="group.key">
          <div v-if="group.key !== 'core'" class="nav-caption">{{ group.label }}</div>
          <RouterLink v-for="link in group.items" :key="link.to" :to="link.to">
            <span aria-hidden="true">{{ link.icon }}</span>{{ link.label }}
          </RouterLink>
        </template>
      </nav>
      <div class="sidebar-footer">
        <RouterLink to="/settings" class="profile-link">
          <span class="avatar">{{ auth.user?.displayName.slice(0, 1) }}</span>
          <span><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.roles.join(' · ') }}</small></span>
        </RouterLink>
        <button class="text-button" @click="logout">退出</button>
      </div>
    </aside>
    <main class="main-area"><RouterView /></main>
    <MobileBottomNav
      :items="mobileBottomItems"
      :urgent-count="urgentReminders.length"
      :unread-count="unreadNotifications"
      :more-active="mobileMoreActive"
      :hidden="keyboardOpen || urgentOpen || releaseOpen"
      @more="openMobileMore"
    />
    <MobileMoreSheet
      :open="mobileMoreOpen"
      :groups="mobileMoreGroups"
      :common-items="mobileCommonItems"
      :current-path="route.path"
      :display-name="auth.user?.displayName"
      :roles="roles"
      @close="closeMobileMore"
      @select="selectMobileNavigation"
      @logout="logout"
    />
    <div v-if="urgentOpen" class="urgent-followup-overlay" role="alertdialog" aria-modal="true" aria-labelledby="urgent-followup-title">
      <section class="urgent-followup-card">
        <p class="eyebrow">CRITICAL FOLLOW-UP</p>
        <h2 id="urgent-followup-title">A 类客户跟进已超时</h2>
        <p>以下 {{ urgentReminders.length }} 位 A 类客户已超过 72 小时没有有效时间线更新。提醒只能由新的有效跟进自动解除。</p>
        <div class="urgent-followup-list">
          <article v-for="item in urgentReminders" :key="item.id">
            <div><strong>{{ item.title }}</strong><small>请及时补充真实沟通结果和下一步。</small></div>
            <RouterLink class="primary-button" :to="`/customers/${item.customerId}`" @click="dismissUrgent">立即处理</RouterLink>
          </article>
        </div>
        <button class="soft-button wide" @click="dismissUrgent">我已知晓，本次登录稍后处理</button>
        <small>若仍未完成有效跟进，下次登录将再次全屏提醒。</small>
      </section>
    </div>
    <div v-if="releaseOpen" class="release-update-overlay" role="dialog" aria-modal="true" aria-labelledby="release-update-title">
      <section class="release-update-card">
        <div class="release-update-badge">NEW · v{{ pendingRelease?.version }}</div>
        <p class="eyebrow">PRODUCT UPDATE</p>
        <h2 id="release-update-title">{{ pendingRelease?.title }}</h2>
        <p class="release-update-summary">{{ pendingRelease?.summary }}</p>
        <div class="release-update-sections">
          <section v-for="(items, section) in pendingRelease?.sections" :key="section">
            <strong>{{ section }}</strong>
            <ul><li v-for="item in items" :key="item">{{ item }}</li></ul>
          </section>
        </div>
        <p v-if="releaseError" class="notice error">{{ releaseError }}</p>
        <div class="release-update-actions">
          <button class="ghost-button" :disabled="releaseBusy" @click="acknowledgeRelease(true)">查看完整更新</button>
          <button class="primary-button" :disabled="releaseBusy" @click="acknowledgeRelease(false)">{{ releaseBusy ? '正在保存…' : '我已了解' }}</button>
        </div>
        <small>确认后本账号不再重复展示此版本，历史内容可随时在“更新日志”中查看。</small>
      </section>
    </div>
  </div>
</template>

<style scoped>
.urgent-followup-overlay{position:fixed;inset:0;z-index:var(--layer-critical-alert,10000);display:grid;place-items:center;padding:24px;background:rgba(8,18,48,.78);backdrop-filter:blur(18px)}
.urgent-followup-card{width:min(760px,100%);max-height:min(760px,calc(100vh - 48px));overflow:auto;padding:34px;border:1px solid rgba(255,255,255,.65);border-radius:26px;background:linear-gradient(145deg,rgba(255,255,255,.98),rgba(242,246,255,.96));box-shadow:0 32px 90px rgba(0,13,55,.38)}
.urgent-followup-card h2{margin:8px 0 10px;font-size:30px;color:#a22626}.urgent-followup-card>p{color:var(--muted);line-height:1.7}.urgent-followup-card>small{display:block;margin-top:12px;text-align:center;color:var(--muted)}
.urgent-followup-list{display:grid;gap:10px;margin:24px 0}.urgent-followup-list article{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:16px;border:1px solid #ead7d7;border-radius:15px;background:#fff8f7}.urgent-followup-list strong,.urgent-followup-list small{display:block}.urgent-followup-list small{margin-top:5px;color:var(--muted)}
.release-update-overlay{position:fixed;inset:0;z-index:var(--layer-release-notice,9999);display:grid;place-items:center;padding:24px;background:rgba(9,18,45,.72);backdrop-filter:blur(20px)}.release-update-card{position:relative;width:min(780px,100%);max-height:min(800px,calc(100vh - 48px));overflow:auto;padding:38px;border:1px solid rgba(255,255,255,.7);border-radius:28px;background:radial-gradient(circle at 92% 5%,rgba(122,155,255,.22),transparent 28%),linear-gradient(145deg,#fff,#f3f6ff);box-shadow:0 34px 100px rgba(0,13,55,.4)}.release-update-badge{position:absolute;top:24px;right:26px;padding:7px 11px;border-radius:999px;color:#fff;background:linear-gradient(135deg,#6f8cff,#3157d5);font-size:10px;font-weight:800;letter-spacing:.08em}.release-update-card h2{margin:8px 0 10px;font-size:31px}.release-update-summary{color:#59657c;line-height:1.75}.release-update-sections{display:grid;gap:10px;margin:24px 0}.release-update-sections section{display:grid;grid-template-columns:64px minmax(0,1fr);gap:16px;padding:14px 16px;border:1px solid #e4e9f5;border-radius:15px;background:rgba(255,255,255,.76)}.release-update-sections strong{color:#3157d5;font-size:12px}.release-update-sections ul{display:grid;gap:6px;margin:0;padding-left:17px;color:#4f5c72;font-size:12px;line-height:1.6}.release-update-actions{display:flex;justify-content:flex-end;gap:12px}.release-update-card>small{display:block;margin-top:14px;text-align:right;color:var(--muted);font-size:10px}
@media(max-width:640px){.urgent-followup-overlay,.release-update-overlay{padding:12px}.urgent-followup-card,.release-update-card{padding:22px;border-radius:20px}.urgent-followup-card h2,.release-update-card h2{font-size:25px}.urgent-followup-list article{align-items:flex-start;flex-direction:column}.urgent-followup-list .primary-button{width:100%;text-align:center}.release-update-badge{position:static;display:inline-flex;margin-bottom:20px}.release-update-sections section{grid-template-columns:1fr}.release-update-actions{display:grid}.release-update-card>small{text-align:center}}
</style>
