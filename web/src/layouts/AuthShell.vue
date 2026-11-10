<script setup lang="ts">
import { Bot } from 'lucide-vue-next'

defineProps<{
  title: string
}>()
</script>

<template>
  <div
    class="pt-safe pb-safe flex min-h-dvh items-center justify-center bg-white px-4 py-8 sm:px-6 sm:py-14"
  >
    <div class="w-full max-w-[420px] animate-rise">
      <section class="rounded-[22px] bg-panel px-5 py-8 sm:px-9 sm:py-12">

        <h1 class="text-[16px] tracking-wide font-semibold text-slate-400">
          {{ title }}
        </h1>

        <div class="mt-1 flex items-center gap-2">
          <span class="text-[22px] tracking-wide text-slate-500 sm:text-[30px]"><span class="tracking-normal text-slate-700">NeoAgent</span> WebUI</span>
        </div>

        <div class="mt-6 sm:mt-7">
          <slot />
        </div>
      </section>
    </div>
  </div>
</template>
