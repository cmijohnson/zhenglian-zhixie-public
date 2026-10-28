<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../lib/api'
import { useAuthStore } from '../stores/auth'

type CircleTab = 'all' | 'mine' | 'submitted' | 'valid' | 'invalid'
type PlatformFilter = '' | 'XIAOHONGSHU' | 'DOUYIN'
type ViewMode = 'cards' | 'list'
interface Post { id: string; membershipId: string; memberDisplayName: string; platform: 'XIAOHONGSHU' | 'DOUYIN'; publishDate: string; directionTitle: string; topic: string; contentUrl: string; viewCount: number; likeCount: number; favoriteCount: number; weightScore: number; reviewStatus: 'SUBMITTED' | 'VALID' | 'INVALID'; reviewReason?: string }
interface Sender { membershipId: string; displayName: string }
interface PageResponse<T> { items: T[]; page: number; totalPages: number; totalElements: number }

const auth = useAuthStore()
const posts = ref<Post[]>([])
const senders = ref<Sender[]>([])
const error = ref('')
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)
const search = ref('')
const senderId = ref('')
const sort = ref('recommended')
const tab = ref<CircleTab>('all')
const platformFilter = ref<PlatformFilter>('')
const viewMode = ref<ViewMode>('cards')
const advancedOpen = ref(true)
let timer: number | undefined

const tabs: { value: CircleTab; label: string }[] = [
  { value: 'all', label: '全部内容' },
  { value: 'mine', label: '我的发布' },
  { value: 'submitted', label: '待审核' },
  { value: 'valid', label: '有效内容' },
  { value: 'invalid', label: '不计入' },
]
const reviewStatus = computed(() => ({ submitted: 'SUBMITTED', valid: 'VALID', invalid: 'INVALID' } as const)[tab.value as 'submitted' | 'valid' | 'invalid'])
const platform = (value: Post['platform']) => value === 'XIAOHONGSHU' ? '小红书' : '抖音'
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const reviewName = (post: Post) => post.reviewStatus === 'VALID' ? '有效内容' : post.reviewStatus === 'INVALID' ? '不计入' : '待审核'
const weightName = (post: Post) => {
  if (post.reviewStatus === 'INVALID') return '低权重'
  if (post.weightScore >= 1_010_000) return '高权重'
  if (post.reviewStatus === 'VALID') return '有效权重'
  return '待审核权重'
}

async function loadPage(nextPage = 0) {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(nextPage), size: '20', sort: sort.value })
    if (search.value) params.set('search', search.value)
    const membershipId = tab.value === 'mine' ? auth.user?.membershipId : senderId.value
    if (membershipId) params.set('membershipId', membershipId)
    if (platformFilter.value) params.set('platform', platformFilter.value)
    if (reviewStatus.value) params.set('reviewStatus', reviewStatus.value)
    const result = await api<PageResponse<Post>>(`/api/v1/circle/posts?${params.toString()}`)
    posts.value = nextPage === 0 ? result.items : [...posts.value, ...result.items]
    page.value = result.page
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
    error.value = ''
  } catch { error.value = '内容圈子暂时无法加载' }
  finally { loading.value = false }
}

function resetFilters() {
  search.value = ''
  senderId.value = ''
  platformFilter.value = ''
  sort.value = 'recommended'
  tab.value = 'all'
}

watch([tab, platformFilter, senderId, sort], () => loadPage(0))
watch(search, () => {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => loadPage(0), 250)
})
onMounted(async () => {
  try { senders.value = await api<Sender[]>('/api/v1/circle/senders') } catch { senders.value = [] }
  await loadPage()
})
</script>

