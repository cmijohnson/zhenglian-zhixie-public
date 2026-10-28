import type { Tag } from '../types'

export const CUSTOMER_QUALITY_CATEGORY = '客户质量'

const descriptions: Record<string, string> = {
  A类用户: '高质量客户，有急迫需求且明确需要此方向产品',
  B类用户: '对产品有一定兴趣，但目前阶段暂无迫切需求',
  C类用户: '对产品需求很低，或当前购买、支付能力不足',
}

export function isCustomerQualityTag(tag: Pick<Tag, 'category'>) {
  return tag.category === CUSTOMER_QUALITY_CATEGORY
}

export function customerQualityDescription(tag: Pick<Tag, 'name'>) {
  return descriptions[tag.name] ?? ''
}

export function customerQualityCode(tag?: Pick<Tag, 'name'>) {
  return tag?.name.match(/^[ABC]/)?.[0] ?? '—'
}
