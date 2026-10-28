<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileBottomNav from '../components/MobileBottomNav.vue'
import MobileMoreSheet from '../components/MobileMoreSheet.vue'
import { ApiError, api, jsonBody } from '../lib/api'
import {
  bottomNavigationForRoles,
  commonNavigationForRoles,
  isNavigationActive,
  moreNavigationForRoles,
} from '../lib/navigation'
import { useAuthStore } from '../stores/auth'

interface DailyReport {
  id?: string
  membershipId: string
  memberDisplayName: string
  reportDate: string
  grade26NewValidLeads: number
  deepCommunicationCount: number
  pendingDeepCommunicationCount?: number
  performanceManaged?: boolean
  todayIssues: string
  tomorrowPlan: string
  submittedAt?: string
  updatedAt?: string
  submitted: boolean
}

interface StoredDraft {
  savedAt: string
  deepCommunicationCount: number
  todayIssues: string
  tomorrowPlan: string
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const report = ref<DailyReport>()
const loading = ref(true)
const busy = ref(false)
const completed = ref(false)
const draftReady = ref(false)
const error = ref('')
const mobileMoreOpen = ref(false)
const keyboardOpen = ref(false)
const form = reactive({
  deepCommunicationCount: 0,
  todayIssues: '',
  tomorrowPlan: '',
})

const memberName = computed(() => report.value?.memberDisplayName || auth.user?.displayName || '当前成员')
const roles = computed(() => auth.user?.roles ?? [])
const mobileBottomItems = computed(() => bottomNavigationForRoles(roles.value))
const mobileMoreGroups = computed(() => moreNavigationForRoles(roles.value))
const mobileCommonItems = computed(() => commonNavigationForRoles(roles.value))
const mobileMoreActive = computed(() => mobileMoreOpen.value
  || !mobileBottomItems.value.some((item) => isNavigationActive(route.path, item.to)))
const formattedDate = computed(() => {
  if (!report.value?.reportDate) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date(`${report.value.reportDate}T12:00:00+08:00`))
})
const draftKey = computed(() => report.value
  ? `yingge:daily-summary:${report.value.membershipId}:${report.value.reportDate}`
  : '')
let keyboardTimer: number | undefined

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

async function selectMobileNavigation(path: string) {
  mobileMoreOpen.value = false
  await router.push(path)
}

async function logout() {
  await auth.logout()
  await router.push('/login')
}

function parseDraft(raw: string | null): StoredDraft | undefined {
  if (!raw) return undefined
  try {
    const value = JSON.parse(raw) as Partial<StoredDraft>
    if (typeof value.savedAt !== 'string'
      || typeof value.deepCommunicationCount !== 'number'
      || typeof value.todayIssues !== 'string'
      || typeof value.tomorrowPlan !== 'string') return undefined
    return value as StoredDraft
  } catch {
    return undefined
  }
}

function restoreDraft(data: DailyReport) {
  if (!draftKey.value) return
  const draft = parseDraft(window.localStorage.getItem(draftKey.value))
  if (!draft) {
    window.localStorage.removeItem(draftKey.value)
    return
  }
  const serverUpdatedAt = data.updatedAt ? Date.parse(data.updatedAt) : 0
  const draftSavedAt = Date.parse(draft.savedAt)
  if (serverUpdatedAt && (!Number.isFinite(draftSavedAt) || draftSavedAt < serverUpdatedAt)) {
    window.localStorage.removeItem(draftKey.value)
    return
  }
  Object.assign(form, {
    deepCommunicationCount: draft.deepCommunicationCount,
    todayIssues: draft.todayIssues,
    tomorrowPlan: draft.tomorrowPlan,
  })
}

async function redirectToLogin() {
  auth.$patch({ user: null, checked: true })
  await router.replace({ path: '/login', query: { redirect: route.fullPath } })
}

async function handleError(reason: unknown, fallback: string) {
  if (reason instanceof ApiError && reason.status === 401) {
    await redirectToLogin()
    return
  }
  error.value = reason instanceof ApiError ? reason.message : fallback
}