<template>
  <div class="page circle-page">
    <header class="page-header"><div><p class="eyebrow">CONTENT LIBRARY</p><h1>内容圈子</h1><p class="muted">按发布人、平台和审核状态管理组织内容，也可切换为紧凑列表快速浏览。</p></div><div class="view-switch" aria-label="内容展示模式"><button :class="{ active: viewMode === 'cards' }" @click="viewMode = 'cards'">卡片</button><button :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">列表</button></div></header>

    <section class="panel circle-workspace">
      <nav class="circle-tabs" aria-label="内容分区">
        <button v-for="item in tabs" :key="item.value" :class="{ active: tab === item.value }" :aria-pressed="tab === item.value" @click="tab = item.value">{{ item.label }}</button>
      </nav>
      <div class="circle-quick-filters">
        <span>快捷筛选</span>
        <button :class="{ active: platformFilter === '' }" @click="platformFilter = ''">全部平台</button>
        <button :class="{ active: platformFilter === 'XIAOHONGSHU' }" @click="platformFilter = 'XIAOHONGSHU'">小红书</button>
        <button :class="{ active: platformFilter === 'DOUYIN' }" @click="platformFilter = 'DOUYIN'">抖音</button>
        <button class="filter-toggle" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? '收起筛选' : '更多筛选' }} {{ advancedOpen ? '⌃' : '⌄' }}</button>
      </div>
      <div v-if="advancedOpen" class="circle-advanced-filters">
        <label class="search-box"><span>⌕</span><input v-model.trim="search" placeholder="搜索主题、方向、链接或发布人"></label>
        <label>发布人<select v-model="senderId" :disabled="tab === 'mine'"><option value="">全部发布人</option><option v-for="sender in senders" :key="sender.membershipId" :value="sender.membershipId">{{ sender.displayName }}</option></select></label>
        <label>排序方式<select v-model="sort"><option value="recommended">推荐权重</option><option value="latest">最新发布</option><option value="views">播放最多</option><option value="likes">点赞最多</option><option value="favorites">收藏最多</option></select></label>
        <button class="ghost-button" @click="resetFilters">重置筛选</button>
      </div>
      <div class="circle-result-meta"><span>共 <strong>{{ number(totalElements) }}</strong> 条内容</span><span v-if="loading">正在更新…</span><span v-else>当前按{{ sort === 'recommended' ? '推荐权重' : sort === 'latest' ? '发布时间' : '互动指标' }}排序</span></div>
    </section>

    <p v-if="error" class="notice error">{{ error }}</p>
    <div v-if="viewMode === 'cards'" class="circle-feed">
      <article v-for="post in posts" :key="post.id" class="panel circle-card">
        <header><span class="avatar small">{{ post.memberDisplayName.slice(0, 1) }}</span><div><strong>{{ post.memberDisplayName }}</strong><small>{{ post.publishDate }} · {{ platform(post.platform) }} · {{ post.directionTitle }}</small></div><div class="circle-weight"><span :class="['badge', post.reviewStatus === 'VALID' ? 'badge-green' : post.reviewStatus === 'INVALID' ? 'badge-red' : 'badge-amber']">{{ reviewName(post) }}</span><small>{{ weightName(post) }}</small></div></header>
        <h2>{{ post.topic }}</h2><p v-if="post.reviewReason" class="notice error">审核说明：{{ post.reviewReason }}</p>
        <div class="circle-metrics"><span><strong>{{ number(post.viewCount) }}</strong>播放</span><span><strong>{{ number(post.likeCount) }}</strong>点赞</span><span><strong>{{ number(post.favoriteCount) }}</strong>收藏</span><a :href="post.contentUrl" target="_blank" rel="noopener noreferrer" class="primary-button">查看原内容</a></div>
      </article>
    </div>
    <section v-else-if="posts.length" class="panel circle-list">
      <header class="circle-list-head"><span>内容</span><span>发布信息</span><span>互动数据</span><span>状态</span><span>操作</span></header>
      <article v-for="post in posts" :key="post.id" class="circle-list-row">
        <div><strong>{{ post.topic }}</strong><small>{{ post.directionTitle }}</small></div>
        <div><strong>{{ post.memberDisplayName }}</strong><small>{{ post.publishDate }} · {{ platform(post.platform) }}</small></div>
        <div class="circle-list-metrics"><span>{{ number(post.viewCount) }} 播放</span><span>{{ number(post.likeCount) }} 赞</span><span>{{ number(post.favoriteCount) }} 收藏</span></div>
        <div><span :class="['badge', post.reviewStatus === 'VALID' ? 'badge-green' : post.reviewStatus === 'INVALID' ? 'badge-red' : 'badge-amber']">{{ reviewName(post) }}</span><small>{{ weightName(post) }}</small></div>
        <a :href="post.contentUrl" target="_blank" rel="noopener noreferrer" class="soft-button">打开</a>
      </article>
    </section>
    <div v-if="!posts.length && !error && !loading" class="panel empty-state">当前分区没有符合条件的内容。</div>
    <button v-if="page + 1 < totalPages" class="secondary-button circle-more" :disabled="loading" @click="loadPage(page + 1)">{{ loading ? '加载中…' : '加载更多' }}</button>
  </div>
</template>

<style scoped>
@media(max-width:700px){
  .page-header{align-items:stretch}.view-switch{display:grid;grid-template-columns:1fr 1fr;width:100%}.view-switch button{min-height:44px}
  .circle-tabs{gap:20px;padding-inline:16px;scrollbar-width:none}.circle-tabs button{min-height:48px}
  .circle-quick-filters{display:flex;flex-wrap:nowrap;overflow-x:auto;padding-inline:16px;scrollbar-width:none}.circle-quick-filters>span,.circle-quick-filters button{flex:0 0 auto}.circle-quick-filters .filter-toggle{margin-left:0}
  .circle-advanced-filters{grid-template-columns:1fr;padding:16px}.circle-advanced-filters .ghost-button{width:100%;min-height:44px}
  .circle-result-meta{align-items:flex-start;padding-inline:16px}.circle-result-meta span:last-child{text-align:right}
  .circle-card{padding:18px}.circle-card header{grid-template-columns:auto minmax(0,1fr)}.circle-weight{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;text-align:left}.circle-card h2{margin:16px 0;font-size:17px;overflow-wrap:anywhere}
  .circle-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.circle-metrics a{grid-column:1/-1;width:100%;margin-left:0;min-height:44px}
  .circle-list{padding:0;overflow:visible}.circle-list-head{display:none}.circle-list-row{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:16px 0}.circle-list-row>div:first-child{grid-column:1/-1}.circle-list-metrics{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px}.circle-list-row>a{align-self:end;justify-self:stretch;min-height:44px}
}
</style>
