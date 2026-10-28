<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import DailyExecutionNav from '../components/DailyExecutionNav.vue'
import { ApiError, api, jsonBody } from '../lib/api'

type Platform = 'XIAOHONGSHU' | 'DOUYIN'
type ReviewStatus = 'SUBMITTED' | 'VALID' | 'INVALID'
interface Direction { id: string; title: string; description?: string; active: boolean }
interface ContentPost {
  id: string; membershipId: string; memberDisplayName: string; platform: Platform; publishDate: string
  directionId: string; directionTitle: string; topic: string; contentUrl: string
  viewCount: number; likeCount: number; favoriteCount: number; reviewStatus: ReviewStatus; reviewReason?: string
}
interface MemberProgress { membershipId: string; displayName: string; xiaohongshuValid: number; douyinValid: number }
interface Overview {
  campaign: { name: string; startDate: string; endDate: string; xiaohongshuTarget: number; douyinTarget: number; today: string; active: boolean }
  directions: Direction[]
  myProgress: { xiaohongshuValid: number; douyinValid: number }
  myPosts: ContentPost[]
  reviewQueue: ContentPost[]
  memberProgress: MemberProgress[]
  administrator: boolean
  reviewer: boolean
}

const overview = ref<Overview>()
const error = ref('')
const success = ref('')
const submitting = ref(false)
const form = reactive({ platform: 'XIAOHONGSHU' as Platform, publishDate: '', directionId: '', topic: '', contentUrl: '', viewCount: 0, likeCount: 0, favoriteCount: 0, originalDeclared: false })
const directionForm = reactive({ title: '', description: '' })
const metricEdits = reactive<Record<string, { viewCount: number; likeCount: number; favoriteCount: number }>>({})
const reviewReasons = reactive<Record<string, string>>({})

const platformName = (value: Platform) => value === 'XIAOHONGSHU' ? '小红书' : '抖音'
const reviewName = (value: ReviewStatus) => ({ SUBMITTED: '待审核', VALID: '有效', INVALID: '不计入' }[value])

function extractContentUrl(value: string) {
  const matched = value.match(/https?:\/\/\S+/i)?.[0]
  return (matched ?? value.trim()).replace(/[,，。；;！!）)】\]]+$/, '')
}

function normalizeContentUrl() {
  form.contentUrl = extractContentUrl(form.contentUrl)
}

async function load() {
  overview.value = await api<Overview>('/api/v1/content-tasks/overview')
  if (!form.publishDate) form.publishDate = overview.value.campaign.today
  if (!form.directionId) form.directionId = overview.value.directions.find((item) => item.active)?.id ?? ''
  for (const post of overview.value.myPosts) {
    metricEdits[post.id] = { viewCount: post.viewCount, likeCount: post.likeCount, favoriteCount: post.favoriteCount }
  }
}

async function submitPost() {
  if (submitting.value) return
  error.value = ''; success.value = ''
  const contentUrl = extractContentUrl(form.contentUrl)
  if (!/^https?:\/\/\S+$/i.test(contentUrl)) {
    error.value = '请粘贴有效链接或包含链接的分享文本'
    return
  }
  form.contentUrl = contentUrl
  submitting.value = true
  try {
    await api('/api/v1/content-posts', { method: 'POST', body: jsonBody({ ...form, contentUrl }) })
    success.value = '内容已提交审核并公开到圈子。'
    Object.assign(form, { topic: '', contentUrl: '', viewCount: 0, likeCount: 0, favoriteCount: 0, originalDeclared: false })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '内容提交失败' }
  finally { submitting.value = false }
}

async function updateMetrics(post: ContentPost) {
  error.value = ''; success.value = ''
  try {
    await api(`/api/v1/content-posts/${post.id}/metrics`, { method: 'PATCH', body: jsonBody(metricEdits[post.id]) })
    success.value = '播放与互动数据已更新。'
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '数据更新失败' }
}

async function createDirection() {
  error.value = ''
  try {
    await api('/api/v1/content-directions', { method: 'POST', body: jsonBody(directionForm) })
    Object.assign(directionForm, { title: '', description: '' })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '内容方向发布失败' }
}

async function toggleDirection(direction: Direction) {
  await api(`/api/v1/content-directions/${direction.id}`, {
    method: 'PATCH',
    body: jsonBody({ title: direction.title, description: direction.description, active: !direction.active }),
  })
  await load()
}

