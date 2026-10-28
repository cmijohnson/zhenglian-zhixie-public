<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { NavigationGroup, NavigationItem } from '../lib/navigation'
import { isNavigationActive } from '../lib/navigation'
import MobileFullScreenSheet from './MobileFullScreenSheet.vue'

const props = withDefaults(defineProps<{
  open: boolean
  groups: NavigationGroup[]
  commonItems: NavigationItem[]
  currentPath: string
  displayName?: string
  roles?: string[]
}>(), {
  displayName: '',
  roles: () => [],
})

const emit = defineEmits<{ close: []; select: [path: string]; logout: [] }>()
const query = ref('')

const roleNames: Record<string, string> = {
  ORG_ADMIN: '组织管理员',
  TEAM_MANAGER: '团队负责人',
  MEMBER: '成员',
  VIEWER: '只读成员',
}

const roleText = computed(() => props.roles.map((role) => roleNames[role] ?? role).join(' · '))
const normalizedQuery = computed(() => query.value.trim().toLocaleLowerCase('zh-CN'))
const visibleGroups = computed(() => {
  if (!normalizedQuery.value) return props.groups
  return props.groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => `${item.label}${group.label}`.toLocaleLowerCase('zh-CN').includes(normalizedQuery.value)),
    }))
    .filter((group) => group.items.length)
})
const resultCount = computed(() => visibleGroups.value.reduce((total, group) => total + group.items.length, 0))

function select(path: string) {
  emit('select', path)
}

watch(() => props.open, (open) => {
  if (!open) query.value = ''
})
</script>

<template>
  <MobileFullScreenSheet
    :open="open"
    title="全部功能"
    description="搜索或按分组进入，当前账号只显示有权访问的功能。"
    variant="bottom"
    @close="emit('close')"
  >
    <section class="mobile-account-card">
      <span class="mobile-account-avatar" aria-hidden="true">{{ displayName.slice(0, 1) || '英' }}</span>
      <div><strong>{{ displayName || '当前账号' }}</strong><small>{{ roleText }}</small></div>
      <button type="button" @click="select('/settings')">账户设置</button>
    </section>

    <label class="mobile-menu-search">
      <span aria-hidden="true">⌕</span>
      <input v-model="query" type="search" inputmode="search" autocomplete="off" placeholder="搜索功能，例如“绩效”“导出”" aria-label="搜索功能">
      <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''">×</button>
    </label>

    <section v-if="!query && commonItems.length" class="mobile-common-section">
      <header><strong>常用功能</strong><small>已按当前角色排序</small></header>
      <div class="mobile-common-grid">
        <button v-for="item in commonItems" :key="item.id" type="button" @click="select(item.to)">
          <span aria-hidden="true">{{ item.icon }}</span><b>{{ item.label }}</b>
        </button>
      </div>
    </section>

    <p v-if="query" class="mobile-search-count">找到 {{ resultCount }} 个功能</p>
    <div v-if="visibleGroups.length" class="mobile-menu-groups">
      <details
        v-for="group in visibleGroups"
        :key="group.key"
        :open="Boolean(query) || group.items.some((item) => isNavigationActive(currentPath, item.to))"
      >
        <summary><span>{{ group.label }}</span><small>{{ group.items.length }} 项</small></summary>
        <div>
          <button
            v-for="item in group.items"
            :key="item.id"
            :class="{ active: isNavigationActive(currentPath, item.to) }"
            type="button"
            :aria-current="isNavigationActive(currentPath, item.to) ? 'page' : undefined"
            @click="select(item.to)"
          >
            <span aria-hidden="true">{{ item.icon }}</span>
            <b>{{ item.label }}</b>
            <i aria-hidden="true">›</i>
          </button>
        </div>
      </details>
    </div>
    <div v-else class="mobile-menu-empty">没有找到匹配的功能，请换一个关键词。</div>

    <button class="mobile-logout-button" type="button" @click="emit('logout')">安全退出当前账号</button>
  </MobileFullScreenSheet>
</template>

