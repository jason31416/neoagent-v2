<script setup lang="ts">
import { Bot } from 'lucide-vue-next'

defineProps<{
  title: string
}>()
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-white px-6 py-14">
    <div class="w-full max-w-[420px] animate-rise">
      <section class="rounded-[28px] bg-panel px-9 py-10">
        <div class="flex items-center gap-2.5">
          <span class="grid h-9 w-9 place-items-center rounded-xl bg-brand-300 text-brand-800">
            <Bot :size="19" :stroke-width="2.3" />
          </span>
          <span class="text-[15px] font-semibold tracking-tight text-slate-900">NeoAgent</span>
        </div>

        <h1 class="mt-11 text-[26px] font-semibold leading-tight tracking-tight text-slate-900">
          {{ title }}
        </h1>

        <div class="mt-9">
          <slot />
        </div>
      </section>
    </div>
  </div>
</template>
