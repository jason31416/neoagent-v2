<script setup lang="ts">
import { Bot } from 'lucide-vue-next'

defineProps<{
  title: string
}>()
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-white px-6 py-14">
    <div class="w-full max-w-[420px] animate-rise">
      <section class="rounded-[22px] bg-panel px-9 py-12">

        <h1 class="text-[16px] tracking-wide font-semibold text-slate-400">
          {{ title }}
        </h1>

        <div class="mt-1 flex items-center gap-2">
          <span class="text-[30px] tracking-wide text-slate-500"><span class="tracking-normal text-slate-700">NeoAgent</span> WebUI</span>
        </div>

        <div class="mt-7">
          <slot />
        </div>
      </section>
    </div>
  </div>
</template>
