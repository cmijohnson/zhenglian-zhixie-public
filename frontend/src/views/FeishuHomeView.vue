<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError, api } from '../lib/api'
import { useAuthStore } from '../stores/auth'

interface Bootstrap {
  bound: boolean
  feishuDisplayName?: string
  memberDisplayName: string
  writable: boolean
  customerTableUrl?: string
}

const auth = useAuthStore()
const bootstrap = ref<Bootstrap>()
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    bootstrap.value = await api<Bootstrap>('/api/v1/feishu/workbench/bootstrap')
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.body.message : '飞书身份读取失败，请重新授权'
  } finally {
    loading.value = false
  }
})

function bindFeishu() {
  window.location.assign('/api/v1/auth/feishu/start?purpose=BIND&returnTo=%2Ffeishu')
}

async function logout() {
  await auth.logout()
  window.location.assign('/login')
}
</script>

<template>
  <main class="feishu-launcher">
    <section class="launcher-shell">
      <header class="launcher-header">
        <div class="brand-mark">Y</div>
        <div>
          <p class="eyebrow">YINGGE × FEISHU</p>
          <h1>英格云飞书工作台</h1>
          <p>完整 CRM 与快捷成单各自独立，按场景进入。</p>
        </div>
      </header>

      <p v-if="loading" class="state-card">正在确认飞书账号…</p>
      <section v-else-if="error" class="state-card error-state">
        <strong>尚未完成飞书账号绑定</strong>
        <p>{{ error }}</p>
        <button class="primary-action" @click="bindFeishu">绑定当前飞书账号</button>
      </section>

      <template v-else>
        <section class="identity-card">
          <span class="feishu-mark">飞</span>
          <div>
            <strong>{{ bootstrap?.feishuDisplayName || '飞书用户' }}</strong>
            <p>已绑定英格云成员：{{ bootstrap?.memberDisplayName }}</p>
          </div>
          <span :class="['permission-pill', { readonly: !bootstrap?.writable }]">
            {{ bootstrap?.writable ? '可操作' : '只读' }}
          </span>
        </section>

        <div class="entry-grid">
          <RouterLink class="entry-card crm-card" to="/feishu/crm?returnTo=/dashboard">
            <span class="entry-icon">云</span>
            <p class="eyebrow">FULL CRM</p>
            <h2>进入完整 CRM</h2>
            <p>客户、时间线、提醒、任务、日报、公共池及其他现有员工功能。</p>
            <strong>打开完整系统 →</strong>
          </RouterLink>

          <RouterLink v-if="bootstrap?.writable" class="entry-card deal-card" to="/feishu/deals">
            <span class="entry-icon">单</span>
            <p class="eyebrow">QUICK DEAL</p>
            <h2>快捷成单</h2>
            <p>搜索或建档、调整负责人、填写成单人员、订单金额与奖金，一页确认。</p>
            <strong>开始快捷成单 →</strong>
          </RouterLink>
          <section v-else class="entry-card disabled-card">
            <span class="entry-icon">锁</span>
            <p class="eyebrow">QUICK DEAL</p>
            <h2>快捷成单</h2>
            <p>当前英格云账号为只读角色，不能发起成单。</p>
          </section>
        </div>

        <div class="secondary-actions">
          <a v-if="bootstrap?.customerTableUrl" :href="bootstrap.customerTableUrl" target="_blank" rel="noopener noreferrer">打开飞书客户表</a>
          <RouterLink to="/settings">账号设置</RouterLink>
          <button @click="logout">退出英格云</button>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.feishu-launcher{min-height:100dvh;padding:clamp(20px,5vw,64px);background:radial-gradient(circle at 85% 8%,#d9f4ff 0,transparent 34%),radial-gradient(circle at 5% 90%,#e6ebff 0,transparent 38%),#f5f7fb;color:#14213a}.launcher-shell{width:min(980px,100%);margin:auto}.launcher-header{display:flex;align-items:center;gap:18px;margin-bottom:28px}.brand-mark{display:grid;place-items:center;width:64px;height:64px;border-radius:21px;color:#fff;font-size:28px;font-weight:800;background:linear-gradient(145deg,#5279ff,#22b4ca);box-shadow:0 18px 40px #3157bb35}.launcher-header h1{margin:4px 0 8px;font-size:clamp(30px,5vw,52px);letter-spacing:-.04em}.launcher-header p{margin:0;color:#65718b}.eyebrow{margin:0;color:#4b70e9!important;font-size:12px;font-weight:800;letter-spacing:.18em}.identity-card,.state-card{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:14px;padding:18px 20px;margin-bottom:18px;border:1px solid #dce3f2;border-radius:20px;background:#ffffffd9;box-shadow:0 14px 36px #40527f10}.identity-card p,.state-card p{margin:4px 0 0;color:#69758c}.feishu-mark{display:grid;place-items:center;width:44px;height:44px;border-radius:14px;color:#fff;font-weight:800;background:linear-gradient(145deg,#1684fc,#00b899)}.permission-pill{padding:7px 12px;border-radius:999px;color:#087d62;background:#e5faf4;font-size:13px;font-weight:700}.permission-pill.readonly{color:#8b641d;background:#fff3d5}.entry-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.entry-card{position:relative;display:grid;align-content:start;min-height:300px;padding:30px;border:1px solid #dbe3f2;border-radius:28px;color:inherit;text-decoration:none;overflow:hidden;box-shadow:0 24px 60px #37517b12;transition:transform .18s ease,box-shadow .18s ease}.entry-card:hover{transform:translateY(-3px);box-shadow:0 28px 68px #37517b20}.crm-card{background:linear-gradient(145deg,#fff,#eef4ff)}.deal-card{background:linear-gradient(145deg,#10224d,#254db8);color:#fff;border-color:#284ca8}.deal-card .eyebrow{color:#a9c3ff!important}.entry-icon{display:grid;place-items:center;width:54px;height:54px;margin-bottom:36px;border-radius:18px;background:#e5ecff;color:#315bd7;font-weight:850;font-size:21px}.deal-card .entry-icon{background:#ffffff1c;color:#fff}.entry-card h2{font-size:28px;margin:8px 0 12px}.entry-card>p:not(.eyebrow){min-height:70px;margin:0 0 28px;color:#69758c;line-height:1.7}.deal-card>p:not(.eyebrow){color:#c9d6f9}.entry-card>strong{margin-top:auto}.disabled-card{background:#f3f4f7;color:#828ba0}.secondary-actions{display:flex;justify-content:flex-end;gap:18px;flex-wrap:wrap;margin-top:20px}.secondary-actions a,.secondary-actions button{border:0;background:none;color:#55627a;font:inherit;text-decoration:none;cursor:pointer}.error-state{grid-template-columns:1fr}.primary-action{justify-self:start;min-height:44px;padding:0 18px;border:0;border-radius:12px;color:#fff;background:#315bd7;font-weight:750}@media(max-width:680px){.feishu-launcher{padding:calc(18px + env(safe-area-inset-top)) 16px calc(28px + env(safe-area-inset-bottom))}.launcher-header{align-items:flex-start}.brand-mark{width:52px;height:52px;border-radius:17px}.entry-grid{grid-template-columns:1fr}.entry-card{min-height:250px;padding:24px}.entry-icon{margin-bottom:24px}.identity-card{grid-template-columns:auto 1fr}.permission-pill{grid-column:2;justify-self:start}.secondary-actions{justify-content:flex-start}.entry-card h2{font-size:25px}}@media(prefers-reduced-motion:reduce){.entry-card{transition:none}}
</style>
