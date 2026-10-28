<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import type { Customer } from '../types'
import StatusBadge from './StatusBadge.vue'
import { customerQualityCode, isCustomerQualityTag } from '../lib/customerQuality'

const props = defineProps<{ customer: Customer; to?: RouteLocationRaw }>()
const target = computed<RouteLocationRaw>(() => props.to ?? `/customers/${props.customer.id}`)
const qualityTag = computed(() => props.customer.tags.find(isCustomerQualityTag))
</script>

<template>
  <RouterLink :to="target" class="customer-row">
    <span class="avatar small">{{ customer.fullName.slice(0, 1) }}</span>
    <span class="customer-main"><strong>{{ customer.fullName }}</strong><small>{{ customer.school || '学校未填' }} · {{ customer.focusDirections.join(' / ') }}</small><small class="customer-people">负责人：{{ customer.ownerDisplayName || '未知' }} · 录入人：{{ customer.creatorDisplayName || '未知' }}</small></span>
    <span v-if="qualityTag" :class="['customer-quality-badge', `quality-${customerQualityCode(qualityTag).toLowerCase()}`]"><b>{{ customerQualityCode(qualityTag) }}</b>{{ qualityTag.name }}</span>
    <StatusBadge v-else value="未评级" tone="gray" />
    <StatusBadge :value="customer.intentionLevel + '意向'" :tone="customer.intentionLevel === '高' ? 'green' : 'blue'" />
    <StatusBadge :value="customer.leadReviewStatus === 'VALID' ? '有效线索' : customer.leadReviewStatus === 'INVALID' ? '无效线索' : '待审核'" :tone="customer.leadReviewStatus === 'VALID' ? 'green' : customer.leadReviewStatus === 'INVALID' ? 'red' : 'blue'" />
    <span class="muted">{{ customer.currentStatus }}</span>
    <span class="muted">{{ customer.nextFollowUpAt ? new Date(customer.nextFollowUpAt).toLocaleString() : '未设跟进' }}</span>
  </RouterLink>
</template>
