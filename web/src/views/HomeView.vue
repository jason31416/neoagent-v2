<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { AlertTriangle, Bot, Cpu, MemoryStick, Radio, RefreshCw } from 'lucide-vue-next'
import { api, type RuntimeStatus } from '@/lib/api'
import { formatCount, formatDuration, formatMemory, formatPercent, isAvailable } from '@/lib/format'
import { buildDailySeries } from '@/lib/series'
import AppChip from '@/components/AppChip.vue'
import StatTile from '@/components/StatTile.vue'
import MetricBars from '@/components/MetricBars.vue'

const status = ref<RuntimeStatus | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const error = ref<string | null>(null)

const now = ref(Date.now())
let ticker: number | undefined

/**
 * 后端给的是“启动至今的毫秒数”，记下换算基准，
 * 之后本地每秒走一格就行，不用反复请求接口。
 */
const launchAt = ref<number | null>(null)

const stats = computed(() => status.value?.statistics ?? null)
const systemInfo = computed(() => stats.value?.systemInfo ?? null)

const elapsed = computed(() => (launchAt.value === null ? 0 : Math.max(0, now.value - launchAt.value)))

/**
 * 两张图的柱子宽度要对齐，所以窄的那张少放几天：
 * 14 根柱子落在 3 格宽的面板里，9 根落在 2 格宽的面板里，宽度基本一致。
 * 窗口变窄时整体少放几天，否则每根柱子下面的日期会挤在一起。
 */
const isWide = ref(false)

const tokenSeries = computed(() =>
  buildDailySeries(stats.value?.dailyTokenUsage, isWide.value ? 14 : 10),
)
const messageSeries = computed(() =>
  buildDailySeries(stats.value?.dailyMessagesHandled, isWide.value ? 9 : 7),
)

const agentsAvailable = computed(() => isAvailable(stats.value?.agents ?? -1))
const channelsAvailable = computed(() => isAvailable(stats.value?.channels ?? -1))
const cpuAvailable = computed(() => isAvailable(stats.value?.systemInfo.cpuPercentage ?? -1))
const memoryAvailable = computed(() => isAvailable(stats.value?.systemInfo.memory ?? -1))

const greeting = computed(() => {
  const hour = new Date(now.value).getHours()
  if (hour < 5) return '晚上好'
  if (hour < 11) return '早上好'
  if (hour < 13) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

async function load(isRefresh = false) {
  if (isRefresh) refreshing.value = true

  try {
    const data = await api.status()
    status.value = data
    launchAt.value = Date.now() - data.statistics.running
    now.value = Date.now()
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

const wideQuery = window.matchMedia('(min-width: 1280px)')

function syncWidth(event: MediaQueryListEvent | MediaQueryList) {
  isWide.value = event.matches
}

onMounted(() => {
  syncWidth(wideQuery)
  wideQuery.addEventListener('change', syncWidth)
  void load()
  ticker = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (ticker !== undefined) window.clearInterval(ticker)
  wideQuery.removeEventListener('change', syncWidth)
})
</script>

<template>
  <div class="animate-fade-in">
    <div>
      <header class="flex flex-wrap items-start justify-between gap-x-8 gap-y-5">
        <div class="min-w-0">
          <h1 class="text-[28px] font-semibold leading-tight tracking-tight text-slate-900">
            {{ greeting }}，这里是 NeoAgent！
          </h1>
          <p class="mt-2 text-sm text-slate-500">
            已经运行了
            <span class="font-medium text-slate-700 tabular-nums">{{ formatDuration(elapsed) }}</span>
          </p>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <AppChip mono :label="`v${status?.version ?? '--'}`" />
          <AppChip v-if="systemInfo?.osInfo" :label="systemInfo.osInfo" />
          <AppChip v-if="systemInfo?.systemTime" mono :label="systemInfo.systemTime" />

          <button
            v-ripple
            type="button"
            class="group flex items-center gap-1.5 rounded-full bg-panel px-3 py-1 text-xs text-slate-600 transition-colors duration-200 hover:bg-panel-strong hover:text-slate-900 disabled:opacity-60"
            :disabled="refreshing"
            @click="load(true)"
          >
            <RefreshCw
              :size="12"
              :stroke-width="2.2"
              class="transition-transform duration-500"
              :class="refreshing ? 'animate-spin' : 'group-hover:rotate-90'"
            />
            刷新
          </button>
        </div>
      </header>

      <Transition
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="-translate-y-1 opacity-0"
        leave-active-class="transition duration-150 ease-in"
        leave-to-class="-translate-y-1 opacity-0"
      >
        <div
          v-if="error"
          class="mt-7 flex items-start gap-2.5 rounded-2xl bg-red-50 px-4 py-3.5 text-red-700"
          role="alert"
        >
          <AlertTriangle :size="16" class="mt-0.5 shrink-0" />
          <div class="min-w-0 text-[13px] leading-relaxed">
            <p>{{ error }}</p>
            <button
              type="button"
              class="mt-1 font-medium underline decoration-red-300 underline-offset-2 transition hover:decoration-red-500"
              @click="load(true)"
            >
              重试
            </button>
          </div>
        </div>
      </Transition>

      <section class="mt-9 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Agents"
          :icon="Bot"
          :value="formatCount(stats?.agents ?? -1)"
          caption="个 Agent 正在运行"
          :available="agentsAvailable"
          :loading="loading"
          :index="0"
        />
        <StatTile
          label="频道"
          :icon="Radio"
          :value="formatCount(stats?.channels ?? -1)"
          caption="个频道已经接入"
          :available="channelsAvailable"
          :loading="loading"
          :index="1"
        />
        <StatTile
          label="CPU"
          :icon="Cpu"
          :value="formatPercent(stats?.systemInfo.cpuPercentage ?? -1)"
          :available="cpuAvailable"
          :loading="loading"
          :index="2"
        />
        <StatTile
          label="内存"
          :icon="MemoryStick"
          :value="formatMemory(stats?.systemInfo.memory ?? -1)"
          :available="memoryAvailable"
          :loading="loading"
          :index="3"
        />
      </section>

      <section class="mt-4 grid gap-4 lg:grid-cols-5">
        <MetricBars
          class="lg:col-span-3"
          title="每日 Token 用量"
          unit="tokens"
          :points="tokenSeries"
          :loading="loading"
          :delay="260"
        />
        <MetricBars
          class="lg:col-span-2"
          title="每日消息处理量"
          unit="条"
          :points="messageSeries"
          :loading="loading"
          :delay="320"
        />
      </section>
    </div>
  </div>
</template>
