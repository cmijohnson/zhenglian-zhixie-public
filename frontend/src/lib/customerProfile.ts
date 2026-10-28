import type { Tag } from '../types'

export const customerGradeOptions = ['2024', '2025', '2026', '2027', '2028'] as const

export function normalizeCustomerGrade(value?: string): string {
  const grade = value?.trim() ?? ''
  const fullYear = grade.match(/^(20\d{2})(?:级)?$/)
  if (fullYear) return fullYear[1]
  const shortYear = grade.match(/^([23]\d)(?:级)?$/)
  if (shortYear) return `20${shortYear[1]}`
  return grade
}

export function formatCustomerGrade(value?: string): string {
  const grade = normalizeCustomerGrade(value)
  return /^20\d{2}$/.test(grade) ? `${grade}级` : grade
}

export function isGradeTag(tag: Pick<Tag, 'name' | 'category'>): boolean {
  const name = tag.name.trim()
  return tag.category.trim() === '年级'
    || /^(?:(?:19|20)\d{2}|\d{2})(?:级)?$/.test(name)
}
