<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import type { NavigationItem } from '../lib/navigation'
import { isNavigationActive } from '../lib/navigation'

const props = withDefaults(defineProps<{
  items: NavigationItem[]
  urgentCount?: number
  unreadCount?: number
  moreActive?: boolean
  hidden?: boolean
}>(), {
  urgentCount: 0,
  unreadCount: 0,
  moreActive: false,
  hidden: false,
})

const emit = defineEmits<{ more: [] }>()
const route = useRoute()

function badgeFor(item: NavigationItem) {
  if (item.id === 'reminders') return props.urgentCount
  if (item.id === 'notifications') return props.unreadCount
  return 0
}

function badgeLabel(item: NavigationItem) {
  const count = badgeFor(item)
  if (!count) return item.label
  return item.id === 'reminders'
    ? `${item.label}，${count} 位 A 类客户超时`
    : `${item.label}，${count} 条未读`
}
</script>

<template>
  <nav :class="['mobile-bottom-nav', { 'is-hidden': hidden }]" aria-label="移动端主要导航">
    <RouterLink
      v-for="item in items"
      :key="item.id"
      :to="item.to"
      :class="{ active: isNavigationActive(route.path, item.to) }"
      :aria-current="isNavigationActive(route.path, item.to) ? 'page' : undefined"
      :aria-label="badgeLabel(item)"
    >
      <span class="mobile-nav-icon" aria-hidden="true">{{ item.icon }}</span>
      <span>{{ item.shortLabel ?? item.label }}</span>
      <b v-if="badgeFor(item)" class="mobile-nav-badge">{{ badgeFor(item) > 99 ? '99+' : badgeFor(item) }}</b>
    </RouterLink>
    <button :class="{ active: moreActive }" type="button" :aria-expanded="moreActive" aria-label="更多功能" @click="emit('more')">
      <span class="mobile-nav-icon more-icon" aria-hidden="true"><i></i><i></i><i></i></span>
      <span>更多</span>
      <b v-if="unreadCount && !items.some((item) => item.id === 'notifications')" class="mobile-nav-dot" aria-hidden="true"></b>
    </button>
  </nav>
</template>

<style scoped>
.mobile-bottom-nav{position:fixed;z-index:var(--layer-mobile-navigation,40);right:0;bottom:0;left:0;display:none;height:calc(var(--mobile-tabbar-height,64px) + env(safe-area-inset-bottom));grid-template-columns:repeat(5,minmax(0,1fr));align-items:start;padding:5px max(8px,env(safe-area-inset-right)) env(safe-area-inset-bottom) max(8px,env(safe-area-inset-left));border-top:1px solid rgba(30,52,97,.1);background:rgba(252,253,255,.93);box-shadow:0 -12px 34px rgba(19,36,74,.09);backdrop-filter:blur(22px) saturate(1.2);transition:transform .24s ease,opacity .2s ease}
.mobile-bottom-nav>a,.mobile-bottom-nav>button{position:relative;display:flex;min-width:0;min-height:54px;align-items:center;justify-content:center;flex-direction:column;gap:2px;padding:3px 2px;border-radius:13px;color:#7b879b;background:transparent;font-size:10px;font-weight:650;line-height:1.1}
.mobile-bottom-nav>a.active,.mobile-bottom-nav>button.active{color:#3157d5}.mobile-bottom-nav>a.active .mobile-nav-icon,.mobile-bottom-nav>button.active .mobile-nav-icon{background:#eaf0ff;transform:translateY(-1px)}
.mobile-nav-icon{display:grid;width:29px;height:29px;place-items:center;border-radius:10px;font-size:18px;transition:background .2s ease,transform .2s ease}.more-icon{grid-template-columns:repeat(3,3px);align-content:center;justify-content:center;gap:3px}.more-icon i{width:3px;height:3px;border-radius:50%;background:currentColor}
.mobile-nav-badge{position:absolute;top:1px;left:calc(50% + 7px);display:grid;min-width:18px;height:18px;place-items:center;padding:0 5px;border:2px solid #fff;border-radius:999px;color:#fff;background:#d54152;font-size:8px;line-height:1}.mobile-nav-dot{position:absolute;top:6px;left:calc(50% + 8px);width:8px;height:8px;border:2px solid #fff;border-radius:50%;background:#d54152}
.mobile-bottom-nav.is-hidden{pointer-events:none;opacity:0;transform:translateY(110%)}
@media(max-width:900px){.mobile-bottom-nav{display:grid}}
@media(prefers-reduced-motion:reduce){.mobile-bottom-nav,.mobile-nav-icon{transition-duration:.01ms!important}}
</style>
