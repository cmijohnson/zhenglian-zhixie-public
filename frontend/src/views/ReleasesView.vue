<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import type { ProductRelease } from '../types'

interface ReleasePage {
  items: ProductRelease[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

interface MobileRelease {
  id: string
  platform: string
  versionCode: number
  versionName: string
  notes: string
  apkSize?: number
}

const result = ref<ReleasePage>({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
const androidRelease = ref<MobileRelease>()
const busy = ref(false)
const error = ref('')
const expanded = ref<Set<string>>(new Set())

function toggleRelease(id: string) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

async function load(page = 0) {
  busy.value = true
  error.value = ''
  try {
    const [releasePage, mobileRelease] = await Promise.all([
      api<ReleasePage>(`/api/v1/releases?page=${page}&size=20`),
      api<MobileRelease | undefined>('/api/v1/mobile/app-releases/current').catch(() => undefined),
    ])
    result.value = releasePage
    androidRelease.value = mobileRelease?.platform === 'ANDROID' && mobileRelease.id ? mobileRelease : undefined
  }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '更新日志暂时无法加载' }
  finally { busy.value = false }
}

onMounted(() => load())
</script>

<template>
  <div class="page narrow-page release-page">
    <header class="page-header">
      <div><p class="eyebrow">PRODUCT UPDATES</p><h1>更新日志</h1><p class="muted">查看英格客户云每个版本带来的新能力、改进与修复。</p></div>
      <span class="release-count">{{ result.totalElements }} 个云端版本</span>
    </header>
    <p v-if="error" class="notice error">{{ error }}</p>
    <section v-if="androidRelease" class="panel android-download-card">
      <div>
        <p class="eyebrow">ANDROID APP</p>
        <h2>英格云 Android {{ androidRelease.versionName }}</h2>
        <p class="muted">{{ androidRelease.notes }}<span v-if="androidRelease.apkSize"> · {{ (androidRelease.apkSize / 1024 / 1024).toFixed(1) }} MB</span></p>
      </div>
      <a class="primary-button" :href="`/api/v1/mobile/app-releases/${encodeURIComponent(androidRelease.id)}/apk`" download="yingge-cloud.apk">下载 APK</a>
    </section>
    <div v-if="busy" class="loading-state panel">正在加载更新记录…</div>
    <div v-else class="release-timeline">
      <article v-for="release in result.items" :key="release.id" :class="['panel', 'release-card', { current: release.current }]">
        <header>
          <div><div class="release-version-row"><span class="release-version">v{{ release.version }}</span><span v-if="release.current" class="release-current">当前版本</span></div><h2>{{ release.title }}</h2></div>
          <time>{{ dateTime(release.releasedAt) }}</time>
        </header>
        <p :class="['release-summary', { expanded: expanded.has(release.id) }]">{{ release.summary }}</p>
        <div :class="['release-sections', { expanded: expanded.has(release.id) }]">
        <section v-for="(items, section) in release.sections" :key="section" class="release-section">
          <strong>{{ section }}</strong>
          <ul><li v-for="item in items" :key="item">{{ item }}</li></ul>
        </section>
        </div>
        <button type="button" class="release-toggle" :aria-expanded="expanded.has(release.id)" @click="toggleRelease(release.id)">{{ expanded.has(release.id) ? '收起版本详情' : '展开版本详情' }}</button>
      </article>
      <div v-if="!result.items.length" class="empty-state panel">暂无更新记录。</div>
    </div>
    <nav v-if="result.totalPages > 1" class="pagination">
      <button class="ghost-button" :disabled="result.page === 0" @click="load(result.page - 1)">上一页</button>
      <span>{{ result.page + 1 }} / {{ result.totalPages }}</span>
      <button class="ghost-button" :disabled="result.page + 1 >= result.totalPages" @click="load(result.page + 1)">下一页</button>
    </nav>
  </div>
</template>

<style scoped>
.release-page{max-width:980px}.android-download-card{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px;background:linear-gradient(135deg,#f7f9ff,#eef3ff)}.android-download-card h2{margin:6px 0 8px}.android-download-card p{margin:0;line-height:1.65}.android-download-card .primary-button{display:inline-flex;align-items:center;justify-content:center;min-width:128px;text-decoration:none}.release-count{padding:8px 13px;border:1px solid #dce4f7;border-radius:999px;color:#3656bd;background:#f5f7ff;font-size:11px;font-weight:700}.release-timeline{display:grid;gap:18px}.release-card{position:relative;overflow:hidden}.release-card::before{position:absolute;inset:0 auto 0 0;width:4px;background:#dfe5f1;content:""}.release-card.current::before{background:linear-gradient(#6c8aff,#3157d5)}.release-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:22px}.release-card h2{margin-top:8px;font-size:23px}.release-card time{color:var(--muted);font-size:11px;white-space:nowrap}.release-version-row{display:flex;align-items:center;gap:9px}.release-version{color:#3157d5;font-size:11px;font-weight:800;letter-spacing:.08em}.release-current{padding:4px 8px;border-radius:999px;color:#fff;background:#3157d5;font-size:9px;font-weight:700}.release-summary{margin:16px 0 20px;color:#556176;line-height:1.75}.release-section{display:grid;grid-template-columns:70px minmax(0,1fr);gap:18px;padding:13px 0;border-top:1px solid #edf0f5}.release-section strong{color:#263d88;font-size:12px}.release-section ul{display:grid;gap:7px;margin:0;padding-left:18px;color:#4f5c72;font-size:12px;line-height:1.65}.release-toggle{display:none;color:#3157d5;background:transparent;font-size:12px;font-weight:700}.pagination{display:flex;align-items:center;justify-content:center;gap:16px;margin-top:22px;color:var(--muted);font-size:11px}@media(max-width:640px){.page-header{align-items:flex-start}.release-count{align-self:flex-start}.android-download-card{display:grid}.android-download-card .primary-button{width:100%;min-height:48px}.release-card{padding:18px}.release-card>header{display:grid}.release-card h2{font-size:19px;overflow-wrap:anywhere}.release-card time{order:2}.release-summary{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow-wrap:anywhere}.release-summary.expanded{display:block;-webkit-line-clamp:unset}.release-sections{max-height:150px;overflow:hidden;mask-image:linear-gradient(#000 70%,transparent)}.release-sections.expanded{max-height:none;overflow:visible;mask-image:none}.release-section{grid-template-columns:1fr;gap:8px}.release-section li{overflow-wrap:anywhere}.release-toggle{display:inline-flex;min-height:44px;align-items:center;padding:0}.pagination button{min-height:44px}}
</style>
