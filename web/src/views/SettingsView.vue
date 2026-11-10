<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { AlertTriangle, Loader2, Save, Search, Undo2 } from 'lucide-vue-next'
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

const { confirmState, askConfirm, answerConfirm } = useConfirm()

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

function goTo(id: string) {
  activeSection.value = id
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  sectionElement(id)?.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' })
}

/** 内容区滚动时同步左侧高亮，不用额外引 IntersectionObserver。 */
function onPaneScroll() {
  const pane = paneEl.value
  if (!pane) return
  const line = pane.getBoundingClientRect().top + 56
  let current = groups.value[0]?.id ?? ''
  for (const group of groups.value) {
    const element = sectionElement(group.id)
    if (element && element.getBoundingClientRect().top <= line) current = group.id
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

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('keydown', handleKeydown)
  void load().then(() => nextTick(onPaneScroll))
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="flex h-full min-h-0 animate-fade-in flex-col">
    <header class="flex shrink-0 items-end justify-between gap-6">
      <h1 class="text-[26px] font-semibold leading-tight tracking-tight text-slate-900">
        系统设置
      </h1>

      <div class="flex items-center gap-1.5">
        <span
          class="mr-1 text-[12px] text-emerald-600 transition-opacity duration-150"
          :class="saved ? 'opacity-100' : 'opacity-0'"
          :aria-hidden="!saved"
        >
          已保存
        </span>
        <button
          type="button"
          class="press flex h-9 items-center gap-1.5 rounded-lg px-3 text-[12.5px] text-slate-500 hover:bg-panel hover:text-slate-800 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
          :disabled="!dirty || saving"
          @click="undo"
        >
          <Undo2 :size="13" />
          撤销
        </button>
        <button
          type="button"
          class="press flex h-9 items-center gap-1.5 rounded-lg bg-brand-300 px-4 text-[12.5px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white disabled:cursor-default disabled:opacity-40 disabled:hover:bg-brand-300 disabled:hover:text-brand-800"
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

    <div class="mt-8 flex min-h-0 flex-1 gap-10">
      <aside class="scroll-thin flex w-[196px] shrink-0 flex-col overflow-y-auto pr-1">
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
            class="h-8 w-full rounded-lg border border-slate-200 bg-white pl-7 pr-2.5 text-[12px] text-slate-800 outline-none transition duration-150 placeholder:text-slate-300 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
          />
        </div>

        <nav v-if="!loading" class="mt-3 space-y-0.5">
          <button
            v-for="group in groups"
            :key="group.id"
            type="button"
            class="relative flex w-full items-center gap-2 rounded-lg py-2 pl-3 pr-2 text-left transition-colors duration-150"
            :class="activeSection === group.id ? 'bg-brand-50' : 'text-slate-600 hover:bg-panel/70'"
            @click="goTo(group.id)"
          >
            <span
              v-if="activeSection === group.id"
              class="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-full bg-brand-400"
            ></span>
            <span
              class="flex-1 text-[13px]"
              :class="activeSection === group.id ? 'font-medium text-brand-800' : ''"
            >
              {{ group.title }}
            </span>
          </button>
        </nav>
      </aside>

      <section
        ref="paneEl"
        class="scroll-thin min-w-0 flex-1 overflow-y-auto pb-6 pr-1"
        @scroll.passive="onPaneScroll"
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
            class="scroll-mt-2 overflow-hidden rounded-2xl border border-hairline bg-white"
          >
            <div class="border-b border-hairline bg-panel/40 px-6 py-2.5">
              <h2 class="text-[12px] font-medium text-slate-500">{{ group.title }}</h2>
            </div>

            <div class="divide-y divide-hairline px-6">
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
