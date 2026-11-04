<script setup lang="ts">
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  Activity,
  Bot,
  Boxes,
  LogOut,
  MessagesSquare,
  Radio,
  Settings,
} from 'lucide-vue-next'
import { signOut } from '@/lib/auth'

const route = useRoute()
const router = useRouter()

const primaryNav = [
  { label: '概览', to: '/', icon: Activity },
  { label: 'Agents', to: '/agents', icon: Bot },
  { label: '频道', to: '/channels', icon: Radio },
  { label: '模型', to: '/models', icon: Boxes },
  { label: '消息记录', to: '/messages', icon: MessagesSquare },
]

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}

function handleSignOut() {
  signOut()
  router.replace({ name: 'login' })
}
</script>

<template>
  <aside
    class="sticky top-0 flex h-screen w-67 shrink-0 flex-col border-r border-hairline bg-white"
  >
    <div class="flex items-center gap-2.5 px-5 py-5">
      <span class="grid h-8 w-8 place-items-center rounded-lg bg-brand-300 text-brand-600">
        <Bot :size="18" :stroke-width="2.3" />
      </span>
      <span class="text-[16px] font-semibold text-slate-800">NeoAgent</span>
    </div>

    <nav class="flex-1 overflow-y-auto px-3">
      <p class="px-2 pb-2 pt-1 text-[12px] tracking-wider font-medium text-slate-400">控制台</p>

      <RouterLink
        v-for="item in primaryNav"
        :key="item.to"
        v-ripple
        :to="item.to"
        class="group mb-1.5 flex items-center gap-3 rounded-lg px-3 py-2 text-[15px] transition-colors duration-150"
        :class="
          isActive(item.to)
            ? 'bg-brand-50 text-brand-800'
            : 'text-slate-700 hover:bg-panel hover:text-slate-900'
        "
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

    <div class="border-t border-hairline p-3">
      <RouterLink
        v-ripple
        to="/settings"
        class="group mb-0.5 flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors duration-150"
        :class="
          isActive('/settings')
            ? 'bg-brand-50 font-medium text-brand-700'
            : 'text-slate-600 hover:bg-panel hover:text-slate-900'
        "
      >
        <Settings
          :size="17"
          :stroke-width="2.2"
          class="shrink-0 transition-transform duration-200 group-hover:scale-105"
        />
        <span>系统设置</span>
      </RouterLink>

      <button
        v-ripple
        type="button"
        class="group flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm text-slate-500 transition-colors duration-150 hover:bg-panel hover:text-slate-900 active:scale-[0.99]"
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
