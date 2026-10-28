<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import { useAuthStore } from '../stores/auth'

type Priority = 'NORMAL' | 'IMPORTANT' | 'URGENT'
interface Announcement { id: string; authorMembershipId: string; authorDisplayName: string; title: string; content: string; priority: Priority; pinned: boolean; showOnLeadershipScreen: boolean; status: 'PUBLISHED' | 'ARCHIVED'; publishedAt: string }
const auth = useAuthStore()
const announcements = ref<Announcement[]>([])
const includeArchived = ref(false)
const editingId = ref('')
const error = ref('')
const success = ref('')
const expanded = ref<Set<string>>(new Set())
const form = reactive({ title: '', content: '', priority: 'NORMAL' as Priority, pinned: false, showOnLeadershipScreen: false })
const canPublish = computed(() => auth.canPublishAnnouncements)
const canManage = (item: Announcement) => auth.isAdmin || item.authorMembershipId === auth.user?.membershipId
const priorityName = (value: Priority) => ({ NORMAL: '普通', IMPORTANT: '重要', URGENT: '紧急' }[value])
function toggleContent(id: string) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

async function load() {
  announcements.value = await api<Announcement[]>(`/api/v1/announcements?includeArchived=${canPublish.value && includeArchived.value}`)
}
function resetForm() { editingId.value = ''; Object.assign(form, { title: '', content: '', priority: 'NORMAL', pinned: false, showOnLeadershipScreen: false }) }
function edit(item: Announcement) { editingId.value = item.id; Object.assign(form, { title: item.title, content: item.content, priority: item.priority, pinned: item.pinned, showOnLeadershipScreen: item.showOnLeadershipScreen }); window.scrollTo({ top: 0, behavior: 'smooth' }) }
async function save() {
  error.value = ''; success.value = ''
  try {
    await api(editingId.value ? `/api/v1/announcements/${editingId.value}` : '/api/v1/announcements', {
      method: editingId.value ? 'PATCH' : 'POST', body: jsonBody(form),
    })
    success.value = editingId.value ? '公告已更新。' : '公告已发布。'
    resetForm(); await load()
  } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '公告保存失败' }
}
async function archive(item: Announcement) {
  if (!window.confirm(`确认归档公告“${item.title}”？`)) return
  await api(`/api/v1/announcements/${item.id}/archive`, { method: 'POST' }); await load()
}
watch(includeArchived, load)
onMounted(() => load().catch(() => { error.value = '公告暂时无法加载' }))
</script>

<template>
  <div class="page announcements-page">
    <header class="page-header"><div><p class="eyebrow">ANNOUNCEMENTS</p><h1>公告中心</h1><p class="muted">重要安排统一发布，紧急和置顶公告优先展示。</p></div></header>
    <p v-if="error" class="notice error">{{ error }}</p><p v-if="success" class="notice">{{ success }}</p>
    <form v-if="canPublish" class="panel announcement-form" @submit.prevent="save">
      <div class="panel-title"><div><p class="eyebrow">PUBLISH</p><h2>{{ editingId ? '编辑公告' : '发布公告' }}</h2></div><button v-if="editingId" type="button" class="text-button" @click="resetForm">取消编辑</button></div>
      <label>标题<input v-model.trim="form.title" required maxlength="200" placeholder="公告标题"></label>
      <label>正文<textarea v-model.trim="form.content" required maxlength="10000" rows="6" placeholder="写清时间、对象和执行要求"></textarea></label>
      <div class="form-grid two"><label>优先级<select v-model="form.priority"><option value="NORMAL">普通</option><option value="IMPORTANT">重要</option><option value="URGENT">紧急</option></select></label><div class="announcement-display-options"><label class="check-label announcement-pin"><input v-model="form.pinned" type="checkbox">置顶显示</label><label class="check-label"><input v-model="form.showOnLeadershipScreen" type="checkbox">在领导大屏待机轮播</label></div></div>
      <button class="primary-button">{{ editingId ? '保存修改' : '发布公告' }}</button>
    </form>
    <label v-if="canPublish" class="check-label announcement-archive-filter"><input v-model="includeArchived" type="checkbox">显示已归档公告</label>
    <section class="announcement-list">
      <article v-for="item in announcements" :key="item.id" :class="['panel', 'announcement-card', `priority-${item.priority.toLowerCase()}`, { archived: item.status === 'ARCHIVED' }]">
        <header><div class="announcement-badges"><span v-if="item.pinned" class="badge badge-green">置顶</span><span v-if="item.showOnLeadershipScreen" class="badge badge-blue">大屏轮播</span><span class="badge">{{ priorityName(item.priority) }}</span><span v-if="item.status === 'ARCHIVED'" class="badge badge-amber">已归档</span></div><time>{{ new Date(item.publishedAt).toLocaleString() }}</time></header>
        <h2>{{ item.title }}</h2><p :class="['announcement-content', { expanded: expanded.has(item.id) }]">{{ item.content }}</p><button v-if="item.content.length > 120" type="button" class="content-toggle" :aria-expanded="expanded.has(item.id)" @click="toggleContent(item.id)">{{ expanded.has(item.id) ? '收起正文' : '展开全文' }}</button><footer><span>发布人：{{ item.authorDisplayName }}</span><div v-if="canManage(item) && item.status === 'PUBLISHED'"><button class="text-button" @click="edit(item)">编辑</button><button class="danger-link" @click="archive(item)">归档</button></div></footer>
      </article>
      <div v-if="!announcements.length" class="empty-state panel">暂无公告。</div>
    </section>
  </div>
</template>

<style scoped>
.content-toggle{display:none;color:#3157d5;background:transparent;font-size:12px;font-weight:700}
@media(max-width:700px){
  .announcement-form .form-grid.two{grid-template-columns:1fr}.announcement-display-options{display:grid;gap:8px}.announcement-form>.primary-button{width:100%;min-height:46px}
  .announcement-card{padding:18px}.announcement-card>header,.announcement-card>footer{align-items:flex-start;flex-direction:column}.announcement-badges{flex-wrap:wrap}.announcement-card time{white-space:normal}.announcement-card h2{font-size:17px;overflow-wrap:anywhere}
  .announcement-content{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:5;overflow-wrap:anywhere}.announcement-content.expanded{display:block;overflow:visible;-webkit-line-clamp:unset}
  .content-toggle{display:inline-flex;min-height:44px;align-items:center;padding:0}
  .announcement-card footer>div{display:grid;grid-template-columns:1fr 1fr;width:100%}.announcement-card footer button{min-height:44px}
}
</style>
