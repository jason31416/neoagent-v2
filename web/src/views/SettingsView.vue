<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, type Component } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  AlertTriangle,
  Cpu,
  Loader2,
  Network,
  Save,
  ScrollText,
  Search,
  Settings2,
  ShieldCheck,
  Undo2,
} from 'lucide-vue-next'
import { api, ApiError, type SchemaGroup, type SchemaValue } from '@/lib/api'
import { useConfirm } from '@/lib/confirm'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import SchemaField from '@/components/SchemaField.vue'

const groups = ref<SchemaGroup[]>([])
const original = ref<SchemaGroup[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const saving = ref(false)
const saved = ref(false)
const query = ref('')
const activeSection = ref('')
const paneEl = ref<HTMLElement | null>(null)

let spyLocked = false
let unlockTimer: number | undefined

const { confirmState, askConfirm, answerConfirm } = useConfirm()

const groupIcons: Record<string, Component> = {
  panel: Settings2,
  runtime: Cpu,
  network: Network,
  log: ScrollText,
  security: ShieldCheck,
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function valuesOf(list: SchemaGroup[]): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const group of list) {
    for (const field of group.fields) {
      if (field.type === 'readonly') continue
      values[field.key] = field.value
    }
  }
  return values
}

const dirty = computed(
  () => JSON.stringify(valuesOf(groups.value)) !== JSON.stringify(valuesOf(original.value)),
)

const visibleGroups = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return groups.value
  return groups.value
    .map((group) => ({
      ...group,
      fields: group.fields.filter((field) => field.label.toLowerCase().includes(keyword)),
    }))
    .filter((group) => group.fields.length > 0)
})

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后再试'
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const data = await api.settings()
    groups.value = clone(data.groups)
    original.value = clone(data.groups)
    activeSection.value = data.groups[0]?.id ?? ''
  } catch (error) {
    loadError.value = messageOf(error)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!dirty.value || saving.value) return
  saving.value = true
  saveError.value = null
  try {
    const data = await api.saveSettings(valuesOf(groups.value))
    groups.value = clone(data.groups)
    original.value = clone(data.groups)
    saved.value = true
    window.setTimeout(() => {
      saved.value = false
    }, 1800)
  } catch (error) {
    saveError.value = messageOf(error)
  } finally {
    saving.value = false
  }
}

function undo() {
  groups.value = clone(original.value)
  saveError.value = null
}

function updateField(key: string, value: SchemaValue) {
  for (const group of groups.value) {
    const field = group.fields.find((item) => item.key === key)
    if (field) {
      field.value = value
      return
    }
  }
}

function sectionElement(id: string): HTMLElement | null {
  return document.getElementById(`settings-${id}`)
}

function releaseSpy() {
  window.clearTimeout(unlockTimer)
  spyLocked = false
}

/**
 * 宽屏由右侧面板自己滚动，窄屏取消了嵌套滚动、改用外层 main。
 * 根据计算后的 overflow 选真正的滚动容器，跳转和高亮才能两边都对得上。
 */
function scrollHost(): HTMLElement | null {
  const pane = paneEl.value
  if (!pane) return null
  const overflowY = window.getComputedStyle(pane).overflowY
  if (overflowY === 'auto' || overflowY === 'scroll') return pane
  return pane.closest('main')
}

function goTo(id: string) {
  const scroller = scrollHost()
  const element = sectionElement(id)
  activeSection.value = id
  if (!scroller || !element) return

  /*
   * 只滚动内容区：scrollIntoView 会连带滚动外层页面，
   * 让分类导航在平滑滚动过程中跟着移动。
   * 滚动期间 scroll 事件的反向高亮会经过中间分组，先锁住避免来回闪动。
   */
  spyLocked = true
  window.clearTimeout(unlockTimer)
  unlockTimer = window.setTimeout(releaseSpy, 900)

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const top =
    element.getBoundingClientRect().top -
    scroller.getBoundingClientRect().top +
    scroller.scrollTop -
    8
  scroller.scrollTo({ top: Math.max(0, top), behavior: reduce ? 'auto' : 'smooth' })
}

