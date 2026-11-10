<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  AlertTriangle,
  Check,
  ChevronDown,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  X,
} from 'lucide-vue-next'
import {
  api,
  ApiError,
  type ModelProvider,
  type ProviderInput,
  type ProviderModel,
  type ProviderStatus,
  type RemoteModel,
} from '@/lib/api'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import SelectField from '@/components/SelectField.vue'

interface EditableModel extends ProviderModel {
  /** 仅前端使用的稳定 key：新建的模型还没有 id，不能拿 id 当 key。 */
  uid: string
}

interface DraftProvider {
  id: string
  name: string
  type: string
  baseUrl: string
  status: ProviderStatus
  apiKeySet: boolean
  apiKeyPreview: string | null
  models: EditableModel[]
}

const providerTypes = [
  { value: 'openai-compatible', label: 'OpenAI 兼容' },
  { value: 'deepseek', label: 'DeepSeek' },
  { value: 'local', label: '本地推理' },
]

const statusMeta: Record<ProviderStatus, { label: string; pill: string }> = {
  connected: { label: '已连接', pill: 'bg-emerald-50 text-emerald-700' },
  untested: { label: '未测试', pill: 'bg-slate-100 text-slate-500' },
  error: { label: '连接失败', pill: 'bg-red-50 text-red-600' },
}

const providers = ref<ModelProvider[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const activeId = ref<string | null>(null)
const draft = ref<DraftProvider>(blankDraft())
const baseline = ref('')
const saving = ref(false)
const testing = ref(false)
const showSaved = ref(false)
const formError = ref<string | null>(null)
const expanded = ref<string[]>([])
const replacingKey = ref(false)
const pendingKey = ref('')

// 标签下划线用一个共享元素滑动，比每个标签各挂一条线更连贯。
const tabsEl = ref<HTMLElement | null>(null)
const underline = ref({ x: 0, w: 0 })
const underlineReady = ref(false)
// 只在真正切换提供商时换 key，保存新建的提供商不会让内容区闪一下。
const viewKey = ref('')
const removing = ref<string[]>([])
let tabObserver: ResizeObserver | undefined

const pullOpen = ref(false)
const pullLoading = ref(false)
const pullError = ref<string | null>(null)
const remoteModels = ref<RemoteModel[]>([])
const pullSelected = ref<string[]>([])
const pullQuery = ref('')
const pullSearchEl = ref<HTMLInputElement | null>(null)
const pullListEl = ref<HTMLElement | null>(null)
/** 列表高度量一次就固定住，搜索时结果变少也不会把对话框抽短。 */
const pullListHeight = ref<number | null>(null)

const filteredRemote = computed(() => {
  const query = pullQuery.value.trim().toLowerCase()
  if (!query) return remoteModels.value
  return remoteModels.value.filter(
    (model) =>
      model.id.toLowerCase().includes(query) || model.displayName.toLowerCase().includes(query),
  )
})

/** 当前筛选结果里还能勾选的（已经添加过的不能再选）。 */
const pullSelectable = computed(() => filteredRemote.value.filter((model) => !isAdded(model.id)))

const allSelectableSelected = computed(
  () =>
    pullSelectable.value.length > 0 &&
    pullSelectable.value.every((model) => pullSelected.value.includes(model.id)),
)

const addedRemoteCount = computed(
  () => remoteModels.value.filter((model) => isAdded(model.id)).length,
)

const confirmState = ref<{
  title: string
  message?: string
  confirmText?: string
  danger?: boolean
} | null>(null)
let confirmResolve: ((value: boolean) => void) | null = null

let uidSeq = 0
let savedTimer: number | undefined

const isNew = computed(() => activeId.value === '__new__')
const hasDraft = computed(() => activeId.value !== null)
const totalModels = computed(() =>
  providers.value.reduce((total, provider) => total + provider.models.length, 0),
)
const dirty = computed(() => snapshot() !== baseline.value)

function nextUid(): string {
  uidSeq += 1
  return `m${uidSeq}`
}

function blankDraft(): DraftProvider {
  return {
    id: '',
    name: '',
    type: 'openai-compatible',
    baseUrl: '',
    status: 'untested',
    apiKeySet: false,
    apiKeyPreview: null,
    models: [],
  }
}

function toDraft(provider: ModelProvider): DraftProvider {
  return {
    id: provider.id,
    name: provider.name,
    type: provider.type,
    baseUrl: provider.baseUrl,
    status: provider.status,
    apiKeySet: provider.apiKeySet,
    apiKeyPreview: provider.apiKeyPreview,
    models: provider.models.map((model) => ({ ...model, uid: nextUid() })),
  }
}

function toPayload(provider: DraftProvider): ProviderInput {
  return {
    name: provider.name.trim(),
    type: provider.type,
    baseUrl: provider.baseUrl.trim(),
    // 正在做收起动画的行已经算删除，避免这 220ms 内点保存又把它写回去。
    models: provider.models
      .filter((model) => !removing.value.includes(model.uid))
      .map((model) => {
        const contextLength = Number(model.contextLength)
        const maxImage = Number(model.maxImage)
        return {
          id: model.id.trim(),
          displayName: model.displayName.trim() || model.id.trim(),
          contextLength:
            Number.isFinite(contextLength) && contextLength > 0 ? Math.round(contextLength) : 0,
          maxImage: Number.isFinite(maxImage) && maxImage > 0 ? Math.round(maxImage) : -1,
        }
      }),
  }
}

function snapshot(): string {
  return JSON.stringify({ provider: toPayload(draft.value), key: pendingKey.value })
}

baseline.value = snapshot()

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '操作失败，请稍后再试'
}

