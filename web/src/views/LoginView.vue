<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, Info, Loader2 } from 'lucide-vue-next'
import { api, ApiError } from '@/lib/api'
import { setToken } from '@/lib/auth'
import AuthShell from '@/layouts/AuthShell.vue'

const route = useRoute()
const router = useRouter()

const password = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)
const checking = ref(true)

/** null 表示暂时判断不出来（后端没连上）。 */
const registered = ref<boolean | null>(null)

const inputEl = ref<HTMLInputElement | null>(null)

/** 被 401 踢回来时带上的标记，用来解释为什么突然要重新登录。 */
const sessionExpired = computed(() => route.query.expired === '1')

function safeRedirect(): string {
  const raw = route.query.redirect
  if (typeof raw !== 'string') return '/'
  // 只接受站内路径，避免被链接带去别的地方。
  if (!raw.startsWith('/') || raw.startsWith('//')) return '/'
  return raw
}

onMounted(async () => {
  try {
    const res = await api.isRegistered()
    registered.value = res.status
  } catch (e) {
    error.value = e instanceof Error ? e.message : '无法连接到 NeoAgent 服务'
  } finally {
    checking.value = false
    if (registered.value !== false) {
      await nextTick()
      inputEl.value?.focus()
    }
  }
})

async function submit() {
  if (submitting.value) return

  error.value = null

  if (password.value.length === 0) {
    error.value = '请输入面板密码'
    inputEl.value?.focus()
    return
  }

  submitting.value = true
  try {
    const res = await api.login(password.value)
    setToken(res.token)
    await router.replace(safeRedirect())
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) error.value = '密码不正确'
    else error.value = e instanceof Error ? e.message : '登录失败'

    password.value = ''
    await nextTick()
    inputEl.value?.focus()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell title="欢迎回来">
    <div v-if="checking" class="animate-pulse space-y-4">
      <div class="h-10 w-full rounded-xl bg-slate-100" />
      <div class="h-10 w-full rounded-xl bg-slate-100" />
    </div>

    <div
      v-else-if="registered === false"
      class="flex items-start gap-2.5 rounded-xl bg-amber-50 p-3.5 text-amber-800"
    >
      <AlertTriangle :size="16" class="mt-0.5 shrink-0" />
      <div class="text-[13px] leading-relaxed">
        <p>您还没有设置密码，请先从后台日志中打开绑定链接</p>
        <p class="mt-1.5 text-amber-700/80">
          NeoAgent 启动时会在控制台打印一条包含 token 的链接，打开它就能为面板设置密码
        </p>
      </div>
    </div>

    <form v-else class="animate-fade-in" novalidate @submit.prevent="submit">
      <div
        v-if="sessionExpired"
        class="mb-5 flex items-start gap-2.5 rounded-xl bg-brand-50 p-3 text-brand-700"
      >
        <Info :size="16" class="mt-0.5 shrink-0" />
        <p class="text-[13px] leading-relaxed">登录状态已失效，请重新登录</p>
      </div>

      <label for="password" class="block text-[13px] font-medium text-slate-600">面板密码</label>
      <input
        id="password"
        ref="inputEl"
        v-model="password"
        type="password"
        autocomplete="current-password"
        :aria-invalid="error !== null"
        placeholder="请输入密码"
        class="mt-2 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-base text-slate-900 outline-none sm:text-sm transition duration-150 placeholder:text-slate-400 focus:ring-2 focus:ring-brand-300/10 disabled:bg-slate-50"
        :disabled="submitting"
      />

      <Transition
        enter-active-class="transition duration-150 ease-out"
        enter-from-class="-translate-y-1 opacity-0"
        leave-active-class="transition duration-100 ease-in"
        leave-to-class="-translate-y-1 opacity-0"
      >
        <p
          v-if="error"
          class="mt-3 rounded-xl bg-red-50 px-3 py-2 text-[13px] leading-relaxed text-red-600"
          role="alert"
        >
          {{ error }}
        </p>
      </Transition>

      <button
        v-ripple
        type="submit"
        class="group mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-brand-300 px-4 py-3 text-sm font-medium text-brand-800 transition duration-150 hover:bg-brand-400 active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-60"
        :disabled="submitting"
      >
        <Loader2 v-if="submitting" :size="16" class="animate-spin" />
        <span>{{ submitting ? '正在登录' : '登录' }}</span>
        <ArrowRight
          v-if="!submitting"
          :size="16"
          class="transition-transform duration-200 group-hover:translate-x-0.5"
        />
      </button>
    </form>
  </AuthShell>
</template>
