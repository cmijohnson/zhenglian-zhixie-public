<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, jsonBody } from '../lib/api'
import { customerGradeOptions, isGradeTag, normalizeCustomerGrade } from '../lib/customerProfile'
import { customerQualityCode, customerQualityDescription, isCustomerQualityTag } from '../lib/customerQuality'
import { localInput, toIso } from '../lib/format'
import {
  isCustomerDetailHistoryEntry,
  markTeamDataCustomerSaved,
  returnFromTeamDataEdit,
  safeTeamDataReturnTo,
} from '../lib/teamDataNavigation'
import type { Customer, DictionaryItem, Tag } from '../types'

const route = useRoute()
const router = useRouter()
const editId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const editing = computed(() => Boolean(editId.value))
const teamDataReturnTo = computed(() => safeTeamDataReturnTo(route.query.returnTo))
const qqReturnTo = computed(() => !editing.value && route.query.returnTo === '/qq-chat' ? '/qq-chat' : '')
const detailLocation = computed(() => ({
  path: `/customers/${editId.value}`,
  query: teamDataReturnTo.value ? { returnTo: teamDataReturnTo.value } : {},
}))
const existing = ref<Customer>()
const tags = ref<Tag[]>([])
const dictionaries = ref<DictionaryItem[]>([])
const busy = ref(false)
const error = ref('')
const addingSchool = ref(false)
const schoolBusy = ref(false)
const newSchool = ref('')
const schoolError = ref('')
const addingSource = ref(false)
const sourceBusy = ref(false)
const newSource = ref('')
const sourceError = ref('')
const mobileSections = reactive({ quality: true, friend: true })
const duplicate = ref<{
  blocking: boolean
  requiresConfirmation: boolean
  hiddenExactMatches: number
  matches: { customerId: string; fullName: string; reasons: string[] }[]
}>()
const form = reactive({
  fullName: '', nickname: '', phone: '', wechatId: '', qqId: '', school: '', major: '', grade: '', city: '',
  highSchool: '', sourceLocation: '',
  identityType: '在校生', demandSummary: '', intentionLevel: '中', urgencyLevel: '中', verificationStatus: '未核实',
  sourceChannel: '', currentStatus: '初次接触', ownerNotes: '', nextFollowUpAt: '', visibility: 'PRIVATE',
  friendAddedAt: '', friendTrustedAt: '', friendQualifiedAt: '',
  focusDirections: [] as string[], tagIds: [] as string[], duplicateOverrideReason: '',
})

const focusOptions = computed(() => dictionaries.value.filter((item) => item.type === 'FOCUS_DIRECTION'))
const regularTags = computed(() => tags.value.filter((tag) => !isCustomerQualityTag(tag)))
const existingQualityTag = computed(() => existing.value?.tags.find(isCustomerQualityTag))
const sources = computed(() => {
  const options = dictionaries.value.filter((item) => item.type === 'SOURCE_CHANNEL')
  if (form.sourceChannel && !options.some((item) => item.label === form.sourceChannel)) {
    return [...options, {
      id: existing.value?.sourceDictionaryId ?? `legacy-source-${form.sourceChannel}`,
      type: 'SOURCE_CHANNEL', code: 'LEGACY', label: form.sourceChannel, sortOrder: Number.MAX_SAFE_INTEGER,
    }]
  }
  return options
})
const legacyGrade = computed(() => form.grade && !customerGradeOptions.some((option) => option === form.grade) ? form.grade : '')
const schoolOptions = computed(() => {
  const options = dictionaries.value.filter((item) => item.type === 'SCHOOL')
  if (form.school && !options.some((item) => item.label === form.school)) {
    return [...options, { id: `legacy-school-${form.school}`, type: 'SCHOOL', code: 'LEGACY', label: form.school, sortOrder: Number.MAX_SAFE_INTEGER }]
  }
  return options
})
const canSave = computed(() => form.fullName && (form.phone || form.wechatId || form.qqId))
const friendStage = computed(() => {
  if (form.friendQualifiedAt) return '已筛选'
  if (form.friendTrustedAt) return '已信任'
  if (form.friendAddedAt) return '已添加'
  return '待添加'
})

