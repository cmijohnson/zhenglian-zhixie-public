<script setup lang="ts">
import { getCurrentInstance, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { focusableElements, lockDocumentScroll, unlockDocumentScroll } from '../lib/mobileOverlay'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  variant?: 'full' | 'bottom'
  closeLabel?: string
}>(), {
  description: '',
  variant: 'full',
  closeLabel: '关闭',
})

const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)
const titleId = `mobile-sheet-title-${getCurrentInstance()?.uid ?? 'sheet'}`
let previousFocus: HTMLElement | null = null
let scrollLocked = false

function close() {
  emit('close')
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !panel.value) return
  const focusable = focusableElements(panel.value)
  if (!focusable.length) {
    event.preventDefault()
    panel.value.focus()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => props.open, async (open) => {
  if (open) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    lockDocumentScroll()
    scrollLocked = true
    await nextTick()
    const first = panel.value ? focusableElements(panel.value)[0] : undefined
    ;(first ?? panel.value)?.focus()
  } else {
    if (scrollLocked) {
      unlockDocumentScroll()
      scrollLocked = false
    }
    previousFocus?.focus()
    previousFocus = null
  }
}, { immediate: true })

onBeforeUnmount(() => {
  if (scrollLocked) unlockDocumentScroll()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="mobile-sheet">
      <div v-if="open" class="mobile-sheet-backdrop" @mousedown.self="close">
        <section
          ref="panel"
          :class="['mobile-sheet-panel', `is-${variant}`]"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="titleId"
          tabindex="-1"
          @keydown="handleKeydown"
        >
          <header class="mobile-sheet-header">
            <div>
              <h2 :id="titleId">{{ title }}</h2>
              <p v-if="description">{{ description }}</p>
            </div>
            <button class="mobile-sheet-close" type="button" :aria-label="closeLabel" @click="close">×</button>
          </header>
          <div class="mobile-sheet-body"><slot /></div>
          <footer v-if="$slots.footer" class="mobile-sheet-footer"><slot name="footer" /></footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mobile-sheet-backdrop{position:fixed;inset:0;z-index:var(--layer-mobile-sheet,80);display:flex;align-items:flex-end;justify-content:center;padding:max(10px,env(safe-area-inset-top)) 10px 0;background:rgba(12,22,48,.42);backdrop-filter:blur(12px)}
.mobile-sheet-panel{display:flex;width:min(760px,100%);max-height:calc(100dvh - max(10px,env(safe-area-inset-top)));flex-direction:column;overflow:hidden;border:1px solid rgba(255,255,255,.84);border-radius:25px 25px 0 0;background:#f8faff;box-shadow:0 -28px 80px rgba(12,27,64,.25);outline:0}
.mobile-sheet-panel.is-full{height:calc(100dvh - max(10px,env(safe-area-inset-top)))}
.mobile-sheet-panel.is-bottom{max-height:min(88dvh,820px)}
.mobile-sheet-header{display:flex;flex:0 0 auto;align-items:flex-start;justify-content:space-between;gap:18px;padding:20px 20px 16px;border-bottom:1px solid #e5eaf4;background:rgba(255,255,255,.94)}
.mobile-sheet-header h2{color:#17213a;font-size:22px}.mobile-sheet-header p{margin-top:5px;color:#78849a;font-size:12px;line-height:1.55}
.mobile-sheet-close{display:grid;width:44px;min-width:44px;height:44px;place-items:center;padding:0;border-radius:50%;color:#536078;background:#eef2f8;font-size:25px}
.mobile-sheet-body{min-height:0;flex:1;padding:18px 20px calc(20px + env(safe-area-inset-bottom));overflow:auto;overscroll-behavior:contain}
.mobile-sheet-footer{flex:0 0 auto;padding:12px 20px calc(12px + env(safe-area-inset-bottom));border-top:1px solid #e1e7f1;background:rgba(255,255,255,.96);box-shadow:0 -12px 30px rgba(22,40,82,.06)}
.mobile-sheet-enter-active,.mobile-sheet-leave-active{transition:opacity .22s ease}.mobile-sheet-enter-active .mobile-sheet-panel,.mobile-sheet-leave-active .mobile-sheet-panel{transition:transform .28s cubic-bezier(.22,.8,.22,1)}.mobile-sheet-enter-from,.mobile-sheet-leave-to{opacity:0}.mobile-sheet-enter-from .mobile-sheet-panel,.mobile-sheet-leave-to .mobile-sheet-panel{transform:translateY(36px)}
@media(min-width:901px){.mobile-sheet-panel.is-full{width:min(760px,calc(100% - 48px));height:min(840px,calc(100dvh - 48px));border-radius:25px}.mobile-sheet-backdrop{align-items:center;padding:24px}}
@media(prefers-reduced-motion:reduce){.mobile-sheet-enter-active,.mobile-sheet-leave-active,.mobile-sheet-enter-active .mobile-sheet-panel,.mobile-sheet-leave-active .mobile-sheet-panel{transition-duration:.01ms!important}}
</style>
