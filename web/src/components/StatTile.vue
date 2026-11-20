<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    label: string
    value: string
    /** 数字下面那句话的后半截，和数字连起来读，例如「1」+「个 Agent 正在运行」。 */
    caption?: string
    /** 数据拿不到时把说明换成「暂时读取不到」，避免出现「-- 个 Agent 正在运行」。 */
    available?: boolean
    icon?: Component
    loading?: boolean
    index?: number
  }>(),
  { index: 0, loading: false, available: true },
)
</script>

<template>
  <div
    class="group relative animate-rise overflow-hidden rounded-2xl bg-panel p-4 transition-colors duration-300 ease-out hover:bg-panel-strong sm:p-8"
    :style="{ animationDelay: `${index * 60}ms` }"
  >
    <component
      :is="icon"
      v-if="icon"
      :size="92"
      :stroke-width="2"
      aria-hidden="true"
      class="pointer-events-none absolute -right-4 -bottom-5 hidden text-slate-200/75 transition-transform duration-500 ease-out group-hover:-translate-y-1 sm:block"
    />

    <p class="relative text-[13px] font-medium text-slate-500 sm:text-[16px]">{{ label }}</p>

    <div class="relative mt-3 flex h-9 flex-wrap items-baseline gap-x-1.5 sm:mt-5 sm:h-11">
      <div
        v-if="loading"
        class="h-6 w-14 animate-pulse rounded-md bg-slate-200/70 sm:h-7 sm:w-20"
      />
      <template v-else>
        <span
          class="text-[26px] font-semibold leading-none tracking-tight text-slate-900 tabular-nums sm:text-[38px]"
        >
          {{ value }}
        </span>
        <span
          v-if="caption"
          class="text-[11px] leading-tight text-slate-400 sm:text-[14px] sm:leading-none"
        >
          {{ available ? caption : '暂时读取不到' }}
        </span>
      </template>
    </div>
  </div>
</template>