onMounted(async () => {
  const [loadedTags, loadedDictionaries, loadedCustomer] = await Promise.all([
    api<Tag[]>('/api/v1/tags'),
    api<DictionaryItem[]>('/api/v1/dictionaries'),
    editId.value ? api<Customer>(`/api/v1/customers/${editId.value}`) : Promise.resolve(undefined),
  ])
  tags.value = loadedTags.filter((tag) => !isGradeTag(tag))
  dictionaries.value = loadedDictionaries
  if (loadedCustomer) {
    existing.value = loadedCustomer
    Object.assign(form, {
      fullName: loadedCustomer.fullName,
      nickname: loadedCustomer.nickname ?? '',
      phone: loadedCustomer.phone ?? '',
      wechatId: loadedCustomer.wechatId ?? '',
      qqId: loadedCustomer.qqId ?? '',
      school: loadedCustomer.school ?? '',
      major: loadedCustomer.major ?? '',
      grade: normalizeCustomerGrade(loadedCustomer.grade),
      city: loadedCustomer.city ?? '',
      highSchool: loadedCustomer.highSchool ?? '',
      sourceLocation: loadedCustomer.sourceLocation ?? '',
      identityType: loadedCustomer.identityType,
      demandSummary: loadedCustomer.demandSummary ?? '',
      intentionLevel: loadedCustomer.intentionLevel,
      urgencyLevel: loadedCustomer.urgencyLevel,
      verificationStatus: loadedCustomer.verificationStatus,
      sourceChannel: loadedCustomer.sourceChannel ?? '',
      currentStatus: loadedCustomer.currentStatus,
      ownerNotes: loadedCustomer.ownerNotes ?? '',
      nextFollowUpAt: loadedCustomer.nextFollowUpAt ? localInput(loadedCustomer.nextFollowUpAt) : '',
      friendAddedAt: loadedCustomer.friendAddedAt ? localInput(loadedCustomer.friendAddedAt) : '',
      friendTrustedAt: loadedCustomer.friendTrustedAt ? localInput(loadedCustomer.friendTrustedAt) : '',
      friendQualifiedAt: loadedCustomer.friendQualifiedAt ? localInput(loadedCustomer.friendQualifiedAt) : '',
      visibility: loadedCustomer.visibility,
      focusDirections: [...loadedCustomer.focusDirections],
      tagIds: loadedCustomer.tags.filter((tag) => !isCustomerQualityTag(tag) && tags.value.some((option) => option.id === tag.id)).map((tag) => tag.id),
    })
  } else if (qqReturnTo.value) {
    form.nickname = queryText('nickname', 160)
    form.qqId = queryText('qqId', 120)
    const requestedSource = queryText('sourceChannel', 120)
    if (requestedSource && dictionaries.value.some((item) => item.type === 'SOURCE_CHANNEL' && item.label === requestedSource)) {
      form.sourceChannel = requestedSource
    }
  }
})

function queryText(name: string, maxLength: number) {
  const value = route.query[name]
  return typeof value === 'string' ? value.trim().slice(0, maxLength) : ''
}

function body() {
  const selectedSource = dictionaries.value.find(
    (item) => item.type === 'SOURCE_CHANNEL' && item.label === form.sourceChannel,
  )
  const existingSourceDictionaryId = form.sourceChannel === existing.value?.sourceChannel
    ? existing.value?.sourceDictionaryId
    : undefined
  return {
    ...form,
    grade: normalizeCustomerGrade(form.grade),
    tagIds: form.tagIds.filter((id) => tags.value.some((tag) => tag.id === id)),
    sourceDictionaryId: selectedSource?.id ?? existingSourceDictionaryId,
    nextFollowUpAt: toIso(form.nextFollowUpAt),
    friendAddedAt: toIso(form.friendAddedAt),
    friendTrustedAt: toIso(form.friendTrustedAt),
    friendQualifiedAt: toIso(form.friendQualifiedAt),
    focusDirections: form.focusDirections.length ? form.focusDirections : ['暂不明确'],
    ownerMembershipId: existing.value?.ownerMembershipId,
    teamId: existing.value?.teamId,
    expectedVersion: existing.value?.version,
  }
}

function markFriendStage(stage: 'ADDED' | 'TRUSTED' | 'QUALIFIED') {
  const now = localInput()
  if (!form.friendAddedAt) form.friendAddedAt = now
  if ((stage === 'TRUSTED' || stage === 'QUALIFIED') && !form.friendTrustedAt) form.friendTrustedAt = now
  if (stage === 'QUALIFIED' && !form.friendQualifiedAt) form.friendQualifiedAt = now
}

