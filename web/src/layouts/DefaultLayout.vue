<script setup lang="ts">
import { RouterView } from 'vue-router'
import SideNav from '@/components/SideNav.vue'
</script>

<template>
  <div class="flex min-h-screen bg-canvas">
    <SideNav />

    <main class="min-w-0 flex-1">
      <div class="mx-auto w-full max-w-6xl px-10 py-12">
        <RouterView v-slot="{ Component, route }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </Transition>
        </RouterView>
      </div>
    </main>
  </div>
</template>
