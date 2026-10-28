<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const rejected = ref(false)

function safeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/dashboard'
  if (value.includes('\\') || /^\/api(?:\/|$)/i.test(value) || /^\/feishu\/crm(?:[/?#]|$)/i.test(value)) return '/dashboard'
  try {
    const decoded = decodeURIComponent(value)
    if (!decoded.startsWith('/') || decoded.startsWith('//') || decoded.includes('\\') || /^[a-z][a-z0-9+.-]*:/i.test(decoded.slice(1))) return '/dashboard'
  } catch {
    return '/dashboard'
  }
  return value
}

onMounted(async () => {
  const requested = typeof route.query.returnTo === 'string' ? route.query.returnTo : '/dashboard'
  const target = safeReturnTo(requested)
  rejected.value = target !== requested
  await router.replace(target)
})
</script>

<template>
  <main class="handoff"><div class="spinner"></div><h1>正在进入完整 CRM</h1><p>{{ rejected ? '不安全的跳转地址已被拦截，正在返回工作台。' : '已完成飞书身份校验，正在载入英格云。' }}</p></main>
</template>

<style scoped>
.handoff{min-height:100dvh;display:grid;place-content:center;justify-items:center;gap:12px;padding:24px;background:#f5f7fb;color:#17233d;text-align:center}.spinner{width:44px;height:44px;border:4px solid #dce4f7;border-top-color:#3f68e8;border-radius:50%;animation:spin .8s linear infinite}.handoff h1{margin:4px 0 0}.handoff p{margin:0;color:#68758f}@keyframes spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.spinner{animation:none}}
</style>