async function review(post: ContentPost, status: 'VALID' | 'INVALID') {
  error.value = ''
  try {
    await api(`/api/v1/content-posts/${post.id}/review`, {
      method: 'PATCH', body: jsonBody({ status, reason: status === 'INVALID' ? reviewReasons[post.id] : undefined }),
    })
    await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '审核失败' }
}

onMounted(() => load().catch(() => { error.value = '内容任务暂时无法加载' }))
</script>

<template>
  <div class="page content-page">
    <header class="page-header">
      <div><p class="eyebrow">CONTENT CAMPAIGN</p><h1>自媒体内容任务</h1><p class="muted">每天每个平台最多计入一条，由团队长或管理员审核为有效后进入目标统计。</p></div>
    </header>
    <DailyExecutionNav />
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>
    <template v-if="overview">
      <section class="campaign-banner panel">
        <div><span class="badge">{{ overview.campaign.active ? '任务进行中' : '当前非任务周期' }}</span><h2>{{ overview.campaign.name }}</h2><p>{{ overview.campaign.startDate }} — {{ overview.campaign.endDate }}</p></div>
        <div class="campaign-target"><span>小红书</span><strong>{{ overview.myProgress.xiaohongshuValid }}/{{ overview.campaign.xiaohongshuTarget }}</strong></div>
        <div class="campaign-target"><span>抖音</span><strong>{{ overview.myProgress.douyinValid }}/{{ overview.campaign.douyinTarget }}</strong></div>
      </section>

      <div class="work-grid">
        <form class="panel form-grid" @submit.prevent="submitPost">
          <div class="panel-title"><div><p class="eyebrow">SUBMIT</p><h2>上传今日内容</h2></div></div>
          <div class="form-grid two"><label>平台<select v-model="form.platform"><option value="XIAOHONGSHU">小红书</option><option value="DOUYIN">抖音</option></select></label><label>发布日期<input v-model="form.publishDate" type="date" :min="overview.campaign.startDate" :max="overview.campaign.today" required></label></div>
          <label>内容方向<select v-model="form.directionId" required><option value="" disabled>请选择管理员发布的方向</option><option v-for="direction in overview.directions.filter((item) => item.active)" :key="direction.id" :value="direction.id">{{ direction.title }}</option></select></label>
          <label>内容主题<input v-model.trim="form.topic" maxlength="240" placeholder="简明填写本条内容的主题" required></label>
          <label>公开链接<input v-model="form.contentUrl" data-testid="content-url" type="text" inputmode="url" placeholder="粘贴链接或抖音/小红书分享文本" required @blur="normalizeContentUrl"><small class="field-hint">可直接粘贴整段分享文案，系统会自动提取其中第一个 HTTP/HTTPS 链接。</small></label>
          <div class="form-grid three"><label>播放量<input v-model.number="form.viewCount" type="number" min="0"></label><label>点赞量<input v-model.number="form.likeCount" type="number" min="0"></label><label>收藏量<input v-model.number="form.favoriteCount" type="number" min="0"></label></div>
          <label class="check-label"><input v-model="form.originalDeclared" type="checkbox" required>我确认该内容非搬运、具有明确主题并符合基本质量要求</label>
          <button class="primary-button" data-testid="content-submit" :disabled="submitting">{{ submitting ? '正在提交…' : '提交并公开到圈子' }}</button>
        </form>

        <section class="panel">
          <div class="panel-title"><div><p class="eyebrow">DIRECTIONS</p><h2>本期内容方向</h2></div></div>
          <article v-for="direction in overview.directions.filter((item) => item.active)" :key="direction.id" class="direction-card"><strong>{{ direction.title }}</strong><p>{{ direction.description || '管理员暂未补充详细说明' }}</p></article>
          <div v-if="!overview.directions.some((item) => item.active)" class="empty-state small">管理员尚未发布内容方向。</div>
        </section>
      </div>

      <section class="panel content-history">
        <div class="panel-title"><div><p class="eyebrow">MY POSTS</p><h2>我的发布记录</h2></div></div>
        <article v-for="post in overview.myPosts" :key="post.id" class="content-row">
          <div><span :class="['badge', post.reviewStatus === 'VALID' ? 'badge-green' : post.reviewStatus === 'INVALID' ? 'badge-red' : 'badge-amber']">{{ reviewName(post.reviewStatus) }}</span><strong>{{ platformName(post.platform) }} · {{ post.topic }}</strong><small>{{ post.publishDate }} · {{ post.directionTitle }}</small><p v-if="post.reviewReason" class="form-error">{{ post.reviewReason }}</p></div>
          <div class="metric-edit"><label>播放<input v-model.number="metricEdits[post.id].viewCount" type="number" min="0"></label><label>点赞<input v-model.number="metricEdits[post.id].likeCount" type="number" min="0"></label><label>收藏<input v-model.number="metricEdits[post.id].favoriteCount" type="number" min="0"></label><button class="soft-button" @click="updateMetrics(post)">更新数据</button></div>
          <a :href="post.contentUrl" target="_blank" rel="noopener noreferrer" class="text-button">查看内容 ↗</a>
        </article>
        <div v-if="!overview.myPosts.length" class="empty-state">还没有提交内容。</div>
      </section>

      <template v-if="overview.reviewer">
        <div class="admin-two-column content-admin">
          <section v-if="overview.administrator" class="panel"><div class="panel-title"><div><p class="eyebrow">ADMIN</p><h2>发布内容方向</h2></div></div><form class="form-grid" @submit.prevent="createDirection"><label>方向标题<input v-model.trim="directionForm.title" required maxlength="160"></label><label>方向说明<textarea v-model.trim="directionForm.description" rows="4" maxlength="4000"></textarea></label><button class="primary-button">发布方向</button></form><div class="direction-admin-list"><button v-for="direction in overview.directions" :key="direction.id" class="ghost-button" @click="toggleDirection(direction)">{{ direction.title }} · {{ direction.active ? '停用' : '启用' }}</button></div></section>
          <section class="panel"><div class="panel-title"><div><p class="eyebrow">PROGRESS</p><h2>{{ overview.administrator ? '成员有效进度' : '本团队有效进度' }}</h2></div></div><div class="table-wrap"><table><thead><tr><th>成员</th><th>小红书</th><th>抖音</th></tr></thead><tbody><tr v-for="member in overview.memberProgress" :key="member.membershipId"><td>{{ member.displayName }}</td><td>{{ member.xiaohongshuValid }}/{{ overview.campaign.xiaohongshuTarget }}</td><td>{{ member.douyinValid }}/{{ overview.campaign.douyinTarget }}</td></tr></tbody></table></div><div v-if="!overview.memberProgress.length" class="empty-state small">当前范围没有成员。</div></section>
        </div>
        <section class="panel review-panel"><div class="panel-title"><div><p class="eyebrow">REVIEW</p><h2>待审核内容</h2></div><span>{{ overview.reviewQueue.length }} 条</span></div><article v-for="post in overview.reviewQueue" :key="post.id" class="review-row"><div><strong>{{ post.memberDisplayName }} · {{ platformName(post.platform) }} · {{ post.topic }}</strong><small>{{ post.publishDate }} · {{ post.directionTitle }} · 播放 {{ post.viewCount }} / 赞 {{ post.likeCount }} / 藏 {{ post.favoriteCount }}</small><a :href="post.contentUrl" target="_blank" rel="noopener noreferrer">打开内容 ↗</a></div><input v-model.trim="reviewReasons[post.id]" placeholder="判定不计入时填写原因"><button class="soft-button" @click="review(post, 'VALID')">计为有效</button><button class="ghost-button" @click="review(post, 'INVALID')">不计入</button></article><div v-if="!overview.reviewQueue.length" class="empty-state small">没有待审核内容。</div></section>
      </template>
    </template>
    <div v-else-if="!error" class="loading-state">正在加载内容任务…</div>
  </div>
</template>

<style scoped>
@media(max-width:700px){
  .campaign-banner{display:grid;grid-template-columns:1fr 1fr;gap:12px}.campaign-banner>div:first-child{grid-column:1/-1}.campaign-target{min-width:0;padding:14px}
  .work-grid,.admin-two-column{grid-template-columns:1fr}
  .form-grid.two,.form-grid.three{grid-template-columns:1fr}
  .content-row{display:grid;grid-template-columns:1fr;align-items:stretch;gap:14px;padding:18px 0}
  .content-row>div:first-child{min-width:0}.content-row>div:first-child strong,.content-row>div:first-child p{overflow-wrap:anywhere}
  .metric-edit{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.metric-edit label{min-width:0}.metric-edit input{min-width:0}.metric-edit button{grid-column:1/-1;min-height:44px}
  .content-row>.text-button{justify-self:start;min-height:44px}
  .review-row{display:grid;grid-template-columns:1fr;gap:10px}.review-row input,.review-row button{width:100%;min-height:44px}
  .direction-admin-list{display:grid;grid-template-columns:1fr}.direction-admin-list button{min-height:44px}
  .content-admin .table-wrap{overflow-x:auto;overscroll-behavior-inline:contain}.content-admin table{min-width:420px}
}
</style>
