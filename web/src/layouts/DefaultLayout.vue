<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import SideNav from '@/components/SideNav.vue'

const route = useRoute()
const mainEl = ref<HTMLElement | null>(null)

watch(
  () => route.path,
  () => {
    mainEl.value?.scrollTo({ top: 0 })
  },
)

/**
 * 面板用的是固定侧栏加内部滚动。
 * 把文档本身锁住，双指横划或上下划就不会再带着整个页面回弹；
 * 离开面板（比如回到登录页）时再恢复，登录页仍然可以正常滚动。
 */
onMounted(() => {
  document.documentElement.style.overflow = 'hidden'
  document.body.style.overflow = 'hidden'
})

onBeforeUnmount(() => {
  document.documentElement.style.overflow = ''
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-canvas">
    <SideNav />

    <main
      ref="mainEl"
      class="min-w-0 flex-1 overflow-x-hidden overflow-y-auto overscroll-y-contain"
    >
      <div class="mx-auto h-full w-full max-w-6xl overflow-x-clip px-10 py-12">
        <RouterView v-slot="{ Component, route }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </Transition>
        </RouterView>
      </div>
    </main>
  </div>
</template>