async function checkDuplicate() {
  if (!form.fullName || !(form.major || form.phone || form.wechatId || form.qqId)) {
    duplicate.value = undefined
    form.duplicateOverrideReason = ''
    return
  }
  duplicate.value = await api('/api/v1/customers/duplicate-check', {
    method: 'POST',
    body: jsonBody({
      fullName: form.fullName,
      major: form.major,
      phone: form.phone,
      wechatId: form.wechatId,
      qqId: form.qqId,
      excludeCustomerId: editId.value || undefined,
    }),
  })
  if (!duplicate.value?.requiresConfirmation) form.duplicateOverrideReason = ''
}

async function addSchool() {
  const label = newSchool.value.trim()
  if (!label) { schoolError.value = '请输入学校名称'; return }
  schoolBusy.value = true
  schoolError.value = ''
  try {
    const created = await api<DictionaryItem>('/api/v1/dictionaries/schools', {
      method: 'POST', body: jsonBody({ label }),
    })
    dictionaries.value.push(created)
    form.school = created.label
    newSchool.value = ''
    addingSchool.value = false
  } catch (reason) {
    schoolError.value = reason instanceof ApiError ? reason.message : '添加学校失败，请稍后重试'
  } finally { schoolBusy.value = false }
}

async function addSource() {
  const label = newSource.value.trim()
  if (!label) { sourceError.value = '请输入来源名称'; return }
  sourceBusy.value = true
  sourceError.value = ''
  try {
    const created = await api<DictionaryItem>('/api/v1/dictionaries/sources', {
      method: 'POST', body: jsonBody({ label }),
    })
    dictionaries.value.push(created)
    form.sourceChannel = created.label
    newSource.value = ''
    addingSource.value = false
  } catch (reason) {
    sourceError.value = reason instanceof ApiError ? reason.message : '添加来源失败，请稍后重试'
  } finally { sourceBusy.value = false }
}