/** 内容区滚动时同步分类高亮，不用额外引 IntersectionObserver。 */
function onPaneScroll() {
  if (spyLocked) return
  const scroller = scrollHost()
  if (!scroller) return
  const line = scroller.getBoundingClientRect().top + 56
  let current = groups.value[0]?.id ?? ''
  for (const group of groups.value) {
    const element = sectionElement(group.id)
    if (element && element.getBoundingClientRect().top <= line) current = group.id
  }
  // 最后一组内容不够滚到判定线时，滚到底就认为它是当前分组。
  const scrollable = scroller.scrollHeight > scroller.clientHeight + 1
  if (scrollable && scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 1) {
    current = groups.value[groups.value.length - 1]?.id ?? current
  }
  activeSection.value = current
}

function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void save()
  }
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return askConfirm({
    title: '放弃未保存的修改？',
    message: '系统设置还没有保存，离开后会丢失。',
    confirmText: '放弃',
    danger: true,
  })
})

let outerScrollEl: HTMLElement | null = null

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('keydown', handleKeydown)
  outerScrollEl = paneEl.value?.closest('main') ?? null
  outerScrollEl?.addEventListener('scroll', onPaneScroll, { passive: true })
  void load().then(() => nextTick(onPaneScroll))
})

onBeforeUnmount(() => {
  window.clearTimeout(unlockTimer)
  outerScrollEl?.removeEventListener('scroll', onPaneScroll)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="flex min-h-0 animate-fade-in flex-col lg:h-full">
    <header class="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
      <div class="flex min-w-0 flex-1 items-baseline gap-3">
        <h1
          class="min-w-0 truncate text-[22px] font-semibold leading-tight tracking-tight text-slate-900 sm:text-[26px]"
        >
          系统设置
        </h1>
        <span
          class="shrink-0 text-[12px] text-emerald-600 transition-opacity duration-150"
          :class="saved ? 'opacity-100' : 'opacity-0'"
          :aria-hidden="!saved"
        >
          已保存
        </span>
      </div>

      <div class="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:items-center sm:gap-1.5">
        <button
          type="button"
          class="press flex h-11 items-center justify-center gap-1.5 rounded-lg px-3 text-[12.5px] text-slate-500 hover:bg-panel hover:text-slate-800 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500 sm:h-9"
          :disabled="!dirty || saving"
          @click="undo"
        >
          <Undo2 :size="13" />
          撤销
        </button>
        <button
          type="button"
          class="press flex h-11 items-center justify-center gap-1.5 rounded-lg bg-brand-300 px-4 text-[12.5px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white disabled:cursor-default disabled:opacity-40 disabled:hover:bg-brand-300 disabled:hover:text-brand-800 sm:h-9"
          :disabled="!dirty || saving"
          @click="save"
        >
          <Loader2 v-if="saving" :size="13" class="animate-spin" />
          <Save v-else :size="13" />
          保存更改
        </button>
      </div>
    </header>

    <Transition name="notice">
      <div
        v-if="saveError"
        class="mt-4 flex items-start gap-2.5 rounded-xl bg-red-50 px-4 py-3 text-[12.5px] leading-relaxed text-red-700"
        role="alert"
      >
        <AlertTriangle :size="15" class="mt-0.5 shrink-0" />
        <span class="min-w-0">{{ saveError }}</span>
      </div>
    </Transition>

    <div class="mt-6 flex min-h-0 flex-col gap-4 lg:mt-8 lg:flex-1 lg:flex-row lg:gap-10">
      <aside
        class="scroll-thin flex w-full shrink-0 flex-col gap-2 px-1 pt-1 lg:w-49 lg:gap-0 lg:overflow-y-auto"
      >
        <div class="relative shrink-0">
          <Search
            :size="12"
            :stroke-width="2.2"
            class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400"
          />
          <input
            v-model="query"
            type="text"
            placeholder="搜索"
            spellcheck="false"
            class="h-10 w-full rounded-lg bg-panel pl-7 pr-2.5 text-[12px] text-slate-800 outline-none transition duration-150 placeholder:text-slate-300 focus:ring-4 focus:ring-brand-300/25 lg:h-8"
          />
        </div>

        <nav
          v-if="!loading"
          class="scrollbar-none flex gap-1.5 overflow-x-auto lg:mt-3 lg:block lg:space-y-0.5 lg:overflow-x-visible"
        >
          <button
            v-for="group in groups"
            :key="group.id"
            type="button"
            class="relative flex w-auto shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2.5 text-left transition-colors duration-150 lg:w-full lg:py-2 lg:whitespace-normal"
            :class="activeSection === group.id ? 'bg-brand-50' : 'text-slate-600 hover:bg-panel/70'"
            @click="goTo(group.id)"
          >
            <span
              v-if="activeSection === group.id"
              class="absolute left-0 top-1/2 ml-1 hidden h-4 w-0.75 -translate-y-1/2 rounded-full bg-brand-400 lg:block"
            ></span>
            <component
              :is="groupIcons[group.id] ?? null"
              :size="14"
              class="shrink-0"
              :class="activeSection === group.id ? 'text-brand-500' : 'text-slate-400'"
            />
            <span
              class="text-[13px] lg:flex-1"
              :class="activeSection === group.id ? 'font-medium text-brand-800' : ''"
            >
              {{ group.title }}
            </span>
          </button>
        </nav>
      </aside>

      <section
        ref="paneEl"
        class="scroll-thin min-w-0 pb-6 lg:flex-1 lg:overflow-y-auto lg:pr-1"
        @scroll.passive="onPaneScroll"
        @scrollend="releaseSpy"
        @wheel.passive="releaseSpy"
        @touchstart.passive="releaseSpy"
      >
        <div v-if="loading" class="space-y-4">
          <div class="h-8 w-40 animate-pulse rounded-lg bg-panel"></div>
          <div class="h-52 animate-pulse rounded-2xl bg-panel"></div>
          <div class="h-40 animate-pulse rounded-2xl bg-panel"></div>
        </div>

        <div
          v-else-if="loadError"
          class="flex items-start gap-2.5 rounded-2xl bg-red-50 px-4 py-3.5 text-red-700"
          role="alert"
        >
          <AlertTriangle :size="16" class="mt-0.5 shrink-0" />
          <div class="min-w-0 text-[13px] leading-relaxed">
            <p>{{ loadError }}</p>
            <button
              type="button"
              class="mt-1 font-medium underline decoration-red-300 underline-offset-2 transition hover:decoration-red-500"
              @click="load"
            >
              重试
            </button>
          </div>
        </div>

        <div
          v-else-if="visibleGroups.length === 0"
          class="rounded-2xl bg-panel px-6 py-16 text-center text-[13px] text-slate-400"
        >
          没有匹配「{{ query.trim() }}」的配置项
        </div>

        <div v-else class="space-y-4">
          <div
            v-for="group in visibleGroups"
            :id="`settings-${group.id}`"
            :key="group.id"
            class="scroll-mt-2 rounded-2xl"
          >
            <div class="flex items-center gap-2 px-4 pt-4 pb-1 sm:px-6">
              <span class="h-4 w-1 shrink-0 rounded-full bg-brand-400"></span>
              <h2 class="text-[13px] font-semibold text-brand-800">{{ group.title }}</h2>
            </div>

            <div class="px-4 pb-2 sm:px-6">
              <SchemaField
                v-for="field in group.fields"
                :key="field.key"
                :field="field"
                :model-value="field.value"
                @update:model-value="updateField(field.key, $event)"
              />
            </div>
          </div>
        </div>
      </section>
    </div>

    <ConfirmDialog
      :open="!!confirmState"
      :title="confirmState?.title ?? ''"
      :message="confirmState?.message"
      :confirm-text="confirmState?.confirmText"
      :danger="confirmState?.danger"
      @confirm="answerConfirm(true)"
      @cancel="answerConfirm(false)"
    />
  </div>
</template>
