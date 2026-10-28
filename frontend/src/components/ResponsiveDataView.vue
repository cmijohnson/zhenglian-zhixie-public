<script setup lang="ts">
export interface ResponsiveColumn {
  key: string
  label: string
  mobileLabel?: string
  mobileRole?: 'primary' | 'secondary' | 'detail' | 'actions'
  hiddenOnMobile?: boolean
  formatter?: (value: unknown, item: Record<string, unknown>) => unknown
}

const props = withDefaults(defineProps<{
  items: Record<string, unknown>[]
  columns: ResponsiveColumn[]
  rowKey?: string | ((item: Record<string, unknown>, index: number) => string | number)
  emptyText?: string
  caption?: string
}>(), {
  rowKey: 'id',
  emptyText: '暂无数据',
  caption: '',
})

function itemKey(item: Record<string, unknown>, index: number) {
  if (typeof props.rowKey === 'function') return props.rowKey(item, index)
  return String(item[props.rowKey] ?? index)
}

function valueFor(item: Record<string, unknown>, column: ResponsiveColumn) {
  const value = item[column.key]
  return column.formatter ? column.formatter(value, item) : value
}

function displayValue(item: Record<string, unknown>, column: ResponsiveColumn) {
  const value = valueFor(item, column)
  if (value === undefined || value === null || value === '') return '—'
  return String(value)
}
</script>

<template>
  <div class="responsive-data-view">
    <div class="responsive-data-desktop">
      <table>
        <caption v-if="caption">{{ caption }}</caption>
        <thead><tr><th v-for="column in columns" :key="column.key">{{ column.label }}</th></tr></thead>
        <tbody>
          <tr v-for="(item, index) in items" :key="itemKey(item, index)">
            <td v-for="column in columns" :key="column.key" :data-label="column.mobileLabel ?? column.label">
              <slot :name="`cell-${column.key}`" :item="item" :value="valueFor(item, column)">{{ displayValue(item, column) }}</slot>
            </td>
          </tr>
          <tr v-if="!items.length"><td :colspan="columns.length" class="responsive-data-empty">{{ emptyText }}</td></tr>
        </tbody>
      </table>
    </div>

    <div class="responsive-data-mobile">
      <article v-for="(item, index) in items" :key="itemKey(item, index)">
        <div
          v-for="column in columns.filter((entry) => !entry.hiddenOnMobile)"
          :key="column.key"
          :class="[`is-${column.mobileRole ?? 'detail'}`]"
        >
          <span v-if="column.mobileRole !== 'primary' && column.mobileRole !== 'actions'">{{ column.mobileLabel ?? column.label }}</span>
          <section>
            <slot :name="`cell-${column.key}`" :item="item" :value="valueFor(item, column)">{{ displayValue(item, column) }}</slot>
          </section>
        </div>
      </article>
      <div v-if="!items.length" class="responsive-data-empty">{{ emptyText }}</div>
    </div>
  </div>
</template>

<style scoped>
.responsive-data-view{min-width:0}.responsive-data-desktop{overflow:auto}.responsive-data-desktop table{width:100%}.responsive-data-mobile{display:none}.responsive-data-empty{padding:30px!important;color:#8a95a7;text-align:center!important}
@media(max-width:900px){.responsive-data-desktop{display:none}.responsive-data-mobile{display:grid;gap:11px}.responsive-data-mobile>article{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:11px 14px;padding:16px;border:1px solid #e0e6f1;border-radius:17px;background:#fff;box-shadow:0 8px 26px rgba(24,43,84,.055)}.responsive-data-mobile article>div{min-width:0}.responsive-data-mobile article>div>span{display:block;margin-bottom:4px;color:#8a94a7;font-size:10px}.responsive-data-mobile article section{min-width:0;overflow-wrap:anywhere;color:#43516a;font-size:12px}.responsive-data-mobile article .is-primary{grid-column:1/-1;color:#17213a;font-size:15px;font-weight:750}.responsive-data-mobile article .is-secondary{color:#526078}.responsive-data-mobile article .is-actions{display:flex;grid-column:1/-1;flex-wrap:wrap;gap:8px;padding-top:3px}.responsive-data-mobile article .is-actions section{display:flex;width:100%;flex-wrap:wrap;gap:8px}.responsive-data-mobile article .is-actions :deep(button),.responsive-data-mobile article .is-actions :deep(a){min-height:44px;flex:1}.responsive-data-mobile>.responsive-data-empty{border:1px dashed #dce2ed;border-radius:16px;background:#fff}}
</style>