async function cancel() {
  if (!editing.value) {
    if (qqReturnTo.value) {
      await router.push(qqReturnTo.value)
      return
    }
    router.back()
    return
  }
  if (isCustomerDetailHistoryEntry(router.options.history.state.back, editId.value)) {
    router.back()
    return
  }
  await router.replace(detailLocation.value)
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    await checkDuplicate()
    if (duplicate.value?.blocking) { error.value = '联系方式已存在，不能重复建档。'; return }
    if (duplicate.value?.requiresConfirmation && !form.duplicateOverrideReason) {
      error.value = `发现姓名与专业一致的客户，请核对现有档案并填写继续${editing.value ? '修改' : '创建'}原因。`; return
    }
    const saved = await api<{ id: string }>(editing.value ? `/api/v1/customers/${editId.value}` : '/api/v1/customers', {
      method: editing.value ? 'PATCH' : 'POST', body: jsonBody(body()),
    })
    if (editing.value && teamDataReturnTo.value) {
      markTeamDataCustomerSaved(teamDataReturnTo.value, form.fullName)
      returnFromTeamDataEdit(router, saved.id, teamDataReturnTo.value)
      return
    }
    if (!editing.value && qqReturnTo.value) {
      await router.push({ path: qqReturnTo.value, query: { customerId: saved.id, resume: '1' } })
      return
    }
    await router.push(`/customers/${saved.id}`)
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '保存失败，请稍后重试'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="page narrow-page">
    <header class="page-header"><div><p class="eyebrow">{{ editing ? 'CUSTOMER PROFILE' : 'QUICK CAPTURE' }}</p><h1>{{ editing ? '编辑客户画像' : '新建客户' }}</h1><p class="muted">{{ editing ? '更新客户基础信息、状态、标签和跟进安排。' : '先收集最低必要信息，其他内容可稍后补充。' }}</p></div><button class="ghost-button" @click="cancel">返回</button></header>
    <form class="form-card panel" @submit.prevent="save">
      <section>
        <div class="section-heading"><span>01</span><div><h2>基础信息</h2><p>姓名与一种联系方式为必填。</p></div></div>
        <div class="form-grid two">
          <label>姓名 *<input v-model.trim="form.fullName" required maxlength="160" data-testid="customer-name-input" placeholder="客户真实姓名" @blur="checkDuplicate"></label>
          <label>昵称<input v-model.trim="form.nickname" maxlength="160" placeholder="可选"></label>
          <label>手机号<input v-model.trim="form.phone" maxlength="80" inputmode="tel" data-testid="customer-phone-input" placeholder="手机 / 固话" @blur="checkDuplicate"></label>
          <label>微信号<input v-model.trim="form.wechatId" maxlength="120" placeholder="微信 ID" @blur="checkDuplicate"></label>
          <label>QQ 号<input v-model.trim="form.qqId" maxlength="120" inputmode="numeric" placeholder="QQ ID" @blur="checkDuplicate"></label>
          <label>专业<input v-model.trim="form.major" maxlength="160" data-testid="customer-major-input" placeholder="用于姓名＋专业联合查重" @blur="checkDuplicate"></label>
          <div class="dictionary-create-field source-field">
            <label>来源<select v-model="form.sourceChannel" data-testid="source-select"><option value="">请选择来源</option><option v-for="item in sources" :key="item.id" :value="item.label">{{ item.label }}</option></select></label>
            <button type="button" class="text-action" data-testid="add-source-toggle" :aria-expanded="addingSource" @click="addingSource = !addingSource; sourceError = ''">{{ addingSource ? '取消添加' : '＋ 添加来源' }}</button>
            <div v-if="addingSource" class="dictionary-create">
              <input v-model.trim="newSource" maxlength="120" data-testid="new-source-input" placeholder="输入新的客户来源" @keydown.enter.prevent="addSource">
              <button type="button" class="soft-button" data-testid="add-source-submit" :disabled="sourceBusy" @click="addSource">{{ sourceBusy ? '添加中…' : '加入选项' }}</button>
            </div>
            <small v-if="sourceError" class="form-error">{{ sourceError }}</small>
          </div>
        </div>
        <p v-if="!form.phone && !form.wechatId && !form.qqId" class="field-hint">手机号、微信号、QQ 号至少填写一个。</p>
      </section>
      <section v-if="duplicate && (duplicate.matches.length || duplicate.hiddenExactMatches)" class="duplicate-box" data-testid="customer-conflict-warning">
        <strong>{{ duplicate.blocking ? '发现相同联系方式，不能重复建档' : '发现姓名与专业一致的客户' }}</strong>
        <p v-for="match in duplicate.matches" :key="match.customerId">{{ match.fullName }} · {{ match.reasons.join('、') }}</p>
        <p v-if="duplicate.hiddenExactMatches">另有 {{ duplicate.hiddenExactMatches }} 条无权查看的重复或冲突档案，请先向管理员核实。</p>
        <label v-if="duplicate.requiresConfirmation && !duplicate.blocking">确认仍需保存的原因 *<textarea v-model.trim="form.duplicateOverrideReason" rows="2" maxlength="500" data-testid="duplicate-override-reason" placeholder="例如：已核对学号/学校，确认是同名同专业的不同学生"></textarea></label>
      </section>
      <section class="customer-quality-section" data-testid="customer-quality-picker">
        <div class="section-heading"><span>02</span><div><h2>客户质量</h2><p>A/B/C 只在客户详情中根据沟通事实进行结构化认定。</p></div><button type="button" class="mobile-section-toggle" :aria-expanded="mobileSections.quality" @click="mobileSections.quality = !mobileSections.quality">{{ mobileSections.quality ? '收起' : '展开' }}</button></div>
        <div :class="['mobile-section-body', { collapsed: !mobileSections.quality }]">
        <div class="customer-quality-field">
          <div :class="['customer-quality-readonly', existingQualityTag ? `quality-${customerQualityCode(existingQualityTag).toLowerCase()}` : 'quality-unset']">
            <b>{{ existingQualityTag ? customerQualityCode(existingQualityTag) : '—' }}</b>
            <span><strong>{{ existingQualityTag?.name || '未评级' }}</strong><small>{{ existingQualityTag ? customerQualityDescription(existingQualityTag) : editing ? '保存画像后可在客户详情补充分级依据' : '建档并记录真实沟通后再进行分级' }}</small></span>
          </div>
          <p class="field-hint">编辑画像不会修改现有等级，也不会删除历史 A/B/C 标签。</p>
        </div>
        </div>
      </section>
      <section class="friend-funnel-editor" data-testid="friend-funnel-editor">
        <div class="section-heading">
          <span>03</span>
          <div><h2>好友转化漏斗</h2><p>当前阶段：{{ friendStage }}。按真实发生时间记录，数据将进入领导大屏和个人排行榜。</p></div>
          <button type="button" class="mobile-section-toggle" :aria-expanded="mobileSections.friend" @click="mobileSections.friend = !mobileSections.friend">{{ mobileSections.friend ? '收起' : '展开' }}</button>
        </div>
        <div :class="['mobile-section-body', { collapsed: !mobileSections.friend }]">
        <div class="friend-stage-strip">
          <button type="button" :class="{ reached: form.friendAddedAt }" @click="markFriendStage('ADDED')"><b>01</b><span>添加好友</span><small>{{ form.friendAddedAt ? '已记录' : '点击标记' }}</small></button>
          <i></i>
          <button type="button" :class="{ reached: form.friendTrustedAt }" @click="markFriendStage('TRUSTED')"><b>02</b><span>建立信任</span><small>{{ form.friendTrustedAt ? '已记录' : '点击标记' }}</small></button>
          <i></i>
          <button type="button" :class="{ reached: form.friendQualifiedAt }" @click="markFriendStage('QUALIFIED')"><b>03</b><span>筛选确认</span><small>{{ form.friendQualifiedAt ? '已记录' : '点击标记' }}</small></button>
        </div>
        <div class="form-grid three friend-stage-times">
          <label>添加好友时间<input v-model="form.friendAddedAt" type="datetime-local"><small>确认已经互加好友</small></label>
          <label>建立信任时间<input v-model="form.friendTrustedAt" type="datetime-local" :disabled="!form.friendAddedAt"><small>完成有效沟通并建立基本信任</small></label>
          <label>筛选确认时间<input v-model="form.friendQualifiedAt" type="datetime-local" :disabled="!form.friendTrustedAt"><small>完成需求筛选，进入有效跟进池</small></label>
        </div>
        <p class="field-hint">后续里程碑不能早于前一阶段；如需纠正，请从最后一个阶段开始清空。</p>
        </div>
      </section>
      <details :open="editing">
        <summary><span>04</span><div><strong>补充客户画像</strong><small>学校、需求、意向、标签和下次跟进</small></div></summary>
        <div class="details-body form-grid two">
          <div class="dictionary-create-field school-field">
            <label>学校<select v-model="form.school" data-testid="school-select"><option value="">请选择学校</option><option v-for="item in schoolOptions" :key="item.id" :value="item.label">{{ item.label }}</option></select></label>
            <button type="button" class="text-action" data-testid="add-school-toggle" :aria-expanded="addingSchool" @click="addingSchool = !addingSchool; schoolError = ''">{{ addingSchool ? '取消添加' : '＋ 添加学校' }}</button>
            <div v-if="addingSchool" class="dictionary-create school-create">
              <input v-model.trim="newSchool" maxlength="120" data-testid="new-school-input" placeholder="输入完整学校名称" @keydown.enter.prevent="addSchool">
              <button type="button" class="soft-button" data-testid="add-school-submit" :disabled="schoolBusy" @click="addSchool">{{ schoolBusy ? '添加中…' : '加入选项' }}</button>
            </div>
            <small v-if="schoolError" class="form-error">{{ schoolError }}</small>
          </div>
          <label>年级<select v-model="form.grade" data-testid="grade-select"><option value="">请选择年级</option><option v-for="year in customerGradeOptions" :key="year" :value="year">{{ year }}级</option><option v-if="legacyGrade" :value="legacyGrade">历史值：{{ legacyGrade }}（请改选）</option></select><small class="field-hint">统一按入学年份保存，年级不再作为客户标签。</small></label><label>所在城市<input v-model.trim="form.city"></label>
          <label>高中学校<input v-model.trim="form.highSchool" maxlength="200" placeholder="学生入学前所在高中"></label>
          <label>学生生源地<input v-model.trim="form.sourceLocation" maxlength="200" placeholder="例如：示例省示例市"></label>
          <label>身份类型<select v-model="form.identityType"><option v-for="item in ['新生','转专业','在校生','毕业生','家长代问','其他']" :key="item">{{ item }}</option></select></label>
          <label>当前状态<select v-model="form.currentStatus"><option v-for="item in ['初次接触','待核实','已沟通','跟进中','高意向','待决定','已转化','暂缓','无效']" :key="item">{{ item }}</option></select></label>
          <label>报名意向<select v-model="form.intentionLevel"><option>高</option><option>中</option><option>低</option></select></label>
          <label>紧急程度<select v-model="form.urgencyLevel"><option>高</option><option>中</option><option>低</option></select></label>
          <label>核实状态<select v-model="form.verificationStatus"><option>未核实</option><option>部分待确认</option><option>已核实</option></select></label>
          <label>下次跟进<input v-model="form.nextFollowUpAt" type="datetime-local"></label>
          <label class="span-2">关注方向<div class="chip-picker"><label v-for="item in focusOptions" :key="item.id"><input v-model="form.focusDirections" type="checkbox" :value="item.label"><span>{{ item.label }}</span></label></div></label>
          <label class="span-2">其他客户标签<div class="chip-picker" data-testid="customer-tag-picker"><label v-for="tag in regularTags" :key="tag.id"><input v-model="form.tagIds" type="checkbox" :value="tag.id"><span>{{ tag.name }}</span></label><small v-if="!regularTags.length" class="field-hint">暂无其他标签</small></div></label>
          <label class="span-2">需求摘要<textarea v-model.trim="form.demandSummary" rows="3" maxlength="4000"></textarea></label>
          <label class="span-2">内部备注<textarea v-model.trim="form.ownerNotes" rows="3" maxlength="10000"></textarea></label>
        </div>
      </details>
      <p v-if="error" class="form-error">{{ error }}</p>
      <footer class="form-actions"><button type="button" class="ghost-button" @click="cancel">取消</button><button class="primary-button" :disabled="busy || !canSave">{{ busy ? '保存中…' : editing ? '保存修改' : '创建客户' }}</button></footer>
    </form>
  </div>
</template>

<style scoped>
.mobile-section-toggle{display:none}
.customer-quality-readonly{display:flex;align-items:center;gap:14px;padding:16px;border:1px solid #dfe6f2;border-radius:16px;background:#f7f9fd}.customer-quality-readonly>b{display:grid;place-items:center;flex:0 0 52px;width:52px;height:52px;border-radius:15px;background:#edf1f8;color:#516078;font-size:22px}.customer-quality-readonly>span{display:grid;gap:4px}.customer-quality-readonly small{color:#6f7d95;line-height:1.45}.customer-quality-readonly.quality-a>b{background:#ffe9e4;color:#bd3f2e}.customer-quality-readonly.quality-b>b{background:#fff2d8;color:#9b6810}.customer-quality-readonly.quality-c>b{background:#eef1f5;color:#637087}
@media(max-width:760px){
  .narrow-page{padding-inline:14px}
  .form-card{padding:18px 16px 8px;border-radius:20px}
  .form-card>section{padding-bottom:22px}
  .form-card>section+section,.form-card>details{padding-top:22px}
  .form-grid.two,.form-grid.three{grid-template-columns:1fr}
  .form-card input,.form-card select,.form-card textarea{font-size:16px}
  .form-card input,.form-card select{min-height:46px}
  .section-heading{position:relative;align-items:flex-start;padding-right:54px}
  .section-heading>div{min-width:0}
  .section-heading h2{font-size:19px}
  .section-heading p{line-height:1.55}
  .mobile-section-toggle{position:absolute;top:0;right:0;display:inline-flex;min-width:48px;min-height:40px;align-items:center;justify-content:center;border:0;border-radius:10px;background:#eef3ff;color:#3159c7;font-weight:700}
  .mobile-section-body.collapsed{display:none}
  .customer-quality-picker{grid-template-columns:1fr}
  .customer-quality-picker label{min-height:68px}
  .friend-stage-strip{display:grid;grid-template-columns:1fr;gap:8px}
  .friend-stage-strip>i{width:2px;height:12px;margin:auto}
  .friend-stage-strip button{min-height:62px}
  .friend-stage-times{margin-top:14px}
  .dictionary-create,.dictionary-create.school-create{grid-template-columns:1fr}
  .dictionary-create .soft-button{min-height:44px;width:100%}
  .chip-picker{max-height:240px;overflow-y:auto;overscroll-behavior:contain}
  .form-actions{position:sticky;z-index:4;bottom:calc(64px + env(safe-area-inset-bottom));display:grid;grid-template-columns:1fr 1.25fr;margin:22px -16px 0;padding:12px 16px calc(12px + env(safe-area-inset-bottom));border-top:1px solid #e3e8f2;background:#fffffff2;backdrop-filter:blur(16px)}
  .form-actions button{min-height:48px;width:100%}
  .duplicate-box{scroll-margin-top:16px}
}
</style>
