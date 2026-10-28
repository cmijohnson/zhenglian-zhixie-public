<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, jsonBody } from '../lib/api'
import type {
  LeadershipEventType,
  LeadershipSceneId,
  LeadershipScreenSettings,
  MotionIntensity,
} from '../lib/leadershipAnimation'

const sceneMeta: Record<LeadershipSceneId, { name: string; description: string }> = {
  time: { name: '时间星环', description: '巨型时钟、日期与数据范围' },
  culture: { name: '文化宣言', description: '公司标语与大屏公告轮播' },
  briefing: { name: '今日战报', description: '今日新增、添加、信任、筛选' },
  trend: { name: '增长轨迹', description: '最近 7 天经营趋势' },
  champions: { name: '榜首光柱', description: '五维排行榜冠军舞台' },
  funnel: { name: '转化能量流', description: '客户到筛选的全链路转化' },
  mission: { name: '任务与公告', description: '完成率、重点任务与通知' },
  followUpRisk: { name: 'A 类跟进风险', description: '超时客户数与归属人动态分布' },
  schoolComparison: { name: '核心院校对比', description: '重点年级示例大学与示例科技大学对比' },
}
const eventMeta: Record<LeadershipEventType, { name: string; description: string }> = {
  zeroBreakthrough: { name: '破零时刻', description: '关键指标从 0 变为正数' },
  customerAdded: { name: '新客户接入', description: '每次新增客户时展示数量、姓名与负责人' },
  customerUpdated: { name: '客户档案更新', description: '客户资料、归属或跟进状态变化时播报' },
  milestone: { name: '数字跃迁', description: '客户或转化跨过整数档' },
  trendSurge: { name: '趋势升空', description: '今日表现高于近 7 日均值 30%' },
  conversionStage: { name: '转化贯通', description: '转化率跨过 25% / 50% / 75%' },
  taskPublished: { name: '新任务发布', description: '新任务下达后展示标题、发布人和截止时间' },
  taskMilestone: { name: '任务攻顶', description: '任务完成率跨越关键节点' },
  rankingChange: { name: '榜单换位', description: '冠军变化或成员进入前三' },
  teamMomentum: { name: '团队共振', description: '至少 3 名成员同步增长' },
}

const settings = ref<LeadershipScreenSettings>()
const form = reactive({
  animationsEnabled: true,
  standbyEnabled: true,
  idleTimeoutMinutes: 20,
  sceneDurationSeconds: 12,
  eventDurationSeconds: 7,
  motionIntensity: 'CINEMATIC' as MotionIntensity,
  sceneOrder: [] as LeadershipSceneId[],
  enabledScenes: [] as LeadershipSceneId[],
  enabledEvents: [] as LeadershipEventType[],
  featuredGrade: '2026',
})
const busy = ref(false)
const error = ref('')
const success = ref('')
const enabledSceneSet = computed(() => new Set(form.enabledScenes))
const enabledEventSet = computed(() => new Set(form.enabledEvents))
const eventCount = Object.keys(eventMeta).length

function apply(value: LeadershipScreenSettings) {
  settings.value = value
  form.animationsEnabled = value.animationsEnabled
  form.standbyEnabled = value.standbyEnabled
  form.idleTimeoutMinutes = value.idleTimeoutMinutes
  form.sceneDurationSeconds = value.sceneDurationSeconds
  form.eventDurationSeconds = value.eventDurationSeconds
  form.motionIntensity = value.motionIntensity
  form.sceneOrder = [...value.sceneOrder]
  form.enabledScenes = [...value.enabledScenes]
  form.enabledEvents = [...value.enabledEvents]
  form.featuredGrade = value.featuredGrade
}

async function load() {
  error.value = ''
  try {
    apply(await api<LeadershipScreenSettings>('/api/v1/admin/leadership-screen-settings'))
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '大屏配置加载失败'
  }
}

async function save() {
  if (!settings.value) return
  if (!form.enabledScenes.length) {
    error.value = '至少启用一个待机场景'
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    apply(await api<LeadershipScreenSettings>('/api/v1/admin/leadership-screen-settings', {
      method: 'PUT',
      body: jsonBody({
        animationsEnabled: form.animationsEnabled,
        standbyEnabled: form.standbyEnabled,
        idleTimeoutMinutes: form.idleTimeoutMinutes,
        sceneDurationSeconds: form.sceneDurationSeconds,
        eventDurationSeconds: form.eventDurationSeconds,
        motionIntensity: form.motionIntensity,
        sceneOrder: form.sceneOrder,
        enabledScenes: form.enabledScenes,
        enabledEvents: form.enabledEvents,
        featuredGrade: form.featuredGrade,
        expectedVersion: settings.value.version,
      }),
    }))
    success.value = '大屏设置已保存，电视大屏下次刷新后自动生效'
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '大屏配置保存失败'
  } finally {
    busy.value = false
  }
}

function moveScene(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= form.sceneOrder.length) return
  const reordered = [...form.sceneOrder]
  ;[reordered[index], reordered[target]] = [reordered[target], reordered[index]]
  form.sceneOrder = reordered
}

function toggleScene(scene: LeadershipSceneId) {
  form.enabledScenes = enabledSceneSet.value.has(scene)
    ? form.enabledScenes.filter((item) => item !== scene)
    : [...form.enabledScenes, scene]
}

