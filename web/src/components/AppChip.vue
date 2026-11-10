<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    label?: string
    icon?: Component
    mono?: boolean
  }>(),
  { mono: false },
)
</script>

<template>
  <span
    class="inline-flex max-w-full select-none items-center gap-1.5 rounded-full bg-panel px-3 py-1 text-xs text-slate-600 transition-colors duration-200 hover:bg-panel-strong"
    :class="mono ? 'font-mono text-[11px] tabular-nums' : ''"
  >
    <component
      :is="icon"
      v-if="icon"
      :size="12"
      :stroke-width="2.2"
      class="shrink-0 text-slate-400"
      aria-hidden="true"
    />
    <span class="min-w-0 truncate"
      ><slot>{{ label }}</slot></span
    >
  </span>
</template>
