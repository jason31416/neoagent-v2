<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, Plus, Search, X } from 'lucide-vue-next'
import deepseekIcon from '@/assets/providers/deepseek.svg'
import openaiIcon from '@/assets/providers/openai.svg'
import openrouterIcon from '@/assets/providers/openrouter.svg'
import opencodeIcon from '@/assets/providers/opencode.svg'

export interface ProviderPreset {
  id: string
  name: string
  type: string
  baseUrl: string
  icon?: string
}

const props = defineProps<{ open: boolean; disabled?: boolean }>()
const emit = defineEmits<{ close: []; select: [preset: ProviderPreset | null] }>()
const presets: ProviderPreset[] = [
  {
    id: 'deepseek',
    name: 'DeepSeek',
    type: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    icon: deepseekIcon,
  },
  {
    id: 'openai',
    name: 'OpenAI',
    type: 'openai-compatible',
    baseUrl: 'https://api.openai.com/v1',
    icon: openaiIcon,
  },
  {
    id: 'openrouter',
    name: 'OpenRouter',
    type: 'openai-compatible',
    baseUrl: 'https://openrouter.ai/api/v1',
    icon: openrouterIcon,
  },
  {
    id: 'opencode',
    name: 'OpenCode',
    type: 'openai-compatible',
    baseUrl: 'https://opencode.ai/inference/openai/v1',
    icon: opencodeIcon,
  },
  {
    id: 'opencode-go',
    name: 'OpenCode Go',
    type: 'openai-compatible',
    baseUrl: 'https://opencode.ai/inference/go/openai/v1',
    icon: opencodeIcon,
  },
  {
    id: 'commandcode',
    name: 'Command Code',
    type: 'openai-compatible',
    baseUrl: 'https://api.commandcode.ai/provider/v1',
  },
  {
    id: 'mimo-code',
    name: 'MiMo Code',
    type: 'openai-compatible',
    baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1',
  },
]
const query = ref('')
const searchEl = ref<HTMLInputElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
const filtered = computed(() => {
  const value = query.value.trim().toLowerCase()
  return presets.filter((preset) =>
    `${preset.name} ${preset.baseUrl}`.toLowerCase().includes(value),
  )
})

function hostOf(baseUrl: string): string {
  return new URL(baseUrl).host
}

function close() {
  if (!props.disabled) emit('close')
}
function handleKeydown(event: KeyboardEvent) {
  if (!props.open || props.disabled) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopImmediatePropagation()
    close()
  }
  if (event.key !== 'Tab') return
  const elements = panelEl.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled)',
  )
  if (!elements?.length) {
    event.preventDefault()
    return
  }
  const first = elements[0]!
  const last = elements[elements.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
watch(
  () => props.open,
  async (open) => {
    if (open) {
      previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      query.value = ''
      window.addEventListener('keydown', handleKeydown, true)
      await nextTick()
      searchEl.value?.focus()
    } else {
      window.removeEventListener('keydown', handleKeydown, true)
      previousFocus?.focus()
    }
  },
)
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown, true))
</script>

<template>
  <Teleport to="body">
    <Transition name="provider-picker" :duration="{ enter: 140, leave: 100 }">
      <div
        v-if="open"
        class="fixed inset-0 z-40 flex items-center justify-center bg-slate-900/25 px-4"
        @click.self="close"
      >
        <section
          ref="panelEl"
          role="dialog"
          aria-modal="true"
          aria-labelledby="provider-picker-title"
          :aria-busy="disabled"
          class="dialog-panel flex max-h-[min(80vh,720px)] w-full max-w-[640px] flex-col overflow-hidden rounded-3xl bg-white shadow-[0_32px_100px_-24px_rgba(15,23,42,0.35)]"
        >
          <header class="flex shrink-0 items-center justify-between px-7 pb-5 pt-7">
            <h2
              id="provider-picker-title"
              class="text-[21px] font-semibold tracking-tight text-slate-900"
            >
              添加提供商
            </h2>
            <button
              type="button"
              aria-label="关闭"
              :disabled="disabled"
              class="grid h-8 w-8 place-items-center rounded-full text-slate-400 transition hover:bg-panel hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-brand-400"
              @click="close"
            >
              <X :size="18" />
            </button>
          </header>
          <div
            class="mx-7 mb-5 flex shrink-0 items-center gap-2.5 rounded-xl bg-panel px-3.5 focus-within:ring-2 focus-within:ring-brand-300"
          >
            <Search :size="16" class="shrink-0 text-slate-400" />
            <input
              ref="searchEl"
              v-model="query"
              aria-label="搜索提供商"
              placeholder="搜索提供商"
              :disabled="disabled"
              class="h-11 min-w-0 flex-1 border-0 bg-transparent text-[13px] text-slate-800 outline-none placeholder:text-slate-400"
            />
          </div>
          <div class="scroll-thin min-h-0 overflow-y-auto px-7 pb-7">
            <div v-if="filtered.length" class="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <button
                v-for="preset in filtered"
                :key="preset.id"
                type="button"
                :disabled="disabled"
                class="group flex h-[72px] min-w-0 items-center gap-3 rounded-xl border border-hairline bg-white px-4 text-left transition-colors duration-150 hover:border-brand-300 hover:bg-brand-300/10 focus-visible:ring-2 focus-visible:ring-brand-400 disabled:opacity-50"
                @click="emit('select', preset)"
              >
                <span class="h-7 w-7 shrink-0" aria-hidden="true">
                  <img v-if="preset.icon" :src="preset.icon" alt="" class="h-full w-full object-contain" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-[14px] font-semibold text-slate-800">{{
                    preset.name
                  }}</span>
                  <span class="mt-1 block truncate font-mono text-[10px] text-slate-400">{{
                    hostOf(preset.baseUrl)
                  }}</span>
                </span>
                <ArrowRight
                  :size="15"
                  class="shrink-0 text-slate-300 transition-colors duration-150 group-hover:text-brand-700"
                />
              </button>
            </div>
            <div v-else class="py-16 text-center text-[13px] text-slate-400">没有匹配的提供商</div>
          </div>
          <footer class="shrink-0 border-t border-hairline bg-panel/40 px-7 py-5">
            <button
              type="button"
              :disabled="disabled"
              class="group flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand-300 text-[13px] font-medium text-brand-800 transition hover:bg-brand-400 hover:text-white focus-visible:ring-2 focus-visible:ring-brand-400 focus-visible:ring-offset-2 disabled:opacity-50"
              @click="emit('select', null)"
            >
              <Plus :size="16" />创建自定义提供商
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 仅淡入淡出：不缩放文字，也不让卡片移动到滚动裁切区域。 */
.provider-picker-enter-active {
  transition: opacity 140ms ease-out;
}

.provider-picker-leave-active {
  transition: opacity 100ms ease-in;
  pointer-events: none;
}

.provider-picker-enter-from,
.provider-picker-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .provider-picker-enter-active,
  .provider-picker-leave-active {
    transition: none;
  }
}
</style>