<style scoped>
.mobile-account-card{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:12px;margin-bottom:16px;padding:14px;border:1px solid #e1e7f2;border-radius:17px;background:#fff}.mobile-account-avatar{display:grid;width:43px;height:43px;place-items:center;border-radius:14px;color:#fff;background:linear-gradient(145deg,#6686ef,#3157d5);font-size:18px;font-weight:800}.mobile-account-card strong,.mobile-account-card small{display:block}.mobile-account-card strong{font-size:14px}.mobile-account-card small{margin-top:4px;color:#8893a6;font-size:10px}.mobile-account-card button{min-height:44px;padding:0 12px;border-radius:11px;color:#3454ba;background:#edf2ff;font-size:11px;font-weight:700}
.mobile-menu-search{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:9px;margin-bottom:20px;padding:0 13px;border:1px solid #dce3ef;border-radius:14px;background:#fff;box-shadow:0 8px 24px rgba(27,48,93,.05)}.mobile-menu-search>span{color:#7e8aa0;font-size:20px}.mobile-menu-search input{width:100%;min-width:0;min-height:50px;padding:0;border:0;background:transparent;box-shadow:none;font-size:16px;outline:0}.mobile-menu-search button{width:34px;min-height:34px;padding:0;border-radius:50%;color:#738096;background:#eef1f6;font-size:19px}
.mobile-common-section{margin-bottom:20px}.mobile-common-section>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.mobile-common-section>header strong{font-size:13px}.mobile-common-section>header small{color:#919aac;font-size:9px}.mobile-common-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.mobile-common-grid button{display:grid;min-height:62px;grid-template-columns:36px minmax(0,1fr);align-items:center;gap:10px;padding:9px 12px;border:1px solid #e1e7f2;border-radius:14px;color:#33415c;background:#fff;text-align:left}.mobile-common-grid button>span{display:grid;width:34px;height:34px;place-items:center;border-radius:11px;color:#3157d5;background:#edf2ff;font-size:17px}.mobile-common-grid b{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.mobile-search-count{margin:0 2px 10px;color:#7c879b;font-size:11px}.mobile-menu-groups{display:grid;gap:10px}.mobile-menu-groups details{overflow:hidden;border:1px solid #e0e6f1;border-radius:15px;background:#fff}.mobile-menu-groups summary{display:flex;min-height:49px;align-items:center;justify-content:space-between;padding:0 15px;color:#36435d;cursor:pointer;font-size:13px;font-weight:750;list-style:none}.mobile-menu-groups summary::-webkit-details-marker{display:none}.mobile-menu-groups summary small{color:#929cad;font-size:9px;font-weight:500}.mobile-menu-groups details>div{padding:0 8px 8px}.mobile-menu-groups button{display:grid;width:100%;min-height:47px;grid-template-columns:34px minmax(0,1fr) auto;align-items:center;gap:9px;padding:5px 8px;border-radius:11px;color:#5c687e;background:transparent;text-align:left}.mobile-menu-groups button>span{display:grid;width:32px;height:32px;place-items:center;border-radius:10px;color:#60718f;background:#f1f4f9;font-size:16px}.mobile-menu-groups button b{font-size:12px}.mobile-menu-groups button i{font-size:19px;font-style:normal}.mobile-menu-groups button.active{color:#2446ae;background:#edf2ff}.mobile-menu-groups button.active>span{color:#3157d5;background:#fff}.mobile-menu-empty{padding:34px 18px;border:1px dashed #dce2ed;border-radius:15px;color:#8791a3;background:#fff;text-align:center;font-size:12px}
.mobile-logout-button{width:100%;min-height:48px;margin-top:18px;border:1px solid #f0d6d8;border-radius:14px;color:#b13a48;background:#fff7f8;font-size:12px;font-weight:700}
@media(max-width:380px){.mobile-common-grid{grid-template-columns:1fr}.mobile-account-card{grid-template-columns:auto minmax(0,1fr)}.mobile-account-card button{grid-column:1/-1;width:100%}}
</style>
