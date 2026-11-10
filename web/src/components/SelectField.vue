<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Check, ChevronDown } from 'lucide-vue-next'

export interface SelectOption {
  value: string
  label: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: SelectOption[]
    /** 无障碍名称；视觉上的标题由调用方自己排版。 */
    label?: string
    disabled?: boolean
    placeholder?: string
  }>(),
  { label: undefined, disabled: false, placeholder: '请选择' },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const uid = `select-${useId()}`

const open = ref(false)
/** 键盘高亮项；鼠标移入也会同步，方便方向键接着走。 */
const activeIndex = ref(-1)
/** 下方空间不够时向上弹，避免被滚动容器的底边切掉。 */
const dropUp = ref(false)
const rootEl = ref<HTMLElement | null>(null)
const triggerEl = ref<HTMLButtonElement | null>(null)
const listEl = ref<HTMLElement | null>(null)

const selectedLabel = computed(
  () => props.options.find((option) => option.value === props.modelValue)?.label,
)

function optionId(index: number): string {
  return `${uid}-option-${index}`
}

function scrollActiveIntoView() {
  const node = listEl.value?.querySelector<HTMLElement>(`[data-active="true"]`)
  node?.scrollIntoView({ block: 'nearest' })
}

function openMenu() {
  if (props.disabled || open.value) return

  const trigger = triggerEl.value?.getBoundingClientRect()
  if (trigger) {
    const estimated = Math.min(props.options.length * 36 + 8, 264)
    const below = window.innerHeight - trigger.bottom
    dropUp.value = below < estimated && trigger.top > below
  }

  open.value = true
  const index = props.options.findIndex((option) => option.value === props.modelValue)
  activeIndex.value = index >= 0 ? index : 0
  void nextTick(scrollActiveIntoView)
}

function closeMenu(restoreFocus = true) {
  if (!open.value) return
  open.value = false
  activeIndex.value = -1
  if (restoreFocus) triggerEl.value?.focus()
}

function toggle() {
  if (open.value) closeMenu(false)
  else openMenu()
}

function moveActive(delta: number) {
  const total = props.options.length
  if (!total) return
  const current = activeIndex.value < 0 ? (delta > 0 ? -1 : 0) : activeIndex.value
  activeIndex.value = (current + delta + total) % total
  void nextTick(scrollActiveIntoView)
}

function choose(index: number) {
  const option = props.options[index]
  if (!option) return
  if (option.value !== props.modelValue) emit('update:modelValue', option.value)
  closeMenu()
}

function onKeydown(event: KeyboardEvent) {
  if (props.disabled) return

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      if (open.value) moveActive(1)
      else openMenu()
      break
    case 'ArrowUp':
      event.preventDefault()
      if (open.value) moveActive(-1)
      else openMenu()
      break
    case 'Home':
      if (!open.value) break
      event.preventDefault()
      activeIndex.value = 0
      void nextTick(scrollActiveIntoView)
      break
    case 'End':
      if (!open.value) break
      event.preventDefault()
      activeIndex.value = props.options.length - 1
      void nextTick(scrollActiveIntoView)
      break
    case 'Enter':
    case ' ':
      event.preventDefault()
      if (!open.value) openMenu()
      else if (activeIndex.value >= 0) choose(activeIndex.value)
      break
    case 'Escape':
      if (!open.value) break
      // 不要让 Esc 继续冒泡去关掉外面的对话框。
      event.preventDefault()
      event.stopPropagation()
      closeMenu()
      break
    case 'Tab':
      closeMenu(false)
      break
    default:
      break
  }
}

function onPointerDown(event: PointerEvent) {
  if (!rootEl.value?.contains(event.target as Node)) closeMenu(false)
}

watch(open, (value) => {
  if (value) document.addEventListener('pointerdown', onPointerDown, true)
  else document.removeEventListener('pointerdown', onPointerDown, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown, true)
})
</script>

<template>
  <div ref="rootEl" class="relative">
    <button
      ref="triggerEl"
      type="button"
      role="combobox"
      :aria-label="label"
      :aria-expanded="open"
      :aria-controls="`${uid}-list`"
      :aria-activedescendant="open && activeIndex >= 0 ? optionId(activeIndex) : undefined"
      :disabled="disabled"
      class="flex h-10 w-full items-center justify-between gap-2 rounded-lg border bg-white px-3.5 text-left text-[13.5px] outline-none transition duration-150 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
      :class="
        open
          ? 'border-brand-400 ring-4 ring-brand-300/25'
          : 'border-slate-200 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25'
      "
      @click="toggle"
      @keydown="onKeydown"
    >
      <span class="truncate" :class="selectedLabel ? 'text-slate-800' : 'text-slate-400'">
        {{ selectedLabel ?? placeholder }}
      </span>
      <ChevronDown
        :size="14"
        :stroke-width="2.2"
        class="shrink-0 text-slate-400 transition-transform duration-200"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <Transition name="menu">
      <div
        v-if="open"
        :id="`${uid}-list`"
        ref="listEl"
        role="listbox"
        :aria-label="label"
        class="scroll-thin absolute inset-x-0 z-30 max-h-64 overflow-y-auto rounded-xl border border-hairline bg-white p-1 shadow-[0_16px_40px_-16px_rgba(15,23,42,0.28)]"
        :class="dropUp ? 'bottom-full mb-1.5 origin-bottom' : 'top-full mt-1.5 origin-top'"
      >
        <button
          v-for="(option, index) in options"
          :id="optionId(index)"
          :key="option.value"
          type="button"
          role="option"
          :aria-selected="option.value === modelValue"
          :data-active="index === activeIndex"
          class="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-[13px] transition-colors duration-100"
          :class="[
            index === activeIndex ? 'bg-panel' : '',
            option.value === modelValue ? 'text-brand-800' : 'text-slate-600',
          ]"
          @click="choose(index)"
          @pointerenter="activeIndex = index"
        >
          <span class="min-w-0 flex-1 truncate">{{ option.label }}</span>
          <Check
            v-if="option.value === modelValue"
            :size="13"
            :stroke-width="2.6"
            class="shrink-0 text-brand-500"
          />
        </button>
      </div>
    </Transition>
  </div>
</template>
