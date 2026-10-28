<script setup lang="ts">
import { computed } from 'vue'
import type {
  LeadershipAnimationEvent,
  LeadershipDashboardData,
  LeadershipSceneId,
  MotionIntensity,
} from '../lib/leadershipAnimation'
import { rankingValue } from '../lib/leadershipAnimation'
import LeadershipParticleCanvas from './LeadershipParticleCanvas.vue'

const props = defineProps<{
  data: LeadershipDashboardData
  now: Date
  scopeName: string
  standby: boolean
  sceneId: LeadershipSceneId
  scenePosition: number
  sceneCount: number
  sceneCycle: number
  motionIntensity: MotionIntensity
  event?: LeadershipAnimationEvent
}>()
defineEmits<{ skip: [] }>()

const defaultMessages = [
  { id: 'default-1', title: '连接客户', content: '连接客户 · 沉淀信任 · 持续成长', priority: 'NORMAL' },
  { id: 'default-2', title: '目标一致', content: '目标一致 · 行动一致 · 结果一致', priority: 'NORMAL' },
  { id: 'default-3', title: '认真跟进', content: '让每一次认真跟进，都更接近转化', priority: 'NORMAL' },
]
const messages = computed(() => props.data.displayMessages?.length ? props.data.displayMessages : defaultMessages)
const selectedMessage = computed(() => messages.value[props.sceneCycle % messages.value.length])
const formattedTime = computed(() => new Intl.DateTimeFormat('zh-CN', {
  hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(props.now))
const formattedDate = computed(() => new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
}).format(props.now))
const today = computed(() => props.data.dailyConversions.at(-1))
const recentDays = computed(() => props.data.dailyConversions.slice(-7))
const recentMax = computed(() => Math.max(1, ...recentDays.value.map((item) =>
  item.addedFriendCustomers + item.trustedFriendCustomers + item.qualifiedFriendCustomers)))
const champions = computed(() => {
  const definitions = [
    { key: 'total' as const, label: '总添加榜' },
    { key: 'todayTotal' as const, label: '总添加日榜' },
    { key: 'trusted' as const, label: '信任榜' },
    { key: 'qualityA' as const, label: 'A类榜' },
    { key: 'task' as const, label: '任务榜' },
  ]
  return definitions.map((definition) => {
    const rows = [...props.data.rankings].sort((left, right) =>
      rankingValue(right, definition.key) - rankingValue(left, definition.key)
        || left.displayName.localeCompare(right.displayName, 'zh-CN'))
    return { ...definition, member: rows[0], value: rows[0] ? rankingValue(rows[0], definition.key) : 0 }
  }).filter((item) => item.member)
})
const funnel = computed(() => [
  { label: '客户', value: props.data.headline.totalCustomers, tone: 'cyan' },
  { label: '添加', value: props.data.headline.addedFriendCustomers, tone: 'blue' },
  { label: '信任', value: props.data.headline.trustedFriendCustomers, tone: 'purple' },
  { label: '筛选', value: props.data.headline.qualifiedFriendCustomers, tone: 'gold' },
])
const stale = computed(() => props.data.aClassStale ?? {
  enabled: true,
  thresholdHours: 72,
  customerCount: 0,
  unassignedCount: 0,
  owners: [],
})
const schoolComparison = computed(() => props.data.schoolComparison ?? {
  featuredGrade: props.data.featuredGrade ?? '2026',
  jiangsuUniversity: 0,
  jiangsuUniversityOfScienceAndTechnology: 0,
  difference: 0,
  leader: 'TIED',
})
const staleThreshold = computed(() => stale.value.thresholdHours % 24 === 0
  ? `${stale.value.thresholdHours / 24} 天`
  : `${stale.value.thresholdHours} 小时`)
function eventDueLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <Transition name="experience-fade">
    <div
      v-if="standby || event"
      class="leadership-experience"
      :class="[{ 'is-event': event }, `motion-${motionIntensity.toLowerCase()}`]"
      data-testid="leadership-experience"
    >
      <LeadershipParticleCanvas />
      <div class="experience-grid"></div>
      <header class="experience-hud">
        <span><i></i> YINGGE INTELLIGENCE CENTER</span>
        <b>{{ scopeName }}</b>
        <time>{{ formattedTime }}</time>
      </header>

      <button v-if="event" class="experience-skip" type="button" @click="$emit('skip')">跳过动画 ×</button>

      <Transition v-if="event" name="event-burst" appear mode="out-in">
        <section :key="event.id" :class="['event-stage', `event-${event.type}`, `accent-${event.accent}`]">
          <div class="event-rings"><i></i><i></i><i></i></div>
          <small>{{ event.eyebrow }}</small>
          <h2>{{ event.title }}</h2>
          <strong>{{ event.value }}</strong>
          <p>{{ event.subtitle }}</p>
          <div v-if="event.type === 'zeroBreakthrough'" class="event-zero-burst" aria-hidden="true">
            <i v-for="index in 8" :key="index"></i>
          </div>
          <div
            v-if="event.type === 'customerAdded' || event.type === 'customerUpdated'"
            class="event-customer-activity"
            :class="{ 'is-update': event.type === 'customerUpdated' }"
          >
            <i aria-hidden="true"></i>
            <article v-for="(customer, index) in event.customers" :key="`${customer.name}-${index}`">
              <b>{{ customer.name.slice(0, 1) }}</b>
              <div><strong>{{ customer.name }}</strong><span>{{ customer.detail }}</span></div>
              <em>{{ event.type === 'customerAdded' ? 'NEW' : 'SYNC' }}</em>
            </article>
            <small v-if="event.customerCount > event.customers.length">
              以及另外 {{ event.customerCount - event.customers.length }} 位客户
            </small>
          </div>
          <div v-if="event.type === 'conversionStage'" class="event-conversion-beam" aria-hidden="true">
            <i></i><b>客户</b><i></i><b>添加</b><i></i><b>信任</b><i></i><b>筛选</b>
          </div>
          <div v-if="event.type === 'taskPublished'" class="event-task-published">
            <i aria-hidden="true"></i><i aria-hidden="true"></i>
            <div><small>MISSION BRIEF</small><strong>{{ event.taskTitle }}</strong></div>
            <span>发布人 · {{ event.publisherName }}</span>
            <span>{{ event.distributionType === 'SELF_CLAIM' ? '全员开放领取' : `覆盖成员 · ${event.assignmentCount} 人` }}</span>
            <span>截止时间 · {{ eventDueLabel(event.dueAt) }}</span>
          </div>
          <div v-if="event.type === 'taskMilestone'" class="event-task-lock" aria-hidden="true"><i>✓</i></div>
          <div v-if="event.type === 'rankingChange'" class="event-race-lanes" aria-hidden="true"><i></i><i></i><i></i></div>
          <div v-if="event.type === 'teamMomentum'" class="event-team-nodes" aria-hidden="true"><i v-for="index in 6" :key="index"></i></div>
          <div v-if="event.type === 'wakeUp'" class="event-wake-scan" aria-hidden="true"></div>
          <div v-if="event.delta" class="event-delta">本轮净增长 <b>+{{ event.delta }}</b></div>
          <div v-if="event.names?.length" class="event-names">
            <span v-for="name in event.names" :key="name">{{ name }}</span>
          </div>
          <div v-if="event.points?.length" class="event-sparkline">
            <i
              v-for="(point, index) in event.points"
              :key="index"
              :style="{ height: `${Math.max(10, point / Math.max(1, ...event.points) * 100)}%`, animationDelay: `${index * 90}ms` }"
            ></i>
          </div>
        </section>
      </Transition>

      <Transition v-else name="standby-scene" mode="out-in">
        <section :key="`${sceneId}-${sceneCycle}`" :class="['standby-stage', `standby-scene-${sceneId}`]">
          <template v-if="sceneId === 'time'">
            <div class="time-orbit"><i></i><i></i><i></i><strong>{{ formattedTime }}</strong></div>
            <h2>{{ formattedDate }}</h2><p>{{ scopeName }} · 经营驾驶舱待机中</p>
          </template>

          <template v-else-if="sceneId === 'culture'">
            <small>CULTURE &amp; BELIEF</small>
            <h2 class="culture-title">{{ selectedMessage?.title }}</h2>
            <p class="culture-copy">{{ selectedMessage?.content }}</p>
            <b class="culture-mark">YG</b>
          </template>

          <template v-else-if="sceneId === 'briefing'">
            <small>TODAY'S BUSINESS BRIEFING</small><h2>今日战报</h2>
            <div class="briefing-grid">
              <div><span>新增客户</span><strong>+{{ data.headline.todayNewCustomers }}</strong></div>
              <div><span>添加好友</span><strong>{{ today?.addedFriendCustomers ?? 0 }}</strong></div>
              <div><span>建立信任</span><strong>{{ today?.trustedFriendCustomers ?? 0 }}</strong></div>
              <div><span>筛选确认</span><strong>{{ data.headline.todayQualifiedCustomers }}</strong></div>
            </div>
          </template>

          <template v-else-if="sceneId === 'trend'">
            <small>SEVEN-DAY MOMENTUM</small><h2>增长轨迹</h2>
            <div class="standby-trend">
              <div v-for="item in recentDays" :key="item.date">
                <i :style="{ height: `${Math.max(5, (item.addedFriendCustomers + item.trustedFriendCustomers + item.qualifiedFriendCustomers) / recentMax * 100)}%` }"></i>
                <span>{{ item.date.slice(5) }}</span>
              </div>
            </div>
            <p>最近 7 天客户经营趋势持续同步</p>
          </template>

          <template v-else-if="sceneId === 'champions'">
            <small>TOP PERFORMERS</small><h2>榜首光柱</h2>
            <div class="champion-stage">
              <div v-for="(champion, index) in champions" :key="champion.key" :style="{ animationDelay: `${index * 120}ms` }">
                <span>{{ champion.label }}</span><strong>{{ champion.member?.displayName }}</strong><b>{{ champion.value }}</b>
              </div>
            </div>
          </template>

          <template v-else-if="sceneId === 'funnel'">
            <small>CONVERSION ENERGY FLOW</small><h2>转化能量流</h2>
            <div class="standby-funnel">
              <template v-for="(step, index) in funnel" :key="step.label">
                <div :class="`tone-${step.tone}`"><span>{{ step.label }}</span><strong>{{ step.value }}</strong><i></i></div>
                <b v-if="index < funnel.length - 1">›</b>
              </template>
            </div>
          </template>

          <template v-else-if="sceneId === 'mission'">
            <small>MISSION &amp; BROADCAST</small><h2>任务与公告播报</h2>
            <div class="mission-broadcast">
              <div class="mission-radar"><i></i><strong>{{ Math.round(data.taskSummary.completionRate * 100) }}%</strong><span>任务完成率</span></div>
              <div>
                <h3>{{ selectedMessage?.title }}</h3>
                <p>{{ selectedMessage?.content }}</p>
                <span v-if="data.tasks[0]">最近任务 · {{ data.tasks[0].title }}</span>
              </div>
            </div>
          </template>

          <template v-else-if="sceneId === 'followUpRisk'">
            <small>A-CLASS FOLLOW-UP RADAR</small><h2>A 类客户跟进风险</h2>
            <div class="follow-up-risk-scene" :class="{ clear: stale.customerCount === 0 }">
              <div class="risk-orbit"><i></i><i></i><strong>{{ stale.customerCount }}</strong><span>超过 {{ staleThreshold }} 未更新</span></div>
              <div class="risk-owner-list">
                <article v-for="owner in stale.owners.slice(0, 6)" :key="owner.membershipId">
                  <span>{{ owner.displayName }}</span><b>{{ owner.customerCount }}</b><i :style="{ width: `${Math.max(8, owner.customerCount / Math.max(1, stale.customerCount) * 100)}%` }"></i>
                </article>
                <article v-if="stale.unassignedCount"><span>未分配 / 已停用</span><b>{{ stale.unassignedCount }}</b><i :style="{ width: `${Math.max(8, stale.unassignedCount / Math.max(1, stale.customerCount) * 100)}%` }"></i></article>
                <p v-if="!stale.enabled">该提醒策略已由管理员关闭</p>
                <p v-else-if="stale.customerCount === 0">当前范围内没有超时的 A 类客户</p>
              </div>
            </div>
          </template>

          <template v-else-if="sceneId === 'schoolComparison'">
            <small>FEATURED COHORT COMPARISON</small><h2>{{ schoolComparison.featuredGrade }} 级核心院校客户</h2>
            <div class="school-comparison-scene">
              <article class="school-jsu">
                <span>EXAMPLE UNIVERSITY</span><h3>示例大学</h3><strong>{{ schoolComparison.jiangsuUniversity }}</strong><small>人</small>
              </article>
              <div class="school-comparison-gap"><i></i><b>VS</b><strong>{{ schoolComparison.difference }}</strong><span>相差人数</span></div>
              <article class="school-jsust">
                <span>EXAMPLE UNIVERSITY OF SCIENCE AND TECHNOLOGY</span><h3>示例科技大学</h3><strong>{{ schoolComparison.jiangsuUniversityOfScienceAndTechnology }}</strong><small>人</small>
              </article>
            </div>
          </template>
        </section>
      </Transition>

      <footer class="experience-footer">
        <span>业务数据更新后将自动唤醒</span>
        <div><i v-for="index in sceneCount" :key="index" :class="{ active: !event && scenePosition === index - 1 }"></i></div>
        <b>DATA SECURITY LEVEL · INTERNAL</b>
      </footer>
    </div>
  </Transition>
</template>
