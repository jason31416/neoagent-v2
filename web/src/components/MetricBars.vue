<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { formatCompact } from '@/lib/format'
import type { SeriesPoint } from '@/lib/series'

const props = withDefaults(
  defineProps<{
    title: string
    points: SeriesPoint[]
    unit?: string
    loading?: boolean
    delay?: number
  }>(),
  { loading: false, delay: 0 },
)

const max = computed(() => Math.max(1, ...props.points.map((point) => point.value)))

const total = computed(() => props.points.reduce((sum, point) => sum + point.value, 0))

const bars = computed(() =>
  props.points.map((point, index) => ({
    key: point.key,
    label: point.label,
    value: point.value,
    percent: Math.max(1.5, Math.round((point.value / max.value) * 100)),
    delay: Math.min(index, 24) * 28,
  })),
)

const scrollEl = ref<HTMLElement | null>(null)
let userMoved = false

function markUserMoved() {
  userMoved = true
}

/** 纵向滚轮只是在翻页面，不算在动图表，别因此取消初始对齐。 */
function onWheel(event: WheelEvent) {
  if (Math.abs(event.deltaX) > Math.abs(event.deltaY)) markUserMoved()
}

/** 最新的一天在最右，初始对齐到末尾；用户一上手滑动就不再自动干预。 */
function alignToLatest() {
  if (userMoved) return
  const el = scrollEl.value
  if (!el) return
  el.scrollLeft = el.scrollWidth - el.clientWidth
}

onMounted(() => void nextTick(alignToLatest))

watch(
  () => props.loading,
  (loading) => {
    if (!loading) void nextTick(alignToLatest)
  },
)
</script>

<template>
  <section
    class="animate-rise min-w-0 overflow-hidden rounded-2xl bg-panel p-4 transition-colors duration-300 ease-out hover:bg-panel-strong sm:p-7"
    :style="{ animationDelay: `${delay}ms` }"
  >
    <header class="flex items-start justify-between gap-2 sm:gap-4">
      <div class="min-w-0">
        <h2 class="text-[13px] font-medium text-slate-500">{{ title }}</h2>

        <div class="mt-2 flex h-8 items-baseline gap-1.5">
          <div v-if="loading" class="h-6 w-24 animate-pulse rounded-md bg-slate-200/70" />
          <template v-else>
            <span
              class="text-[22px] font-semibold leading-none tracking-tight text-slate-900 tabular-nums sm:text-[28px]"
            >
              {{ formatCompact(total) }}
            </span>
            <span v-if="unit" class="text-xs text-slate-400">{{ unit }}</span>
          </template>
        </div>
      </div>

      <span class="shrink-0 pt-0.5 text-right text-[11px] text-slate-400 sm:text-[12px]">
        最近 {{ points.length }} 天
        <span class="block text-[10px] text-slate-300 sm:hidden">右滑查看更早</span>
      </span>
    </header>

    <!-- 柱子和日期放在同一列里，横向滑动时两者始终对齐；pt-6 给柱顶的数值气泡留位置。 -->
    <div
      ref="scrollEl"
      class="scrollbar-none w-full overflow-x-auto pt-6 sm:pt-7"
      @pointerdown.passive="markUserMoved"
      @touchstart.passive="markUserMoved"
      @wheel.passive="onWheel"
    >
      <div v-if="loading" class="flex w-full items-end gap-1 sm:gap-2">
        <div v-for="i in points.length" :key="i" class="flex grow shrink-0 basis-8.5 flex-col">
          <div class="flex h-40 items-end sm:h-56">
            <div
              class="w-full animate-pulse rounded-t-[5px] bg-slate-200/50"
              :style="{ height: `${30 + ((i * 37) % 60)}%` }"
            />
          </div>
          <div class="mt-3 h-3" />
        </div>
      </div>

      <div v-else class="flex w-full gap-1 sm:gap-2">
        <div v-for="bar in bars" :key="bar.key" class="flex grow shrink-0 basis-8.5 flex-col">
          <div class="flex h-40 items-end sm:h-56">
            <div
              class="group relative w-full animate-grow rounded-t-[5px] transition-colors duration-200"
              :class="
                bar.value > 0
                  ? 'bg-brand-300/90 hover:bg-brand-400'
                  : 'bg-slate-300/50 hover:bg-slate-300/70'
              "
              :style="{
                height: `${bar.percent}%`,
                animationDelay: `${bar.delay}ms`,
                transformOrigin: 'bottom',
              }"
              :title="`${bar.value}`"
            >
              <span
                class="pointer-events-none absolute -top-6 left-1/2 -translate-x-1/2 rounded-full bg-gray-500 px-2 py-0.5 text-[10px] font-medium whitespace-nowrap text-gray-50 opacity-0 transition-opacity duration-150 group-hover:opacity-100"
              >
                {{ formatCompact(bar.value) }}
              </span>
            </div>
          </div>
          <span
            class="mt-3 block truncate text-center text-[9px] text-slate-400 tabular-nums xl:text-[10px]"
          >
            {{ bar.label }}
          </span>
        </div>
      </div>
    </div>
  </section>
</template>
