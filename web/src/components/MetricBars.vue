<script setup lang="ts">
import { computed } from 'vue'
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
</script>

<template>
  <section
    class="animate-rise rounded-2xl bg-panel p-7 transition-colors duration-300 ease-out hover:bg-panel-strong"
    :style="{ animationDelay: `${delay}ms` }"
  >
    <header class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <h2 class="text-[13px] font-medium text-slate-500">{{ title }}</h2>

        <div class="mt-2 flex h-8 items-baseline gap-1.5">
          <div v-if="loading" class="h-6 w-24 animate-pulse rounded-md bg-slate-200/70" />
          <template v-else>
            <span
              class="text-[28px] font-semibold leading-none tracking-tight text-slate-900 tabular-nums"
            >
              {{ formatCompact(total) }}
            </span>
            <span v-if="unit" class="text-xs text-slate-400">{{ unit }}</span>
          </template>
        </div>
      </div>

      <span class="pt-0.5 text-[12px] text-slate-400">最近 {{ points.length }} 天</span>
    </header>

    <div v-if="loading" class="mt-7 flex h-56 items-end gap-2">
      <div
        v-for="i in points.length"
        :key="i"
        class="flex-1 animate-pulse rounded-t-[5px] bg-slate-200/50"
        :style="{ height: `${30 + ((i * 37) % 60)}%` }"
      />
    </div>

    <template v-else>
      <!-- 两张图用同样的柱子间隔，宽的那张只是多放几天。 -->
      <div class="mt-7 flex h-56 items-end gap-2">
        <div
          v-for="bar in bars"
          :key="bar.key"
          class="group relative flex-1 animate-grow rounded-t-[5px] transition-colors duration-200"
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

      <div class="mt-3 flex gap-2">
        <span
          v-for="bar in bars"
          :key="`${bar.key}-label`"
          class="flex-1 text-center text-[9px] whitespace-nowrap text-slate-400 tabular-nums xl:text-[10px]"
        >
          {{ bar.label }}
        </span>
      </div>
    </template>
  </section>
</template>
