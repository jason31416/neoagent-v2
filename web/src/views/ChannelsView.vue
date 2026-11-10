<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { AlertTriangle, Loader2, Save, Undo2 } from 'lucide-vue-next'
import { api, ApiError, type ChannelConfig, type ChannelStatus, type SchemaValue } from '@/lib/api'
import { useConfirm } from '@/lib/confirm'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import SchemaField from '@/components/SchemaField.vue'
import ToggleSwitch from '@/components/ToggleSwitch.vue'

const channels = ref<ChannelConfig[]>([])
const original = ref<ChannelConfig[]>([])
const activeId = ref<string | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const saving = ref(false)
const saved = ref(false)

const { confirmState, askConfirm, answerConfirm } = useConfirm()

const statusMeta: Record<ChannelStatus, { label: string; pill: string; dot: string }> = {
  running: { label: '运行中', pill: 'bg-emerald-50 text-emerald-700', dot: 'bg-emerald-500' },
  stopped: { label: '已停止', pill: 'bg-slate-100 text-slate-500', dot: 'bg-slate-300' },
  error: { label: '配置有误', pill: 'bg-red-50 text-red-600', dot: 'bg-red-500' },
  idle: { label: '未配置', pill: 'bg-slate-100 text-slate-500', dot: 'bg-slate-300' },
}

const active = computed(
  () => channels.value.find((channel) => channel.id === activeId.value) ?? channels.value[0],
)

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/** 收集一个频道要提交的值；只读项不提交，密钥回传的是掩码时由服务端识别。 */
function valuesOf(channel: ChannelConfig): Record<string, unknown> {
  const values: Record<string, unknown> = { enabled: channel.enabled }
  for (const group of channel.groups) {
    for (const field of group.fields) {
      if (field.type === 'readonly') continue
      values[field.key] = field.value
    }
  }
  return values
}

function collectAll(list: ChannelConfig[]): Record<string, Record<string, unknown>> {
  const result: Record<string, Record<string, unknown>> = {}
  for (const channel of list) result[channel.id] = valuesOf(channel)
  return result
}

const baseline = computed(() => collectAll(original.value))
const dirty = computed(
  () => JSON.stringify(collectAll(channels.value)) !== JSON.stringify(baseline.value),
)

function channelDirty(channel: ChannelConfig): boolean {
  return JSON.stringify(valuesOf(channel)) !== JSON.stringify(baseline.value[channel.id])
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后再试'
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const data = await api.channels()
    channels.value = clone(data.channels)
    original.value = clone(data.channels)
    if (!activeId.value || !data.channels.some((item) => item.id === activeId.value)) {
      activeId.value = data.channels[0]?.id ?? null
    }
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
    const data = await api.saveChannels(collectAll(channels.value))
    channels.value = clone(data.channels)
    original.value = clone(data.channels)
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
  channels.value = clone(original.value)
  saveError.value = null
}

function updateField(channel: ChannelConfig, key: string, value: SchemaValue) {
  for (const group of channel.groups) {
    const field = group.fields.find((item) => item.key === key)
    if (field) {
      field.value = value
      return
    }
  }
}

function setEnabled(channel: ChannelConfig, value: boolean) {
  channel.enabled = value
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
    message: '频道配置还没有保存，离开后会丢失。',
    confirmText: '放弃',
    danger: true,
  })
})

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('keydown', handleKeydown)
  void load()
})

onBeforeUnmount(() => {
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
          频道
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
      <aside class="scroll-thin flex w-full shrink-0 flex-col px-1 pt-1 lg:w-50 lg:overflow-y-auto">
        <div v-if="loading" class="flex gap-1.5 lg:block lg:space-y-2">
          <div
            v-for="index in 5"
            :key="index"
            class="h-10 w-24 shrink-0 animate-pulse rounded-lg bg-panel lg:h-9 lg:w-full"
          ></div>
        </div>

        <nav
          v-else
          class="scrollbar-none flex gap-1.5 overflow-x-auto lg:block lg:space-y-0.5 lg:overflow-x-visible"
        >
          <button
            v-for="channel in channels"
            :key="channel.id"
            type="button"
            class="relative flex w-auto shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2.5 text-left transition-colors duration-150 lg:w-full lg:py-2 lg:whitespace-normal"
            :class="activeId === channel.id ? 'bg-brand-50' : 'text-slate-600 hover:bg-panel/70'"
            @click="activeId = channel.id"
          >
            <span
              v-if="activeId === channel.id"
              class="absolute left-0 top-1/2 ml-1 hidden h-4 w-0.75 -translate-y-1/2 rounded-full bg-brand-400 lg:block"
            ></span>
            <span class="flex w-3.5 shrink-0 items-center justify-center">
              <span
                class="h-1.5 w-1.5 rounded-full"
                :class="statusMeta[channel.status].dot"
                :title="statusMeta[channel.status].label"
              ></span>
            </span>
            <span
              class="min-w-0 truncate text-[13px] lg:flex-1"
              :class="activeId === channel.id ? 'font-medium text-brand-800' : ''"
            >
              {{ channel.name }}
            </span>
            <span
              v-if="channelDirty(channel)"
              class="h-1.5 w-1.5 shrink-0 rounded-full bg-brand-400"
              title="有未保存的修改"
            ></span>
            <span
              class="shrink-0 rounded-full px-2 py-0.5 text-[10.5px]"
              :class="statusMeta[channel.status].pill"
            >
              {{ statusMeta[channel.status].label }}
            </span>
          </button>
        </nav>
      </aside>

      <section class="scroll-thin min-w-0 pb-6 lg:flex-1 lg:overflow-y-auto lg:pr-1">
        <div v-if="loading" class="space-y-4">
          <div class="h-8 w-40 animate-pulse rounded-lg bg-panel"></div>
          <div class="h-64 animate-pulse rounded-2xl bg-panel"></div>
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
          v-else-if="!active"
          class="rounded-2xl bg-panel px-6 py-16 text-center text-[13px] text-slate-400"
        >
          还没有可配置的频道
        </div>

        <template v-else>
          <div class="flex flex-wrap items-start justify-between gap-3 sm:gap-6">
            <div class="flex min-w-0 items-center gap-2.5">
              <h2
                class="truncate text-[18px] font-semibold tracking-tight text-slate-900 sm:text-[21px]"
              >
                {{ active.name }}
              </h2>
              <span
                class="shrink-0 rounded-full px-2.5 py-0.5 text-[11px]"
                :class="statusMeta[active.status].pill"
              >
                {{ statusMeta[active.status].label }}
              </span>
            </div>

            <div class="flex shrink-0 items-center gap-2.5">
              <span class="text-[12px] text-slate-400">启用</span>
              <ToggleSwitch
                :model-value="active.enabled"
                :label="`启用 ${active.name}`"
                @update:model-value="setEnabled(active, $event)"
              />
            </div>
          </div>

          <div class="mt-5 space-y-4">
            <section v-for="group in active.groups" :key="group.id" class="rounded-2xl">
              <div class="flex items-center gap-2 px-4 pt-4 pb-1 sm:px-6">
                <span class="h-4 w-1 shrink-0 rounded-full bg-brand-400"></span>
                <h3 class="text-[13px] font-semibold text-brand-800">{{ group.title }}</h3>
              </div>

              <div class="px-4 pb-2 sm:px-6">
                <SchemaField
                  v-for="field in group.fields"
                  :key="field.key"
                  :field="field"
                  :model-value="field.value"
                  @update:model-value="updateField(active, field.key, $event)"
                />
              </div>
            </section>
          </div>
        </template>
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
