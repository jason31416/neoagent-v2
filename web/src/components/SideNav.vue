<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { LogOut, X } from 'lucide-vue-next'
import { signOut } from '@/lib/auth'
import { isNavActive, primaryNav, secondaryNav } from '@/lib/navigation'

const props = defineProps<{ mobileOpen?: boolean }>()
const emit = defineEmits<{ close: [] }>()

const route = useRoute()
const router = useRouter()

/** 抽屉只在窄屏存在，宽屏侧栏要一直可点，所以不能靠 CSS 隐藏后仍留着焦点。 */
const mobileQuery = window.matchMedia('(max-width: 767px)')
const isMobile = ref(mobileQuery.matches)

function syncMobile(event: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = event.matches
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.mobileOpen) emit('close')
}

onMounted(() => {
  syncMobile(mobileQuery)
  mobileQuery.addEventListener('change', syncMobile)
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  mobileQuery.removeEventListener('change', syncMobile)
  window.removeEventListener('keydown', handleKeydown)
})

function closeMobile() {
  if (isMobile.value) emit('close')
}

function handleSignOut() {
  closeMobile()
  signOut()
  router.replace({ name: 'login' })
}
</script>

<template>
  <Transition name="dialog">
    <div
      v-if="mobileOpen && isMobile"
      class="fixed inset-0 z-40 bg-slate-900/35"
      aria-hidden="true"
      @click="emit('close')"
    />
  </Transition>

  <aside
    id="app-sidenav"
    :inert="isMobile && !mobileOpen"
    :aria-hidden="isMobile && !mobileOpen ? 'true' : undefined"
    class="fixed inset-y-0 left-0 z-50 flex h-dvh w-[min(84vw,280px)] shrink-0 flex-col border-r border-hairline bg-white shadow-2xl transition-transform duration-200 ease-out md:static md:z-auto md:w-67 md:translate-x-0 md:shadow-none md:transition-none"
    :class="mobileOpen ? 'translate-x-0' : '-translate-x-full'"
  >
    <div class="flex items-center justify-between gap-2 px-4 py-4 sm:px-5 sm:py-5">
      <span class="text-[18px] tracking-wide text-slate-500"
        ><span class="font-semibold tracking-normal text-slate-700">NeoAgent</span> WebUI</span
      >
      <button
        type="button"
        class="press -mr-2 grid h-11 w-11 shrink-0 place-items-center rounded-lg text-slate-500 hover:bg-panel md:hidden"
        aria-label="关闭导航"
        @click="emit('close')"
      >
        <X :size="18" :stroke-width="2.2" />
      </button>
    </div>

    <nav class="flex-1 overflow-y-auto px-3">
      <p class="px-2 pb-2 pt-1 text-[12px] tracking-wider font-medium text-slate-400">控制台</p>

      <RouterLink
        v-for="item in primaryNav"
        :key="item.to"
        v-ripple
        :to="item.to"
        class="group mb-1.5 flex items-center gap-3 rounded-lg px-3 py-3 text-[15px] transition-colors duration-150 md:py-2"
        :class="
          isNavActive(route.path, item.to)
            ? 'bg-brand-50 text-brand-800'
            : 'text-slate-700 hover:bg-panel hover:text-slate-900'
        "
        @click="closeMobile"
      >
        <component
          :is="item.icon"
          :size="17"
          :stroke-width="2.2"
          class="shrink-0 transition-transform duration-200 group-hover:scale-105"
        />
        <span class="px-0.5 truncate">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="pb-safe border-t border-hairline p-3">
      <RouterLink
        v-for="item in secondaryNav"
        :key="item.to"
        v-ripple
        :to="item.to"
        class="group mb-0.5 flex items-center gap-3 rounded-lg px-3 py-3 text-sm transition-colors duration-150 md:py-2"
        :class="
          isNavActive(route.path, item.to)
            ? 'bg-brand-50 font-medium text-brand-700'
            : 'text-slate-600 hover:bg-panel hover:text-slate-900'
        "
        @click="closeMobile"
      >
        <component
          :is="item.icon"
          :size="17"
          :stroke-width="2.2"
          class="shrink-0 transition-transform duration-200 group-hover:scale-105"
        />
        <span>{{ item.label }}</span>
      </RouterLink>

      <button
        v-ripple
        type="button"
        class="group flex w-full items-center gap-3 rounded-lg px-3 py-3 text-sm text-slate-500 transition-colors duration-150 hover:bg-panel hover:text-slate-900 active:scale-[0.99] md:py-2"
        @click="handleSignOut"
      >
        <LogOut
          :size="17"
          :stroke-width="2.2"
          class="shrink-0 transition-transform duration-200 group-hover:scale-105"
        />
        <span>退出登录</span>
      </button>
    </div>
  </aside>
</template>
