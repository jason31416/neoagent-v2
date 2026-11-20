<script setup lang="ts">
import { Construction } from 'lucide-vue-next'

defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <section class="animate-fade-in">
    <header>
      <h1 class="text-[22px] font-semibold tracking-tight text-slate-900 sm:text-2xl">
        {{ title }}
      </h1>
    </header>

    <div
      class="mt-6 flex flex-col items-center justify-center rounded-2xl bg-panel px-4 py-16 text-center sm:px-6 sm:py-24"
    >
      <span class="grid h-11 w-11 place-items-center rounded-full bg-slate-100 text-slate-400">
        <Construction :size="20" :stroke-width="2.2" />
      </span>
      <p class="mt-4 text-sm font-medium text-slate-700">这个页面还在施工</p>
      <p v-if="description" class="mt-1 max-w-sm text-sm leading-relaxed text-slate-500">
        {{ description }}
      </p>
    </div>
  </section>
</template>
