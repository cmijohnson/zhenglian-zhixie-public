<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="daily-execution-nav" aria-label="每日执行分区">
    <div class="daily-execution-copy">
      <p class="eyebrow">DAILY EXECUTION</p>
      <strong>每日执行</strong>
      <small>汇报进展与内容发布分区管理</small>
    </div>
    <nav>
      <RouterLink to="/performance">
        <span>◆</span>
        <span><strong>绩效中心</strong><small>申报、积分、活动与排行</small></span>
      </RouterLink>
      <RouterLink to="/daily-report">
        <span>≡</span>
        <span><strong>每日汇报</strong><small>线索、沟通、问题与计划</small></span>
      </RouterLink>
      <RouterLink to="/content-tasks">
        <span>◈</span>
        <span><strong>内容任务</strong><small>发布、数据、审核与进度</small></span>
      </RouterLink>
    </nav>
  </section>
</template>
