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
    class="group relative animate-rise overflow-hidden rounded-2xl bg-panel p-8 transition-colors duration-300 ease-out hover:bg-panel-strong"
    :style="{ animationDelay: `${index * 60}ms` }"
  >
    <component
      :is="icon"
      v-if="icon"
      :size="92"
      :stroke-width="2"
      aria-hidden="true"
      class="pointer-events-none absolute -right-4 -bottom-5 text-slate-200/[0.75] transition-transform duration-500 ease-out group-hover:-translate-y-1"
    />

    <p class="relative text-[16px] font-medium text-slate-500">{{ label }}</p>

    <div class="relative mt-5 flex h-11 items-baseline gap-1.5">
      <div v-if="loading" class="h-7 w-20 animate-pulse rounded-md bg-slate-200/70" />
      <template v-else>
        <span
          class="text-[38px] font-semibold leading-none tracking-tight text-slate-900 tabular-nums"
        >
          {{ value }}
        </span>
        <span v-if="caption" class="text-[14px] leading-none text-slate-400">
          {{ available ? caption : '暂时读取不到' }}
        </span>
      </template>
    </div>
  </div>
</template>
