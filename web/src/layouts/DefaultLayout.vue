<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { Menu } from 'lucide-vue-next'
import SideNav from '@/components/SideNav.vue'
import { activeNavLabel } from '@/lib/navigation'

const route = useRoute()
const mainEl = ref<HTMLElement | null>(null)
const mobileNavOpen = ref(false)

const pageTitle = computed(() => activeNavLabel(route.path))

watch(
  () => route.path,
  () => {
    mainEl.value?.scrollTo({ top: 0 })
    mobileNavOpen.value = false
  },
)

/**
 * 面板用的是固定侧栏加内部滚动。
 * 把文档本身锁住，双指横划或上下划就不会再带着整个页面回弹；
 * 离开面板（比如回到登录页）时再恢复，登录页仍然可以正常滚动。
 * 移动端侧栏变成抽屉，内容同样由 main 滚动，顶部工具栏始终可见。
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
  <div class="flex h-dvh flex-col overflow-hidden bg-canvas md:flex-row">
    <header
      class="pt-safe z-30 flex min-h-14 shrink-0 items-center gap-2 border-b border-hairline bg-white px-2 md:hidden"
    >
      <button
        type="button"
        class="press grid h-11 w-11 shrink-0 place-items-center rounded-lg text-slate-600 hover:bg-panel"
        :aria-expanded="mobileNavOpen"
        aria-controls="app-sidenav"
        aria-label="打开导航"
        @click="mobileNavOpen = true"
      >
        <Menu :size="20" :stroke-width="2.2" />
      </button>
      <span class="min-w-0 truncate text-[15px] font-medium text-slate-800">{{ pageTitle }}</span>
    </header>

    <SideNav :mobile-open="mobileNavOpen" @close="mobileNavOpen = false" />

    <main
      ref="mainEl"
      class="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto overscroll-y-contain"
    >
      <div
        class="pb-safe mx-auto min-h-full w-full max-w-6xl px-4 py-6 sm:px-6 lg:h-full lg:px-10 lg:py-12"
      >
        <RouterView v-slot="{ Component, route }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </Transition>
        </RouterView>
      </div>
    </main>
  </div>
</template>
