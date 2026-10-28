<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, api } from '../lib/api'
import AdminCrossSaleQuery from '../components/AdminCrossSaleQuery.vue'

interface Member { membershipId: string; displayName: string }
interface Overview { members: Member[] }
const members = ref<Member[]>([]); const error = ref('')
onMounted(async () => {
  try { const result = await api<Overview>('/api/v1/team-data/overview'); members.value=result.members??[] }
  catch (reason) { error.value = reason instanceof ApiError ? reason.message : '团队范围加载失败' }
})
</script>

<template><div class="page market-insights-page"><header class="page-header"><div><p class="eyebrow">MARKET INSIGHTS</p><h1>业务洞察</h1><p class="muted">团队长可按所管团队快速查询交叉单；只要客户档案存在有效协作者，即统一判定为交叉单。</p></div></header><p v-if="error" class="notice error">{{ error }}</p><AdminCrossSaleQuery :members="members"/></div></template>

<style scoped>
.market-insights-page{display:grid;min-width:0;grid-template-columns:minmax(0,1fr);gap:22px}
@media(max-width:700px){
  .market-insights-page{gap:14px}.market-insights-page>*{min-width:0;max-width:100%}
  .market-insights-page :deep(.panel){padding:18px}
  .market-insights-page :deep(.panel-title){align-items:flex-start;flex-direction:column}
  .market-insights-page :deep(.query-grid){grid-template-columns:1fr}
  .market-insights-page :deep(.query-actions){display:grid;grid-template-columns:1fr}
  .market-insights-page :deep(.query-actions>*){width:100%;min-height:44px}
  .market-insights-page :deep(.pagination){display:grid;grid-template-columns:1fr auto 1fr}.market-insights-page :deep(.pagination button){min-height:44px}
}
</style>
