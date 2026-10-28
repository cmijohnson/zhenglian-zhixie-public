<script setup lang="ts">
import { ref } from 'vue'

const format = ref('xlsx')
function download() { window.location.assign(`/api/v1/exports/customers?format=${format.value}`) }
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">PERMISSION-SAFE EXPORT</p><h1>数据导出</h1><p class="muted">服务端会按当前角色和数据范围裁剪结果，并记录审计日志。</p></div></header>
    <section class="panel export-card">
      <div class="export-art">⇩</div><div><h2>可见客户档案</h2><p>导出姓名、联系方式、客户画像、状态、标签与跟进时间。不会包含无权访问的客户。</p><div class="inline-options"><label><input v-model="format" type="radio" value="xlsx">Excel（推荐）</label><label><input v-model="format" type="radio" value="csv">CSV</label></div><button class="primary-button" @click="download">开始安全导出</button></div>
    </section>
    <div class="notice"><strong>数据使用提醒</strong><span>导出文件可能包含个人信息，请仅用于授权业务，并妥善保管。</span></div>
  </div>
</template>

<style scoped>
@media (max-width: 900px) {
  .export-card { grid-template-columns: 1fr; padding: 22px 18px; text-align: left; }
  .export-art { margin-inline: auto; }
  .inline-options { display: grid; gap: 8px; }
  .inline-options label { min-height: 44px; display: flex; align-items: center; gap: 10px; }
  .primary-button { width: 100%; min-height: 48px; }
  .notice { align-items: flex-start; }
}
</style>