function markSaved() {
  showSaved.value = true
  if (savedTimer !== undefined) window.clearTimeout(savedTimer)
  savedTimer = window.setTimeout(() => {
    showSaved.value = false
  }, 2000)
}

function askConfirm(options: {
  title: string
  message?: string
  confirmText?: string
  danger?: boolean
}): Promise<boolean> {
  confirmState.value = options
  return new Promise((resolve) => {
    confirmResolve = resolve
  })
}

function answerConfirm(value: boolean) {
  confirmResolve?.(value)
  confirmResolve = null
  confirmState.value = null
}

function openProvider(id: string) {
  const provider = providers.value.find((item) => item.id === id)
  if (!provider) return
  activeId.value = id
  viewKey.value = id
  removing.value = []
  draft.value = toDraft(provider)
  pendingKey.value = ''
  replacingKey.value = false
  expanded.value = []
  formError.value = null
  baseline.value = snapshot()
}

function fallbackToFirst() {
  const first = providers.value[0]
  if (first) {
    openProvider(first.id)
    return
  }
  activeId.value = null
  viewKey.value = ''
  removing.value = []
  draft.value = blankDraft()
  pendingKey.value = ''
  replacingKey.value = false
  expanded.value = []
  formError.value = null
  baseline.value = snapshot()
}

async function ensureDiscard(): Promise<boolean> {
  if (!dirty.value) return true
  return askConfirm({
    title: '放弃未保存的修改？',
    message: '当前提供商的改动还没有保存，离开后会丢失。',
    confirmText: '放弃',
    danger: true,
  })
}

async function switchProvider(id: string) {
  if (id === activeId.value) return
  if (!(await ensureDiscard())) return
  openProvider(id)
}

async function startNew() {
  if (isNew.value) return
  if (!(await ensureDiscard())) return
  activeId.value = '__new__'
  viewKey.value = '__new'
  removing.value = []
  draft.value = blankDraft()
  pendingKey.value = ''
  replacingKey.value = false
  expanded.value = []
  formError.value = null
  baseline.value = snapshot()
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const data = await api.models()
    providers.value = data.providers
    fallbackToFirst()
  } catch (error) {
    loadError.value = messageOf(error)
  } finally {
    loading.value = false
  }
}

function validate(): string | null {
  if (!draft.value.name.trim()) return '请填写提供商名称'
  if (!draft.value.baseUrl.trim()) return '请填写接口地址'

  const ids = draft.value.models.map((model) => model.id.trim())
  if (ids.some((id) => !id)) return '每个模型都需要填写模型 id'

  const seen = new Set<string>()
  for (const id of ids) {
    if (seen.has(id)) return `模型 id 重复：${id}`
    seen.add(id)
  }

  const usedElsewhere = providers.value
    .filter((provider) => provider.id !== draft.value.id)
    .flatMap((provider) => provider.models.map((model) => model.id))
  const conflict = ids.find((id) => usedElsewhere.includes(id))
  if (conflict) return `模型 id 已被其他提供商使用：${conflict}`

  return null
}