function toggleEvent(event: LeadershipEventType) {
  form.enabledEvents = enabledEventSet.value.has(event)
    ? form.enabledEvents.filter((item) => item !== event)
    : [...form.enabledEvents, event]
}

function preview(query: string) {
  window.open(`/leadership-screen?${query}`, 'yingge-leadership-preview', 'noopener')
}

onMounted(load)
</script>

<template>
  <div class="page leadership-settings-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">LEADERSHIP SCREEN DIRECTOR</p>
        <h1>大屏设置</h1>
        <p class="muted">单独管理电视大屏的待机轮播、实时事件动画和动效强度，不影响普通后台页面。</p>
      </div>
      <div class="leadership-settings-actions">
        <button class="ghost-button" type="button" @click="preview('preview=standby')">预览待机轮播</button>
        <a class="primary-button" href="/leadership-screen" target="_blank" rel="noopener">打开电视大屏</a>
      </div>
    </header>

    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="success" class="notice success">{{ success }}</p>

    <form v-if="settings" class="leadership-settings-form" @submit.prevent="save">
      <section class="panel screen-setting-overview">
        <div class="setting-toggle-grid">
          <label class="toggle-row">
            <input v-model="form.animationsEnabled" type="checkbox">
            <span><strong>启用全屏强动效</strong><small>关闭后不进入待机，也不播放实时事件庆典。</small></span>
          </label>
          <label class="toggle-row">
            <input v-model="form.standbyEnabled" type="checkbox" :disabled="!form.animationsEnabled">
            <span><strong>启用智能待机</strong><small>业务数据长时间无变化后自动进入品牌信息秀。</small></span>
          </label>
        </div>
        <div class="screen-number-grid">
          <label>无变化后进入待机
            <span><input v-model.number="form.idleTimeoutMinutes" type="number" min="5" max="120" required> 分钟</span>
          </label>
          <label>每个待机场景
            <span><input v-model.number="form.sceneDurationSeconds" type="number" min="5" max="60" required> 秒</span>
          </label>
          <label>实时事件持续
            <span><input v-model.number="form.eventDurationSeconds" type="number" min="3" max="15" required> 秒</span>
          </label>
          <label>动画强度
            <select v-model="form.motionIntensity">
              <option value="SUBTLE">轻柔 · 低性能设备</option>
              <option value="CINEMATIC">影院 · 推荐</option>
              <option value="STRONG">强烈 · 展厅模式</option>
            </select>
          </label>
          <label>重点年级
            <span><input v-model="form.featuredGrade" type="text" inputmode="numeric" pattern="(19|20)[0-9]{2}" maxlength="4" required> 级</span>
          </label>
        </div>
      </section>

      <div class="leadership-settings-columns">
        <section class="panel scene-settings-card">
          <header><div><p class="eyebrow">STANDBY PLAYLIST</p><h2>待机轮播场景</h2></div><span>{{ form.enabledScenes.length }} / {{ form.sceneOrder.length }} 已启用</span></header>
          <p class="muted">上下调整实际播放顺序；关闭无业务数据的场景时，大屏会自动轮播剩余项目。</p>
          <div class="scene-order-list">
            <article v-for="(scene, index) in form.sceneOrder" :key="scene" :class="{ disabled: !enabledSceneSet.has(scene) }">
              <button type="button" class="scene-enable" :aria-pressed="enabledSceneSet.has(scene)" @click="toggleScene(scene)">
                <i>{{ enabledSceneSet.has(scene) ? '✓' : '' }}</i>
                <span><strong>{{ sceneMeta[scene].name }}</strong><small>{{ sceneMeta[scene].description }}</small></span>
              </button>
              <div>
                <button type="button" title="上移" :disabled="index === 0" @click="moveScene(index, -1)">↑</button>
                <button type="button" title="下移" :disabled="index === form.sceneOrder.length - 1" @click="moveScene(index, 1)">↓</button>
              </div>
            </article>
          </div>
          <p class="screen-message-tip">文化宣言与任务播报会读取“公告中心”中勾选了“在领导大屏轮播”的已发布公告。</p>
        </section>

        <section class="panel event-settings-card">
          <header><div><p class="eyebrow">LIVE EVENT DIRECTOR</p><h2>实时事件动画</h2></div><span>{{ form.enabledEvents.length }} / {{ eventCount }} 已启用</span></header>
          <p class="muted">普通数字增长仍只做局部翻牌，以下重要事件才接管全屏。</p>
          <div class="event-setting-grid">
            <article v-for="(_, event) in eventMeta" :key="event" :class="{ disabled: !enabledEventSet.has(event) }">
              <button type="button" class="event-enable" :aria-pressed="enabledEventSet.has(event)" @click="toggleEvent(event)">
                <i>{{ enabledEventSet.has(event) ? '✓' : '' }}</i>
                <span><strong>{{ eventMeta[event].name }}</strong><small>{{ eventMeta[event].description }}</small></span>
              </button>
              <button type="button" class="event-preview" @click="preview(`previewEvent=${event}`)">预览</button>
            </article>
          </div>
        </section>
      </div>

      <footer class="screen-settings-savebar">
        <span>配置版本 {{ settings.version }} · 大屏保持每 60 秒拉取最新数据与设置</span>
        <button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存并立即生效' }}</button>
      </footer>
    </form>
  </div>
</template>
