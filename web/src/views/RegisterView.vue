<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, Loader2 } from 'lucide-vue-next'
import { api, ApiError } from '@/lib/api'
import { setToken } from '@/lib/auth'
import AuthShell from '@/layouts/AuthShell.vue'

const route = useRoute()
const router = useRouter()

const secret = computed(() => {
  const raw = route.query.token
  return typeof raw === 'string' ? raw.trim() : ''
})

const password = ref('')
const confirm = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)

const passwordEl = ref<HTMLInputElement | null>(null)

const tooShort = computed(() => password.value.length > 0 && password.value.length < 6)

onMounted(async () => {
  if (!secret.value) return
  await nextTick()
  passwordEl.value?.focus()
})

async function submit() {
  if (submitting.value || !secret.value) return

  error.value = null

  if (password.value.length < 6) {
    error.value = '密码至少 6 位'
    passwordEl.value?.focus()
    return
  }

  if (password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }

  submitting.value = true
  try {
    const res = await api.register(secret.value, password.value)
    setToken(res.token)
    await router.replace('/')
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) error.value = '绑定链接无效或已经使用过'
    else error.value = e instanceof Error ? e.message : '注册失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell title="设置面板密码" subtitle="">
    <template v-if="!secret">
      <div class="flex items-start gap-2.5 rounded-xl bg-amber-50 p-3.5 text-amber-800">
        <AlertTriangle :size="16" class="mt-0.5 shrink-0" />
        <div class="text-[13px] leading-relaxed">
          <p>绑定链接不完整，缺少必要的 token</p>
          <p class="mt-1.5 text-amber-700/80">
            请从后台日志中复制完整的链接再打开，链接形如
            <code class="rounded bg-white/70 px-1 py-0.5 font-mono text-[11px] text-amber-900"
              >http://.../register?token=...</code
            >
          </p>
        </div>
      </div>

      <RouterLink
        v-ripple
        to="/login"
        class="mt-5 flex w-full items-center justify-center rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 transition duration-150 hover:border-slate-300 hover:bg-panel active:scale-[0.99]"
      >
        返回登录
      </RouterLink>
    </template>

    <form v-else class="animate-fade-in" novalidate @submit.prevent="submit">
      <label for="password" class="block text-[13px] font-medium text-slate-600">新密码</label>
      <input
        id="password"
        ref="passwordEl"
        v-model="password"
        type="password"
        autocomplete="new-password"
        placeholder="至少 6 位"
        :aria-invalid="tooShort"
        class="mt-2 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none transition duration-150 placeholder:text-slate-400 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 disabled:bg-slate-50"
        :class="tooShort ? 'border-red-300' : ''"
        :disabled="submitting"
      />

      <label for="confirm" class="mt-5 block text-xs font-medium text-slate-600">确认密码</label>
      <input
        id="confirm"
        v-model="confirm"
        type="password"
        autocomplete="new-password"
        placeholder="再输入一次"
        class="mt-2 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 outline-none transition duration-150 placeholder:text-slate-400 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 disabled:bg-slate-50"
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
        <span>{{ submitting ? '正在保存' : '设置密码并登录' }}</span>
        <ArrowRight
          v-if="!submitting"
          :size="16"
          class="transition-transform duration-200 group-hover:translate-x-0.5"
        />
      </button>
    </form>
  </AuthShell>
</template>
