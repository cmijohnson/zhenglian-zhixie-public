<script setup lang="ts">
defineProps<{ value: string; tone?: 'blue' | 'green' | 'amber' | 'red' | 'gray' }>()
</script>

<template><span class="badge" :class="`badge-${tone ?? 'blue'}`">{{ value }}</span></template>