async function save(): Promise<boolean> {
  if (!hasDraft.value) return false
  const problem = validate()
  if (problem) {
    formError.value = problem
    return false
  }

  saving.value = true
  formError.value = null
  try {
    const payload = toPayload(draft.value)
    if (pendingKey.value) payload.apiKey = pendingKey.value

    const result = isNew.value
      ? await api.createProvider(payload)
      : await api.updateProvider(draft.value.id, payload)

    const provider = result.provider
    providers.value = isNew.value
      ? [...providers.value, provider]
      : providers.value.map((item) => (item.id === provider.id ? provider : item))

    // 保存后沿用原来的 uid，展开状态才不会莫名收起。
    const previousUids = draft.value.models.map((model) => model.uid)
    const nextDraft = toDraft(provider)
    nextDraft.models = nextDraft.models.map((model, index) => ({
      ...model,
      uid: previousUids[index] ?? model.uid,
    }))

    activeId.value = provider.id
    draft.value = nextDraft
    pendingKey.value = ''
    replacingKey.value = false
    expanded.value = expanded.value.filter((uid) =>
      nextDraft.models.some((model) => model.uid === uid),
    )
    baseline.value = snapshot()
    markSaved()
    return true
  } catch (error) {
    formError.value = messageOf(error)
    return false
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  if (!hasDraft.value || isNew.value) return
  if (dirty.value && !(await save())) return

  testing.value = true
  formError.value = null
  try {
    const { provider } = await api.testProvider(draft.value.id)
    providers.value = providers.value.map((item) => (item.id === provider.id ? provider : item))
    draft.value.status = provider.status
    if (provider.status === 'error') {
      formError.value = '连接失败，请检查接口地址和密钥'
    }
  } catch (error) {
    formError.value = messageOf(error)
  } finally {
    testing.value = false
  }
}

async function deleteProvider() {
  if (!hasDraft.value || isNew.value) return
  const id = draft.value.id
  const name = draft.value.name.trim() || '这个提供商'
  const ok = await askConfirm({
    title: `删除 ${name}？`,
    message: '这个提供商和它的模型配置会一起删除，无法撤销。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return

  try {
    await api.deleteProvider(id)
    providers.value = providers.value.filter((item) => item.id !== id)
    fallbackToFirst()
  } catch (error) {
    formError.value = messageOf(error)
  }
}

function isExpanded(uid: string): boolean {
  return expanded.value.includes(uid)
}

function toggleModel(uid: string) {
  expanded.value = isExpanded(uid)
    ? expanded.value.filter((item) => item !== uid)
    : [...expanded.value, uid]
}

function revealActiveTab() {
  void nextTick(() => {
    const container = tabsEl.value
    const active = container?.querySelector<HTMLElement>('[data-tab-active="true"]')
    if (!container || !active) return

    const left = active.offsetLeft
    const right = left + active.offsetWidth
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const behavior: ScrollBehavior = reduce ? 'auto' : 'smooth'

    if (left < container.scrollLeft) {
      container.scrollTo({ left: Math.max(0, left - 16), behavior })
    } else if (right > container.scrollLeft + container.clientWidth) {
      container.scrollTo({ left: right - container.clientWidth + 16, behavior })
    }
  })
}

function measureUnderline() {
  const container = tabsEl.value
  if (!container) return
  const active = container.querySelector<HTMLElement>('[data-tab-active="true"]')
  if (!active) return

  underline.value = { x: active.offsetLeft, w: active.offsetWidth }
  if (!underlineReady.value) {
    requestAnimationFrame(() => {
      underlineReady.value = true
    })
  }
}

function scrollToModel(uid: string) {
  void nextTick(() => {
    const el = document.querySelector<HTMLElement>(`[data-model-uid="${uid}"]`)
    const scroller = el?.closest<HTMLElement>('main')
    if (!el || !scroller) return
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const top =
      scroller.scrollTop +
      el.getBoundingClientRect().top -
      scroller.getBoundingClientRect().top -
      120
    scroller.scrollTo({ top: Math.max(0, top), behavior: reduce ? 'auto' : 'smooth' })
  })
}

function addModel() {
  if (!hasDraft.value) return
  const uid = nextUid()
  draft.value.models.push({
    uid,
    id: '',
    displayName: '',
    contextLength: 131072,
    maxImage: -1,
  })

  // 先让行以收起状态挂载，下一帧再展开，这样高度过渡才会真正播放。
  void nextTick(() => {
    requestAnimationFrame(() => {
      expanded.value = [...expanded.value, uid]
      scrollToModel(uid)
    })
  })
}

function removeModel(uid: string) {
  if (removing.value.includes(uid)) return
  removing.value = [...removing.value, uid]
  expanded.value = expanded.value.filter((item) => item !== uid)

  // 等高度收完再从数组里拿掉，避免下面的内容瞬间上跳。
  window.setTimeout(() => {
    draft.value.models = draft.value.models.filter((model) => model.uid !== uid)
    removing.value = removing.value.filter((item) => item !== uid)
  }, 220)
}

function toggleImage(model: EditableModel) {
  model.maxImage = model.maxImage > 0 ? -1 : 600
}

/** 只取数字：type="number" 在聚焦时会冒出系统自带的小箭头，和这里的排版对不上。 */
function digitsOf(event: Event): number {
  const raw = (event.target as HTMLInputElement).value.replace(/\D/g, '')
  if (!raw) return 0
  return Math.min(Number(raw), 999_999_999)
}

function updateContext(model: EditableModel, event: Event) {
  model.contextLength = digitsOf(event)
}

function updateMaxImage(model: EditableModel, event: Event) {
  model.maxImage = digitsOf(event) || -1
}

function formatContext(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '未设置'
  // 直接显示精确值：128K 在不同厂商那里分别是 128,000 和 131,072，缩写会对不上。
  return new Intl.NumberFormat('zh-CN').format(Math.round(value))
}

function startReplaceKey() {
  replacingKey.value = true
  pendingKey.value = ''
}

function cancelReplaceKey() {
  replacingKey.value = false
  pendingKey.value = ''
}

function isAdded(id: string): boolean {
  return draft.value.models.some((model) => model.id === id)
}

function toggleRemote(id: string) {
  pullSelected.value = pullSelected.value.includes(id)
    ? pullSelected.value.filter((item) => item !== id)
    : [...pullSelected.value, id]
}

function toggleSelectAll() {
  if (allSelectableSelected.value) {
    const removable = new Set(pullSelectable.value.map((model) => model.id))
    pullSelected.value = pullSelected.value.filter((id) => !removable.has(id))
    return
  }

  const next = new Set(pullSelected.value)
  for (const model of pullSelectable.value) next.add(model.id)
  pullSelected.value = [...next]
}

async function openPull() {
  if (!hasDraft.value || isNew.value) return
  if (dirty.value && !(await save())) return

  pullOpen.value = true
  pullLoading.value = true
  pullError.value = null
  pullSelected.value = []
  remoteModels.value = []
  pullQuery.value = ''
  pullListHeight.value = null

  try {
    const data = await api.remoteModels(draft.value.id)
    remoteModels.value = data.models
  } catch (error) {
    pullError.value = messageOf(error)
  } finally {
    pullLoading.value = false
  }

  // 列表加载完量一次内容高度并固定下来：搜索时结果变少也不会把对话框抽短。
  await nextTick()
  pullListHeight.value = measurePullList()
}

function measurePullList(): number | null {
  const el = pullListEl.value
  if (!el) return null
  // 窄屏弹窗贴底展开，列表要留出更多高度给标题、搜索栏和底部按钮。
  const compact = window.matchMedia('(max-width: 639px)').matches
  const min = compact ? 120 : 160
  const max = Math.max(
    min,
    compact
      ? Math.round(window.innerHeight * 0.42)
      : Math.min(420, Math.round(window.innerHeight * 0.52)),
  )
  return Math.round(Math.min(Math.max(el.scrollHeight, min), max))
}

// 搜索框是条件渲染的，挂载出来就直接聚焦，打开后可以立刻敲字。
watch(pullSearchEl, (el) => el?.focus(), { flush: 'post' })

function importSelected() {
  let firstUid: string | null = null
  for (const id of pullSelected.value) {
    if (isAdded(id)) continue
    const remote = remoteModels.value.find((model) => model.id === id)
    const uid = nextUid()
    draft.value.models.push({
      uid,
      id,
      displayName: remote?.displayName ?? id,
      contextLength: remote?.contextLength ?? 0,
      maxImage: remote?.maxImage ?? -1,
    })
    if (!firstUid) firstUid = uid
  }

  pullOpen.value = false
  if (!firstUid) return

  // 导入多个时只展开第一个，避免一次铺开一屏表单。
  const expandUid = firstUid
  void nextTick(() => {
    requestAnimationFrame(() => {
      expanded.value = [...expanded.value, expandUid]
      scrollToModel(expandUid)
    })
  })
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pullOpen.value) {
    // 拉取列表可能很长，Esc 随时能退出。
    event.preventDefault()
    pullOpen.value = false
    return
  }

  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (hasDraft.value && dirty.value && !saving.value) void save()
  }
}

watch(
  tabsEl,
  (el) => {
    tabObserver?.disconnect()
    if (!el) return
    tabObserver ??= new ResizeObserver(() => measureUnderline())
    tabObserver.observe(el)
    void nextTick(measureUnderline)
  },
  { flush: 'post' },
)

watch(activeId, () => revealActiveTab(), { flush: 'post' })

watch(
  [activeId, () => providers.value.length, isNew, () => draft.value.name],
  () => {
    void nextTick(measureUnderline)
  },
  { flush: 'post' },
)

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return askConfirm({
    title: '放弃未保存的修改？',
    message: '当前提供商的改动还没有保存，离开后会丢失。',
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
  if (savedTimer !== undefined) window.clearTimeout(savedTimer)
  tabObserver?.disconnect()
})
</script>

<template>
  <div class="animate-fade-in">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div class="min-w-0">
        <h1 class="text-[22px] font-semibold leading-tight tracking-tight text-slate-900 sm:text-[28px]">模型提供商</h1>
      </div>

      <button
        type="button"
        class="press flex h-10 shrink-0 items-center gap-1.5 rounded-xl bg-brand-300 px-4 text-[13px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white disabled:cursor-default disabled:opacity-50 disabled:hover:bg-brand-300 disabled:hover:text-brand-800"
        :disabled="loading"
        @click="startNew"
      >
        <Plus :size="15" :stroke-width="2.4" />
        添加提供商
      </button>
    </header>

    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="-translate-y-1 opacity-0"
      leave-active-class="transition duration-150 ease-in"
      leave-to-class="-translate-y-1 opacity-0"
    >
      <div
        v-if="loadError"
        class="mt-7 flex items-start gap-2.5 rounded-2xl bg-red-50 px-4 py-3.5 text-red-700"
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
    </Transition>

    <!-- 载入中 -->
    <div v-if="loading" class="mt-10 space-y-4">
      <div class="h-10 w-64 animate-pulse rounded-lg bg-panel"></div>
      <div class="h-[104px] animate-pulse rounded-2xl bg-panel"></div>
      <div class="h-[200px] animate-pulse rounded-2xl bg-panel"></div>
    </div>

    <template v-else-if="hasDraft">
      <!-- 提供商标签页 -->
      <div v-if="providers.length || isNew" class="relative mt-6 border-b border-hairline sm:mt-10">
        <div
          ref="tabsEl"
          class="scrollbar-none relative flex items-center gap-5 overflow-x-auto sm:gap-8"
        >
          <button
            v-for="provider in providers"
            :key="provider.id"
            type="button"
            :data-tab-active="activeId === provider.id"
            class="shrink-0 whitespace-nowrap pb-3 text-[14px] transition-colors duration-150 sm:pb-3.5 sm:text-[16px]"
            :class="
              activeId === provider.id
                ? 'font-medium text-brand-800'
                : 'text-slate-500 hover:text-slate-800'
            "
            @click="switchProvider(provider.id)"
          >
            {{ provider.name }}
          </button>

          <button
            v-if="isNew"
            type="button"
            :data-tab-active="isNew"
            class="shrink-0 whitespace-nowrap pb-3 text-[14px] font-medium text-brand-800 sm:pb-3.5 sm:text-[16px]"
          >
            {{ draft.name.trim() || '新提供商' }}
          </button>

          <span
            class="pointer-events-none absolute bottom-0 left-0 h-[2px] rounded-full bg-brand-400"
            :class="underlineReady ? 'transition-[transform,width] duration-200 ease-out' : ''"
            :style="{
              width: `${underline.w}px`,
              transform: `translateX(${underline.x}px)`,
              opacity: underlineReady ? 1 : 0,
            }"
          ></span>
        </div>
      </div>

      <Transition name="tab" mode="out-in">
        <section :key="viewKey" class="mt-6">
          <div class="flex flex-wrap items-center justify-between gap-3 sm:gap-8">
            <div class="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
              <h2 class="truncate text-[18px] font-semibold tracking-tight text-slate-900 sm:text-[21px]">
                {{ draft.name.trim() || '新提供商' }}
              </h2>
              <span
                class="min-w-[64px] shrink-0 rounded-full px-2.5 py-1 text-center text-[11px] transition-colors duration-200"
                :class="statusMeta[draft.status].pill"
              >
                {{ statusMeta[draft.status].label }}
              </span>
              <span v-if="draft.baseUrl" class="hidden min-w-0 truncate font-mono text-[12px] text-slate-400 sm:inline">
                {{ draft.baseUrl }}
              </span>
            </div>

            <div class="flex w-full shrink-0 items-center gap-2 sm:w-auto">
              <span
                class="w-10 shrink-0 text-right text-[12px] text-slate-400 transition-opacity duration-150"
                :class="showSaved ? 'opacity-100' : 'opacity-0'"
                :aria-hidden="!showSaved"
              >
                已保存
              </span>

              <button
                type="button"
                class="press flex h-10 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-lg px-3.5 text-[12.5px] text-slate-600 hover:bg-panel hover:text-slate-900 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-600 sm:h-9 sm:min-w-[88px] sm:flex-none"
                :disabled="isNew || testing || saving"
                :title="isNew ? '保存后才能测试' : undefined"
                @click="testConnection"
              >
                <Loader2 v-if="testing" :size="13" class="animate-spin" />
                {{ testing ? '测试中' : '测试连接' }}
              </button>

              <button
                type="button"
                class="press flex h-10 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-lg bg-brand-300 px-4 text-[12.5px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white disabled:cursor-default disabled:opacity-40 disabled:hover:bg-brand-300 disabled:hover:text-brand-800 sm:h-9 sm:min-w-[88px] sm:flex-none"
                :disabled="!dirty || saving"
                @click="save"
              >
                <Loader2 v-if="saving" :size="13" class="animate-spin" />
                {{ saving ? '保存中' : '保存更改' }}
              </button>
            </div>
          </div>

          <Transition name="notice">
            <div
              v-if="formError"
              class="mt-4 flex items-start gap-2.5 rounded-xl bg-red-50 px-4 py-3 text-[12.5px] leading-relaxed text-red-700"
              role="alert"
            >
              <AlertTriangle :size="15" class="mt-0.5 shrink-0" />
              <span class="min-w-0">{{ formError }}</span>
            </div>
          </Transition>

          <!-- 连接 -->
          <div class="mt-6 rounded-2xl bg-panel p-4 sm:p-6">
            <div class="grid grid-cols-1 gap-4 sm:gap-5 md:grid-cols-2 xl:grid-cols-4">
              <label class="block">
                <span class="mb-1.5 block text-[12px] text-slate-500">名称</span>
                <input
                  v-model="draft.name"
                  type="text"
                  placeholder="DeepSeek"
                  class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 text-[13.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                />
              </label>

              <div class="block">
                <span class="mb-1.5 block text-[12px] text-slate-500">类型</span>
                <SelectField v-model="draft.type" :options="providerTypes" label="类型" />
              </div>

              <label class="block">
                <span class="mb-1.5 block text-[12px] text-slate-500">接口地址</span>
                <input
                  v-model="draft.baseUrl"
                  type="text"
                  spellcheck="false"
                  placeholder="https://api.deepseek.com"
                  class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 font-mono text-[12.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                />
              </label>

              <div>
                <span class="mb-1.5 block text-[12px] text-slate-500">API 密钥</span>

                <div v-if="draft.apiKeySet && !replacingKey" class="flex items-center gap-2">
                  <div
                    class="flex h-10 min-w-0 flex-1 items-center justify-between gap-2 rounded-lg border border-slate-200 bg-white px-3.5"
                  >
                    <span class="truncate font-mono text-[12px] text-slate-500">
                      {{ draft.apiKeyPreview }}
                    </span>
                    <button
                      type="button"
                      class="shrink-0 text-[12px] font-medium text-brand-700 hover:text-brand-800"
                      @click="startReplaceKey"
                    >
                      替换
                    </button>
                  </div>
                </div>

                <div v-else class="flex items-center gap-2">
                  <input
                    v-model="pendingKey"
                    type="text"
                    autocomplete="off"
                    spellcheck="false"
                    :placeholder="draft.apiKeySet ? '输入新的密钥' : 'sk-...'"
                    class="h-10 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-3.5 font-mono text-[12.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                  />
                  <button
                    v-if="replacingKey"
                    type="button"
                    class="shrink-0 text-[12px] text-slate-500 hover:text-slate-700"
                    @click="cancelReplaceKey"
                  >
                    取消
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- 模型 -->
          <div class="mt-8 sm:mt-10">
            <div class="mb-3.5 flex flex-wrap items-center justify-between gap-2">
              <h3 class="text-[14px] font-medium text-slate-800">
                模型
                <span class="ml-1 text-[12px] font-normal text-slate-400">{{
                  draft.models.length
                }}</span>
              </h3>

              <div class="flex items-center gap-4">
                <button
                  type="button"
                  class="flex items-center gap-1.5 text-[12px] text-slate-500 transition-colors hover:text-brand-700 disabled:cursor-default disabled:opacity-40 disabled:hover:text-slate-500"
                  :disabled="isNew || pullLoading"
                  :title="isNew ? '保存后才能拉取模型' : undefined"
                  @click="openPull"
                >
                  <Loader2 v-if="pullLoading" :size="12" class="animate-spin" />
                  <RefreshCw v-else :size="12" :stroke-width="2.4" />
                  拉取模型
                </button>
                <button
                  type="button"
                  class="flex items-center gap-1.5 text-[12px] text-slate-500 transition-colors hover:text-brand-700"
                  @click="addModel"
                >
                  <Plus :size="12" :stroke-width="2.4" />
                  添加模型
                </button>
              </div>
            </div>

            <div
              v-if="draft.models.length"
              class="divide-y divide-hairline overflow-hidden rounded-2xl bg-panel px-4 sm:px-6"
            >
              <div
                v-for="model in draft.models"
                :key="model.uid"
                :data-model-uid="model.uid"
                class="grid transition-[grid-template-rows] duration-200 ease-out"
                :class="removing.includes(model.uid) ? 'grid-rows-[0fr]' : 'grid-rows-[1fr]'"
              >
                <!--
                  折叠加 -mx/px 把裁切框左右各撑开 6px：输入框的 focus 光圈有 4px，
                  正好压在裁切边上会被切掉右侧那一半。
                -->
                <div class="-mx-1.5 overflow-hidden px-1.5">
                  <button
                    type="button"
                    class="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 py-4 text-left"
                    :aria-expanded="isExpanded(model.uid)"
                    @click="toggleModel(model.uid)"
                  >
                    <p
                      class="min-w-0 max-w-full truncate font-mono text-[13px] sm:text-[13.5px]"
                      :class="model.id ? 'text-slate-900' : 'text-slate-400'"
                    >
                      {{ model.id || '未命名模型' }}
                    </p>
                    <p
                      v-if="model.displayName"
                      class="min-w-0 max-w-full truncate text-[12px] text-slate-400"
                    >
                      {{ model.displayName }}
                    </p>

                    <div class="ml-auto flex shrink-0 flex-wrap items-center gap-1.5">
                      <span
                        v-if="model.contextLength > 0"
                        class="rounded bg-white px-2 py-1 text-[10.5px] text-slate-500"
                      >
                        {{ formatContext(Number(model.contextLength)) }} 上下文
                      </span>
                      <span
                        v-if="model.maxImage > 0"
                        class="rounded bg-white px-2 py-1 text-[10.5px] text-slate-500"
                      >
                        图片
                      </span>
                    </div>

                    <ChevronDown
                      :size="15"
                      class="shrink-0 text-slate-400 transition-transform duration-200"
                      :class="isExpanded(model.uid) ? 'rotate-180' : ''"
                    />
                  </button>

                  <div
                    class="grid transition-[grid-template-rows] duration-200 ease-out"
                    :class="isExpanded(model.uid) ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'"
                  >
                    <div class="-mx-1.5 overflow-hidden px-1.5">
                      <div
                        class="grid grid-cols-1 gap-4 border-t border-hairline pb-4 pt-5 transition-opacity duration-200 sm:grid-cols-2 sm:gap-5 xl:grid-cols-3"
                        :class="isExpanded(model.uid) ? 'opacity-100' : 'opacity-0'"
                        :inert="!isExpanded(model.uid)"
                      >
                        <label class="block">
                          <span class="mb-1.5 block text-[12px] text-slate-500">模型 ID</span>
                          <input
                            v-model="model.id"
                            type="text"
                            spellcheck="false"
                            placeholder="deepseek-chat"
                            class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 font-mono text-[12.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                          />
                        </label>

                        <label class="block">
                          <span class="mb-1.5 block text-[12px] text-slate-500">显示名称</span>
                          <input
                            v-model="model.displayName"
                            type="text"
                            placeholder="可选"
                            class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 text-[13.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                          />
                        </label>

                        <label class="block">
                          <span class="mb-1.5 block text-[12px] text-slate-500">上下文长度</span>
                          <span class="relative block">
                            <input
                              :value="model.contextLength > 0 ? model.contextLength : ''"
                              type="text"
                              inputmode="numeric"
                              autocomplete="off"
                              maxlength="9"
                              class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 pr-16 text-[13.5px] tabular-nums text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
                              @input="updateContext(model, $event)"
                            />
                            <span
                              class="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-[11px] text-slate-400"
                            >
                              tokens
                            </span>
                          </span>
                        </label>

                        <div class="block">
                          <span class="mb-1.5 block text-[12px] text-slate-500">图片输入</span>
                          <div class="flex h-10 items-center">
                            <button
                              type="button"
                              role="switch"
                              :aria-checked="model.maxImage > 0"
                              class="relative h-5 w-9 shrink-0 rounded-full transition-colors duration-200"
                              :class="model.maxImage > 0 ? 'bg-brand-300' : 'bg-slate-200'"
                              @click="toggleImage(model)"
                            >
                              <span
                                class="absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-transform duration-200"
                                :style="{
                                  transform:
                                    model.maxImage > 0 ? 'translateX(16px)' : 'translateX(0)',
                                }"
                              ></span>
                            </button>
                          </div>
                        </div>

                        <label class="block">
                          <span class="mb-1.5 block text-[12px] text-slate-500">最大尺寸</span>
                          <span class="relative block">
                            <input
                              type="text"
                              inputmode="numeric"
                              autocomplete="off"
                              maxlength="9"
                              :disabled="model.maxImage <= 0"
                              :value="model.maxImage > 0 ? model.maxImage : ''"
                              placeholder="600"
                              class="h-10 w-full rounded-lg border border-slate-200 bg-white px-3.5 pr-9 text-[13.5px] tabular-nums text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
                              @input="updateMaxImage(model, $event)"
                            />
                            <span
                              class="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-[11px] text-slate-400"
                            >
                              px
                            </span>
                          </span>
                        </label>

                        <div class="flex items-end justify-end sm:col-span-2 xl:col-span-1">
                          <button
                            type="button"
                            class="py-1 text-[12px] text-red-500 transition-colors hover:text-red-600"
                            @click="removeModel(model.uid)"
                          >
                            删除模型
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div
              v-else
              class="rounded-2xl bg-panel px-4 py-10 text-center text-[13px] text-slate-400 sm:px-6 sm:py-12"
            >
              还没有模型，可以从提供商拉取或手动添加
            </div>
          </div>

          <div v-if="!isNew" class="mt-8 flex justify-end sm:mt-10">
            <button
              type="button"
              class="text-[12px] text-red-500 transition-colors hover:text-red-600"
              @click="deleteProvider"
            >
              删除提供商
            </button>
          </div>
        </section>
      </Transition>
    </template>

    <!-- 空状态 -->
    <div
      v-else-if="!loadError"
      class="mt-8 flex flex-col items-center justify-center rounded-2xl bg-panel px-4 py-16 text-center sm:mt-10 sm:px-6 sm:py-24"
    >
      <p class="text-[13px] text-slate-500">还没有配置任何提供商</p>
      <button
        type="button"
        class="press mt-4 flex h-9 items-center gap-1.5 rounded-lg bg-brand-300 px-4 text-[12.5px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white"
        @click="startNew"
      >
        <Plus :size="14" :stroke-width="2.4" />
        添加提供商
      </button>
    </div>

    <!-- 拉取模型 -->
    <Teleport to="body">
      <Transition name="dialog">
        <div
          v-if="pullOpen"
          class="fixed inset-0 z-40 flex items-end justify-center bg-slate-900/20 sm:items-center sm:px-6"
          @click.self="pullOpen = false"
        >
          <div
            class="dialog-panel flex max-h-[88dvh] w-full flex-col overflow-hidden rounded-t-2xl bg-white shadow-2xl sm:max-h-[min(78vh,660px)] sm:w-140 sm:max-w-full sm:rounded-2xl"
          >
            <div
              class="flex shrink-0 items-start justify-between gap-4 px-4 pb-3 pt-4 sm:px-6 sm:pb-4 sm:pt-6"
            >
              <div class="min-w-0">
                <p class="truncate text-[15px] font-medium text-slate-900">
                  从 {{ draft.name.trim() || '提供商' }} 拉取模型
                </p>
                <p
                  v-if="!pullLoading && !pullError && remoteModels.length"
                  class="mt-1 text-[11px] text-slate-400"
                >
                  共 {{ remoteModels.length }} 个，已添加 {{ addedRemoteCount }} 个
                </p>
              </div>
              <button
                type="button"
                class="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-slate-400 transition-colors hover:bg-panel hover:text-slate-600"
                @click="pullOpen = false"
              >
                <X :size="15" :stroke-width="2.2" />
              </button>
            </div>

            <div
              v-if="!pullLoading && !pullError && remoteModels.length"
              class="flex shrink-0 items-center gap-2 border-t border-hairline px-4 py-3 sm:px-6"
            >
              <div class="relative min-w-0 flex-1">
                <Search
                  :size="13"
                  :stroke-width="2.2"
                  class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                />
                <input
                  ref="pullSearchEl"
                  v-model="pullQuery"
                  type="text"
                  spellcheck="false"
                  autocomplete="off"
                  placeholder="搜索模型 ID 或名称"
                  class="h-11 w-full rounded-lg border border-slate-200 bg-white pl-8 pr-8 text-[12.5px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25 sm:h-9"
                />
                <button
                  v-if="pullQuery"
                  type="button"
                  class="absolute right-1.5 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-md text-slate-400 transition-colors hover:bg-panel hover:text-slate-600"
                  aria-label="清除搜索"
                  @click="pullQuery = ''"
                >
                  <X :size="12" :stroke-width="2.4" />
                </button>
              </div>

              <button
                type="button"
                class="press h-11 shrink-0 rounded-lg px-3 text-[12px] text-slate-500 hover:bg-panel hover:text-slate-700 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500 sm:h-9 sm:px-2.5"
                :disabled="pullSelectable.length === 0"
                @click="toggleSelectAll"
              >
                {{ allSelectableSelected ? '取消全选' : '全选' }}
              </button>
            </div>

            <div class="relative min-h-0">
              <div
                v-if="pullLoading"
                class="border-t border-hairline px-4 py-10 text-center text-[12.5px] text-slate-400 sm:px-6 sm:py-14"
              >
                正在读取模型列表…
              </div>

              <div
                v-else-if="pullError"
                class="border-t border-hairline px-4 py-10 text-center text-[12.5px] leading-relaxed text-red-600 sm:px-6 sm:py-12"
              >
                {{ pullError }}
              </div>

              <div
                v-else-if="remoteModels.length === 0"
                class="border-t border-hairline px-4 py-10 text-center text-[12.5px] text-slate-400 sm:px-6 sm:py-14"
              >
                没有可导入的模型
              </div>

              <div
                v-else
                ref="pullListEl"
                class="scroll-thin overflow-y-auto border-t border-hairline"
                :style="pullListHeight ? { height: `${pullListHeight}px` } : undefined"
              >
                <div
                  v-if="filteredRemote.length === 0"
                  class="flex h-full items-center justify-center px-4 text-center text-[12.5px] leading-relaxed text-slate-400 sm:px-6"
                >
                  没有匹配「{{ pullQuery.trim() }}」的模型
                </div>

                <template v-else>
                  <button
                    v-for="remote in filteredRemote"
                    :key="remote.id"
                    type="button"
                    class="remote-row flex w-full items-center gap-3 border-b border-hairline px-4 py-3 text-left last:border-b-0 sm:px-6 sm:py-2.5"
                    :class="isAdded(remote.id) ? 'cursor-default' : 'hover:bg-panel/60'"
                    :disabled="isAdded(remote.id)"
                    @click="toggleRemote(remote.id)"
                  >
                    <span
                      v-if="isAdded(remote.id)"
                      class="grid h-4 w-4 shrink-0 place-items-center text-slate-300"
                    >
                      <Check :size="12" :stroke-width="2.6" />
                    </span>
                    <span
                      v-else
                      class="grid h-4 w-4 shrink-0 place-items-center rounded-[5px] border transition-colors duration-100"
                      :class="
                        pullSelected.includes(remote.id)
                          ? 'border-brand-400 bg-brand-300 text-white'
                          : 'border-slate-300 bg-white'
                      "
                    >
                      <Check v-if="pullSelected.includes(remote.id)" :size="10" :stroke-width="3" />
                    </span>

                    <span
                      class="min-w-0 flex-1 truncate font-mono text-[12px]"
                      :class="isAdded(remote.id) ? 'text-slate-400' : 'text-slate-700'"
                    >
                      {{ remote.id }}
                    </span>

                    <span
                      v-if="remote.displayName && remote.displayName !== remote.id"
                      class="hidden shrink-0 truncate text-[11px] text-slate-400 sm:block sm:max-w-35"
                    >
                      {{ remote.displayName }}
                    </span>

                    <span v-if="isAdded(remote.id)" class="shrink-0 text-[10.5px] text-slate-400">
                      已添加
                    </span>
                  </button>
                </template>
              </div>
            </div>

            <div
              class="pb-safe flex flex-wrap shrink-0 items-center justify-between gap-2 border-t border-hairline px-4 py-3 sm:px-6 sm:py-4"
            >
              <p class="text-[11px] text-slate-400">
                已选择 {{ pullSelected.length }} 个
                <span v-if="pullQuery.trim()" class="text-slate-300">
                  / 筛选出 {{ filteredRemote.length }} 个
                </span>
              </p>
              <div class="flex items-center gap-1.5">
                <button
                  type="button"
                  class="press rounded-lg px-3.5 py-2.5 text-[12px] text-slate-500 hover:bg-panel hover:text-slate-700 sm:py-2"
                  @click="pullOpen = false"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="press rounded-lg bg-brand-300 px-4 py-2.5 text-[12px] font-medium text-brand-800 hover:bg-brand-400 hover:text-white disabled:cursor-default disabled:opacity-40 disabled:hover:bg-brand-300 disabled:hover:text-brand-800 sm:py-2"
                  :disabled="pullSelected.length === 0"
                  @click="importSelected"
                >
                  导入
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

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
