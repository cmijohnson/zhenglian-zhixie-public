<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const canvas = ref<HTMLCanvasElement>()
let frame = 0
let context: CanvasRenderingContext2D | null = null
let particles: Array<{ x: number; y: number; radius: number; speed: number; phase: number }> = []

function resize() {
  const element = canvas.value
  if (!element) return
  const ratio = Math.min(window.devicePixelRatio || 1, 2)
  element.width = Math.max(1, Math.floor(element.clientWidth * ratio))
  element.height = Math.max(1, Math.floor(element.clientHeight * ratio))
  context = element.getContext('2d')
  context?.setTransform(ratio, 0, 0, ratio, 0, 0)
  const count = Math.min(90, Math.max(28, Math.floor(element.clientWidth / 24)))
  particles = Array.from({ length: count }, (_, index) => ({
    x: Math.random() * element.clientWidth,
    y: Math.random() * element.clientHeight,
    radius: .45 + Math.random() * 1.4,
    speed: .08 + Math.random() * .32,
    phase: index * .7,
  }))
}

function draw(timestamp: number) {
  const element = canvas.value
  if (!element || !context) return
  context.clearRect(0, 0, element.clientWidth, element.clientHeight)
  const centerX = element.clientWidth / 2
  const centerY = element.clientHeight / 2
  for (const particle of particles) {
    particle.y -= particle.speed
    if (particle.y < -8) {
      particle.y = element.clientHeight + 8
      particle.x = Math.random() * element.clientWidth
    }
    const glow = .35 + Math.sin(timestamp / 900 + particle.phase) * .22
    context.beginPath()
    context.fillStyle = `rgba(78, 226, 233, ${glow})`
    context.shadowColor = 'rgba(25, 230, 211, .65)'
    context.shadowBlur = 8
    context.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2)
    context.fill()
  }
  context.shadowBlur = 0
  const radius = Math.min(element.clientWidth, element.clientHeight) * .32
  context.strokeStyle = 'rgba(70, 193, 227, .055)'
  context.lineWidth = 1
  for (let ring = 1; ring <= 3; ring += 1) {
    context.beginPath()
    context.arc(centerX, centerY, radius * ring / 3, 0, Math.PI * 2)
    context.stroke()
  }
  frame = window.requestAnimationFrame(draw)
}

function visibilityChanged() {
  window.cancelAnimationFrame(frame)
  if (!document.hidden) frame = window.requestAnimationFrame(draw)
}

onMounted(() => {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  resize()
  window.addEventListener('resize', resize)
  document.addEventListener('visibilitychange', visibilityChanged)
  frame = window.requestAnimationFrame(draw)
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(frame)
  window.removeEventListener('resize', resize)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template><canvas ref="canvas" class="leadership-particle-canvas" aria-hidden="true"></canvas></template>