async function load() {
  loading.value = true
  error.value = ''
  draftReady.value = false
  try {
    const data = await api<DailyReport>('/api/v1/daily-reports/today')
    report.value = data
    Object.assign(form, {
      deepCommunicationCount: data.deepCommunicationCount,
      todayIssues: data.todayIssues,
      tomorrowPlan: data.tomorrowPlan,
    })
    restoreDraft(data)
    await nextTick()
    draftReady.value = true
  } catch (reason) {
    await handleError(reason, '每日小结暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function validate() {
  if (!report.value?.performanceManaged
    && (!Number.isInteger(form.deepCommunicationCount) || form.deepCommunicationCount < 0)) {
    return '深度沟通人数必须是大于或等于 0 的整数。'
  }
  if (!form.todayIssues.trim()) return '请填写今日问题；如无问题，可填写“暂无”。'
  if (!form.tomorrowPlan.trim()) return '请填写明日计划。'
  if (form.todayIssues.length > 5000 || form.tomorrowPlan.length > 5000) return '单项内容不能超过 5000 字。'
  return ''
}

async function submit() {
  if (!report.value || busy.value) return
  error.value = validate()
  if (error.value) return
  busy.value = true
  try {
    const saved = await api<DailyReport>(`/api/v1/daily-reports/${report.value.reportDate}`, {
      method: 'PUT',
      body: jsonBody({
        deepCommunicationCount: form.deepCommunicationCount,
        todayIssues: form.todayIssues.trim(),
        tomorrowPlan: form.tomorrowPlan.trim(),
      }),
    })
    if (draftKey.value) window.localStorage.removeItem(draftKey.value)
    report.value = saved
    Object.assign(form, {
      deepCommunicationCount: saved.deepCommunicationCount,
      todayIssues: saved.todayIssues,
      tomorrowPlan: saved.tomorrowPlan,
    })
    completed.value = true
    error.value = ''
  } catch (reason) {
    await handleError(reason, '每日小结提交失败，请检查网络后重试。')
  } finally {
    busy.value = false
  }
}

function editAgain() {
  completed.value = false
  draftReady.value = true
}

watch(form, (value) => {
  if (!draftReady.value || completed.value || !draftKey.value) return
  const draft: StoredDraft = {
    savedAt: new Date().toISOString(),
    deepCommunicationCount: value.deepCommunicationCount,
    todayIssues: value.todayIssues,
    tomorrowPlan: value.tomorrowPlan,
  }
  window.localStorage.setItem(draftKey.value, JSON.stringify(draft))
}, { deep: true })

onMounted(() => {
  void load()
  window.addEventListener('resize', scheduleKeyboardUpdate)
  window.visualViewport?.addEventListener('resize', scheduleKeyboardUpdate)
  document.addEventListener('focusin', scheduleKeyboardUpdate)
  document.addEventListener('focusout', scheduleKeyboardUpdate)
})

onBeforeUnmount(() => {
  if (keyboardTimer !== undefined) window.clearTimeout(keyboardTimer)
  window.removeEventListener('resize', scheduleKeyboardUpdate)
  window.visualViewport?.removeEventListener('resize', scheduleKeyboardUpdate)
  document.removeEventListener('focusin', scheduleKeyboardUpdate)
  document.removeEventListener('focusout', scheduleKeyboardUpdate)
})
</script>

<template>
  <main :class="['daily-summary-page', { 'is-keyboard-open': keyboardOpen }]">
    <section class="daily-summary-shell" aria-labelledby="daily-summary-title">
      <header class="summary-header">
        <RouterLink class="summary-brand" to="/dashboard" aria-label="返回英格客户云工作台">
          <span>Y</span>
          <div><strong>英格客户云</strong><small>移动端每日填写</small></div>
        </RouterLink>
        <RouterLink class="workspace-link" to="/dashboard">返回工作台</RouterLink>
      </header>

      <section v-if="loading" class="summary-state" aria-live="polite">
        <span class="loading-mark"></span>
        <h1>正在准备今日小结</h1>
        <p>正在读取你的审核统计与已填写内容。</p>
      </section>

      <section v-else-if="completed" class="summary-state summary-complete" aria-live="polite">
        <span class="complete-mark">✓</span>
        <p class="summary-kicker">SUBMITTED</p>
        <h1 id="daily-summary-title">今日小结已保存</h1>
        <p>{{ memberName }}，{{ formattedDate }}的内容已经写入每日汇报。</p>
        <div class="complete-actions">
          <button class="secondary-action" type="button" @click="editAgain">修改今日小结</button>
          <RouterLink class="primary-action" to="/dashboard">返回工作台</RouterLink>
        </div>
      </section>

      <template v-else>
        <section class="summary-intro">
          <p class="summary-kicker">DAILY SUMMARY</p>
          <h1 id="daily-summary-title">每日小结</h1>
          <p>用几分钟梳理今天的问题与明天最重要的行动。</p>
          <dl v-if="report" class="summary-identity">
            <div><dt>填写人</dt><dd>{{ memberName }}</dd></div>
            <div><dt>日期</dt><dd>{{ formattedDate }}</dd></div>
            <div><dt>状态</dt><dd :class="{ submitted: report.submitted }">{{ report.submitted ? '已提交，可继续修改' : '等待提交' }}</dd></div>
          </dl>
        </section>

        <p v-if="error" class="summary-error" role="alert">{{ error }}</p>
        <button v-if="!report && !loading" class="retry-button" type="button" @click="load">重新加载</button>

        <form v-if="report" class="summary-form" @submit.prevent="submit">
          <section class="question-card automatic-question">
            <header><span>01</span><div><h2>26 级新增有效线索</h2><p>系统根据今天审核通过的线索自动统计。</p></div></header>
            <div class="automatic-value"><strong>{{ report.grade26NewValidLeads }}</strong><span>人</span></div>
          </section>

          <section class="question-card">
            <header><span>02</span><div><h2>深度沟通人数</h2><p>{{ report.performanceManaged ? '由绩效审核结果自动生成。' : '填写今天完成深度沟通的人数。' }}</p></div></header>
            <div v-if="report.performanceManaged" class="automatic-value performance-value">
              <strong>{{ report.deepCommunicationCount }}</strong>
              <span>已通过</span>
              <small>{{ report.pendingDeepCommunicationCount ?? 0 }} 条待审核</small>
            </div>
            <label v-else class="number-field">
              <span>人数</span>
              <input v-model.number="form.deepCommunicationCount" name="deepCommunicationCount" type="number" inputmode="numeric" min="0" step="1" required>
            </label>
          </section>

          <section class="question-card">
            <header><span>03</span><div><h2>今日问题</h2><p>记录遇到的阻碍、风险或需要协助的事项。</p></div></header>
            <label class="text-field">
              <span class="sr-only">今日问题</span>
              <textarea v-model="form.todayIssues" name="todayIssues" rows="6" maxlength="5000" required placeholder="例如：客户反馈、执行阻碍、需要团队协助的事项；如无问题可填写“暂无”"></textarea>
              <small>{{ form.todayIssues.length }}/5000</small>
            </label>
          </section>

          <section class="question-card">
            <header><span>04</span><div><h2>明日计划</h2><p>写清明天最重要、可以实际执行的行动。</p></div></header>
            <label class="text-field">
              <span class="sr-only">明日计划</span>
              <textarea v-model="form.tomorrowPlan" name="tomorrowPlan" rows="6" maxlength="5000" required placeholder="例如：跟进哪些客户、完成什么任务、预计达成什么结果"></textarea>
              <small>{{ form.tomorrowPlan.length }}/5000</small>
            </label>
          </section>

          <footer class="summary-submit-dock">
            <div><strong>{{ report.submitted ? '正在修改今日记录' : '内容仅提交到你的每日汇报' }}</strong><small>未提交内容会临时保存在当前浏览器</small></div>
            <button type="submit" :disabled="busy">{{ busy ? '正在保存…' : report.submitted ? '保存修改' : '提交小结' }}</button>
          </footer>
        </form>
      </template>
    </section>
    <MobileBottomNav
      :items="mobileBottomItems"
      :more-active="mobileMoreActive"
      :hidden="keyboardOpen"
      @more="mobileMoreOpen = true"
    />
    <MobileMoreSheet
      :open="mobileMoreOpen"
      :groups="mobileMoreGroups"
      :common-items="mobileCommonItems"
      :current-path="route.path"
      :display-name="auth.user?.displayName"
      :roles="roles"
      @close="mobileMoreOpen = false"
      @select="selectMobileNavigation"
      @logout="logout"
    />
  </main>
</template>

<style scoped>
.daily-summary-page{min-height:100vh;min-height:100dvh;padding:max(14px,env(safe-area-inset-top)) max(14px,env(safe-area-inset-right)) max(18px,env(safe-area-inset-bottom)) max(14px,env(safe-area-inset-left));overflow-x:hidden;background:radial-gradient(circle at 18% -5%,#dce7ff 0,transparent 35%),linear-gradient(160deg,#f7f9fd 0%,#edf2fa 100%)}
.daily-summary-shell{width:min(100%,520px);min-height:calc(100vh - 32px);min-height:calc(100dvh - 32px);margin:0 auto;border:1px solid rgba(201,211,232,.78);border-radius:28px;background:rgba(255,255,255,.94);box-shadow:0 22px 70px rgba(31,48,88,.13)}
.summary-header{display:flex;min-height:72px;align-items:center;justify-content:space-between;gap:12px;padding:14px 18px;border-bottom:1px solid #edf0f6;background:rgba(255,255,255,.86)}
.summary-brand{display:flex;min-width:0;align-items:center;gap:10px}.summary-brand>span{display:grid;width:38px;height:38px;flex:0 0 auto;place-items:center;border-radius:13px;color:#fff;background:linear-gradient(145deg,#6989ff,#3455cd);font-size:20px;font-weight:750;box-shadow:0 9px 22px rgba(52,85,205,.28)}
.summary-brand strong,.summary-brand small{display:block}.summary-brand strong{color:#1c2944;font-size:14px}.summary-brand small{margin-top:3px;color:#8490a5;font-size:10px}.workspace-link{padding:10px 4px;color:#3157d5;font-size:12px;font-weight:650;white-space:nowrap}
.summary-intro{padding:32px 22px 24px}.summary-kicker{color:#4568de;font-size:10px;font-weight:800;letter-spacing:.2em}.summary-intro h1,.summary-state h1{margin:8px 0 10px;color:#14203a;font-size:34px;line-height:1.08;letter-spacing:-.04em}.summary-intro>p:last-of-type,.summary-state>p{color:#69768e;font-size:14px;line-height:1.7}
.summary-identity{display:grid;grid-template-columns:1fr 1.45fr;gap:12px;margin:24px 0 0}.summary-identity div{min-width:0;padding:13px 14px;border:1px solid #e7ebf3;border-radius:15px;background:#f8faff}.summary-identity div:last-child{grid-column:1/-1}.summary-identity dt{margin-bottom:5px;color:#8994a8;font-size:10px}.summary-identity dd{margin:0;overflow-wrap:anywhere;color:#27344e;font-size:12px;font-weight:700}.summary-identity dd.submitted{color:#087b5b}
.summary-form{display:grid;gap:14px;padding:0 14px calc(106px + var(--app-mobile-bottom-clearance,0px))}.question-card{display:grid;gap:20px;padding:20px;border:1px solid #e4e9f3;border-radius:21px;background:#fff;box-shadow:0 10px 30px rgba(35,52,91,.055)}.question-card>header{display:grid;grid-template-columns:36px minmax(0,1fr);gap:12px}.question-card>header>span{display:grid;width:36px;height:36px;place-items:center;border-radius:12px;color:#3157d5;background:#edf2ff;font-size:11px;font-weight:800}.question-card h2{margin:0 0 5px;color:#1c2942;font-size:18px;line-height:1.25}.question-card header p{color:#7b879b;font-size:12px;line-height:1.55}
.automatic-question{background:linear-gradient(145deg,#fff,#f7f9ff)}.automatic-value{display:flex;min-height:72px;align-items:baseline;gap:7px;padding:17px 18px;border:1px solid #d6e0ff;border-radius:16px;color:#3157d5;background:#f2f5ff}.automatic-value strong{font-size:34px;line-height:1}.automatic-value span{font-size:12px;font-weight:650}.automatic-value small{margin-left:auto;color:#7582a0;font-size:11px}.performance-value{flex-wrap:wrap}
.number-field,.text-field{display:grid;gap:8px}.number-field>span{color:#5b6880;font-size:12px;font-weight:650}.number-field input,.text-field textarea{width:100%;min-height:52px;padding:14px 15px;border:1px solid #dce2ee;border-radius:14px;outline:none;color:#1d2940;background:#fbfcff;font-size:16px;line-height:1.55;transition:border-color .18s,box-shadow .18s}.text-field textarea{min-height:150px;resize:vertical}.number-field input:focus,.text-field textarea:focus{border-color:#6682e6;box-shadow:0 0 0 4px rgba(76,105,220,.11);background:#fff}.text-field small{justify-self:end;color:#929bad;font-size:10px}
.summary-error{margin:0 14px 14px;padding:13px 15px;overflow-wrap:anywhere;border:1px solid #f1c7cc;border-radius:14px;color:#a63445;background:#fff4f5;font-size:12px;line-height:1.6}.retry-button{display:block;min-height:46px;margin:0 14px 24px;padding:0 18px;border-radius:12px;color:#fff;background:#3157d5;font-weight:700}
.summary-submit-dock{position:fixed;z-index:5;right:auto;bottom:var(--app-mobile-bottom-clearance,0px);left:50%;display:grid;width:min(520px,calc(100% - 28px));grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:13px;margin:0;padding:14px max(16px,env(safe-area-inset-right)) max(14px,env(safe-area-inset-bottom)) max(16px,env(safe-area-inset-left));transform:translateX(-50%);border:1px solid rgba(221,227,239,.9);border-bottom:0;border-radius:18px 18px 0 0;background:rgba(255,255,255,.94);box-shadow:0 -14px 36px rgba(26,41,76,.09);backdrop-filter:blur(18px)}.summary-submit-dock strong,.summary-submit-dock small{display:block}.summary-submit-dock strong{color:#344159;font-size:10px}.summary-submit-dock small{margin-top:4px;color:#8b95a7;font-size:9px;line-height:1.35}.summary-submit-dock button,.primary-action,.secondary-action{display:inline-flex;min-height:48px;align-items:center;justify-content:center;padding:0 20px;border-radius:14px;font-size:13px;font-weight:750}.summary-submit-dock button,.primary-action{color:#fff;background:linear-gradient(135deg,#5575eb,#2e50cb);box-shadow:0 10px 24px rgba(49,87,213,.24)}.summary-submit-dock button:disabled{opacity:.62}
.summary-state{display:flex;min-height:calc(100dvh - 104px);flex-direction:column;align-items:center;justify-content:center;padding:40px 26px;text-align:center}.loading-mark{width:42px;height:42px;margin-bottom:20px;border:3px solid #dbe3f8;border-top-color:#4567db;border-radius:50%;animation:summary-spin .8s linear infinite}.complete-mark{display:grid;width:70px;height:70px;margin-bottom:22px;place-items:center;border-radius:24px;color:#fff;background:linear-gradient(145deg,#35ae83,#128160);box-shadow:0 16px 34px rgba(18,129,96,.24);font-size:34px}.summary-complete h1{font-size:30px}.complete-actions{display:grid;width:100%;grid-template-columns:1fr 1fr;gap:10px;margin-top:30px}.secondary-action{color:#36435d;border:1px solid #dce2ed;background:#fff}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@keyframes summary-spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.daily-summary-page{--app-mobile-bottom-clearance:calc(var(--mobile-tabbar-height,64px) + env(safe-area-inset-bottom))}.daily-summary-page.is-keyboard-open{--app-mobile-bottom-clearance:0px}}
@media(max-width:480px){.daily-summary-page{padding:0 0 var(--app-mobile-bottom-clearance)}.daily-summary-shell{min-height:100vh;min-height:100dvh;border:0;border-radius:0;box-shadow:none}.summary-header{padding-top:max(14px,env(safe-area-inset-top))}.summary-intro{padding:28px 18px 22px}.question-card{padding:18px}.summary-form{padding-bottom:calc(132px + var(--app-mobile-bottom-clearance,0px))}.summary-submit-dock{left:0;width:100%;grid-template-columns:1fr;transform:none}.summary-submit-dock button{width:100%}.complete-actions{grid-template-columns:1fr}}
@media(max-width:370px){.summary-header{padding-right:14px;padding-left:14px}.summary-brand small{display:none}.summary-intro{padding-right:14px;padding-left:14px}.summary-form{padding-right:10px;padding-left:10px}.question-card{padding:16px}.summary-identity{grid-template-columns:1fr}.summary-identity div:last-child{grid-column:auto}}
@media(prefers-reduced-motion:reduce){.loading-mark{animation-duration:1.8s}.daily-summary-page *{scroll-behavior:auto!important;transition-duration:.01ms!important}}
</style>
