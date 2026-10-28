<script setup lang="ts">
import MobileFullScreenSheet from './MobileFullScreenSheet.vue'

withDefaults(defineProps<{
  open: boolean
  title?: string
  description?: string
  busy?: boolean
  applyLabel?: string
  resetLabel?: string
}>(), {
  title: '筛选条件',
  description: '调整条件后应用到当前列表。',
  busy: false,
  applyLabel: '应用筛选',
  resetLabel: '清空条件',
})

const emit = defineEmits<{ close: []; apply: []; reset: [] }>()
</script>

<template>
  <MobileFullScreenSheet
    :open="open"
    :title="title"
    :description="description"
    variant="bottom"
    @close="emit('close')"
  >
    <form class="mobile-filter-form" @submit.prevent="emit('apply')">
      <slot />
    </form>
    <template #footer>
      <div class="mobile-filter-actions">
        <button class="ghost-button" type="button" :disabled="busy" @click="emit('reset')">{{ resetLabel }}</button>
        <button class="primary-button" type="button" :disabled="busy" @click="emit('apply')">{{ busy ? '正在应用…' : applyLabel }}</button>
      </div>
    </template>
  </MobileFullScreenSheet>
</template>

<style scoped>
.mobile-filter-form{display:grid;gap:16px}.mobile-filter-form :deep(label){display:grid;gap:7px;color:#4f5c74;font-weight:650}.mobile-filter-form :deep(input:not([type="checkbox"]):not([type="radio"])),.mobile-filter-form :deep(select),.mobile-filter-form :deep(textarea){width:100%;min-width:0;font-size:16px}.mobile-filter-actions{display:grid;grid-template-columns:minmax(0,.75fr) minmax(0,1.25fr);gap:10px}.mobile-filter-actions>*{width:100%;min-height:48px}
</style>
