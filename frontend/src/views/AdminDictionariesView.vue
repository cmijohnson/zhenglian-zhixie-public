<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import type { DictionaryItem, Tag } from '../types'
const dictionaries = ref<DictionaryItem[]>([]); const tags = ref<Tag[]>([]); const error = ref('')
const dictionaryTypes = [
  { value: 'SOURCE_CHANNEL', label: '客户来源' },
  { value: 'FOCUS_DIRECTION', label: '关注方向' },
  { value: 'INTERACTION_CHANNEL', label: '沟通渠道' },
  { value: 'SCHOOL', label: '学校' },
]
const dictionary = reactive({ type: 'SOURCE_CHANNEL', code: '', label: '', sortOrder: 100 })
const tag = reactive({ name: '', category: '业务', color: '#4F46E5' })
async function load() { [dictionaries.value, tags.value] = await Promise.all([api<DictionaryItem[]>('/api/v1/dictionaries'), api<Tag[]>('/api/v1/tags')]) }
async function createDictionary() { try { await api('/api/v1/dictionaries', { method: 'POST', body: jsonBody(dictionary) }); dictionary.code = ''; dictionary.label = ''; await load() } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '创建失败' } }
async function createTag() { try { await api('/api/v1/tags', { method: 'POST', body: jsonBody(tag) }); tag.name = ''; await load() } catch (reason) { error.value = reason instanceof ApiError ? reason.message : '创建失败' } }
onMounted(load)
</script>

<template><div class="page"><header class="page-header"><div><p class="eyebrow">CONTROLLED VOCABULARY</p><h1>字典与标签</h1><p class="muted">来源、关注方向、沟通渠道和学校统一维护，保证客户画像填写规范。</p></div></header><p v-if="error" class="notice error">{{ error }}</p><div class="admin-two-column"><section class="panel"><div class="panel-title"><div><p class="eyebrow">DICTIONARIES</p><h2>组织字典</h2></div></div><form class="form-grid four dictionary-form" @submit.prevent="createDictionary"><select v-model="dictionary.type"><option v-for="type in dictionaryTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select><input v-model.trim="dictionary.code" required placeholder="稳定代码，如 NJU"><input v-model.trim="dictionary.label" required placeholder="显示名称"><button class="soft-button">添加</button></form><div class="dictionary-groups"><article v-for="type in dictionaryTypes" :key="type.value"><strong>{{ type.label }}</strong><div class="tag-list"><i v-for="item in dictionaries.filter((row) => row.type === type.value)" :key="item.id">{{ item.label }}</i></div></article></div></section><section class="panel"><div class="panel-title"><div><p class="eyebrow">TAGS</p><h2>客户标签</h2></div></div><form class="form-grid tag-form" @submit.prevent="createTag"><input v-model.trim="tag.name" required placeholder="标签名称"><input v-model.trim="tag.category" required placeholder="分类"><input v-model="tag.color" type="color"><button class="soft-button">添加</button></form><div class="tag-list large"><i v-for="item in tags" :key="item.id" :style="{ borderColor: item.color }">{{ item.name }}<small>{{ item.category }}</small></i></div></section></div></div></template>

<style scoped>
@media (max-width: 900px) {
  .admin-two-column { grid-template-columns: 1fr; }
  .panel { padding: 18px 16px; }
  .dictionary-form, .tag-form { grid-template-columns: 1fr; }
  .dictionary-form button, .tag-form button { min-height: 48px; width: 100%; }
  .dictionary-groups { grid-template-columns: 1fr; }
  .tag-list { gap: 8px; }
  .tag-list i { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
}
</style>
