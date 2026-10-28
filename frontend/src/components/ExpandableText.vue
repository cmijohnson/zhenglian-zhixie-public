<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  text?: string | null
  lines?: number
  expandLabel?: string
  collapseLabel?: string
  emptyText?: string
}>(), {
  text: '',
  lines: 4,
  expandLabel: '展开全文',
  collapseLabel: '收起',
  emptyText: '—',
})

const content = ref<HTMLElement | null>(null)
const expanded = ref(false)
const overflowing = ref(false)
let observer: ResizeObserver | undefined

const value = computed(() => props.text?.trim() || props.emptyText)

async function measure() {
  await nextTick()
  const element = content.value
  if (!element) return
  overflowing.value = element.scrollHeight > element.clientHeight + 1
}

function toggle() {
  expanded.value = !expanded.value
  if (!expanded.value) void measure()
}

watch(() => props.text, () => {
  expanded.value = false
  void measure()
})

onMounted(() => {
  void measure()
  if ('ResizeObserver' in window && content.value) {
    observer = new ResizeObserver(() => { if (!expanded.value) void measure() })
    observer.observe(content.value)
  } else {
    window.addEventListener('resize', measure)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('resize', measure)
})
</script>

<template>
  <div class="expandable-text">
    <p
      ref="content"
      :class="{ expanded }"
      :style="{ '--expandable-lines': String(Math.max(1, lines)) }"
    >{{ value }}</p>
    <button v-if="overflowing || expanded" type="button" :aria-expanded="expanded" @click="toggle">
      {{ expanded ? collapseLabel : expandLabel }}
      <span aria-hidden="true">{{ expanded ? '↑' : '↓' }}</span>
    </button>
  </div>
</template>

<style scoped>
.expandable-text{min-width:0}.expandable-text p{display:-webkit-box;overflow:hidden;overflow-wrap:anywhere;color:inherit;line-height:inherit;white-space:pre-wrap;-webkit-box-orient:vertical;-webkit-line-clamp:var(--expandable-lines)}.expandable-text p.expanded{display:block;overflow:visible;-webkit-line-clamp:unset}.expandable-text button{display:inline-flex;min-height:40px;align-items:center;gap:5px;margin-top:3px;padding:0;color:#3157d5;background:transparent;font-size:11px;font-weight:700}.expandable-text button span{font-size:13px}
@media(max-width:900px){.expandable-text button{min-height:44px}}
</style>
