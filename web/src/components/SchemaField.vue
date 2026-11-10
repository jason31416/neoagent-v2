<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Copy, Plus, X } from 'lucide-vue-next'
import SelectField from '@/components/SelectField.vue'
import ToggleSwitch from '@/components/ToggleSwitch.vue'
import type { SchemaField, SchemaValue } from '@/lib/api'

const props = defineProps<{
  field: SchemaField
  modelValue: SchemaValue
}>()

const emit = defineEmits<{ 'update:modelValue': [value: SchemaValue] }>()

const replacing = ref(false)
const secretDraft = ref('')
let secretOriginal = ''

const tagList = computed(() =>
  Array.isArray(props.modelValue) ? (props.modelValue as string[]) : [],
)
const draftTag = ref('')

const numberText = ref(String(props.modelValue ?? ''))
watch(
  () => props.modelValue,
  (value) => {
    const next = String(value ?? '')
    if (next !== numberText.value) numberText.value = next
  },
)

const textValue = computed(() => (typeof props.modelValue === 'string' ? props.modelValue : ''))

function update(value: SchemaValue) {
  emit('update:modelValue', value)
}

function onNumberInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value.replace(/\D/g, '')
  numberText.value = raw
  update(raw ? Number(raw) : 0)
}

function addTag() {
  const next = draftTag.value.trim()
  if (!next) return
  if (!tagList.value.includes(next)) update([...tagList.value, next])
  draftTag.value = ''
}

function removeTag(tag: string) {
  update(tagList.value.filter((item) => item !== tag))
}

function startReplace() {
  secretOriginal = typeof props.modelValue === 'string' ? props.modelValue : ''
  secretDraft.value = ''
  replacing.value = true
}

function onSecretInput(event: Event) {
  secretDraft.value = (event.target as HTMLInputElement).value
  update(secretDraft.value)
}

function cancelReplace() {
  replacing.value = false
  secretDraft.value = ''
  update(secretOriginal)
}

async function copyValue() {
  const text = String(props.modelValue ?? '')
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 剪贴板不可用时静默失败，用户仍然可以手动选中复制。
  }
}
</script>

<template>
  <div class="grid grid-cols-[190px_minmax(0,1fr)] items-start gap-x-8 gap-y-2 py-3.5">
    <div>
      <label class="flex h-9 items-center gap-1 text-[13px] text-slate-700">
        {{ field.label }}<span v-if="field.required" class="text-red-400">*</span>
      </label>
      <p v-if="field.help" class="mt-1 text-[11.5px] leading-relaxed text-slate-400">
        {{ field.help }}
      </p>
    </div>

    <div class="flex min-w-0 justify-end">
      <textarea
        v-if="field.type === 'textarea'"
        :value="textValue"
        rows="3"
        :disabled="field.disabled"
        :placeholder="field.placeholder ?? undefined"
        class="w-full max-w-[480px] resize-none rounded-lg border border-slate-200 bg-white px-3.5 py-2.5 text-[13px] leading-relaxed text-slate-800 outline-none transition duration-150 placeholder:text-slate-300 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25 disabled:cursor-not-allowed disabled:bg-panel disabled:text-slate-400"
        @input="update(($event.target as HTMLTextAreaElement).value)"
      ></textarea>

      <div
        v-else-if="field.type === 'secret'"
        class="flex w-full max-w-95 items-center gap-2"
      >
        <template v-if="replacing">
          <input
            :value="secretDraft"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder="输入新的密钥"
            class="h-9 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-3 font-mono text-[12px] text-slate-800 outline-none transition duration-150 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25"
            @input="onSecretInput"
          />
          <button
            type="button"
            class="shrink-0 text-[12px] text-slate-400 transition-colors hover:text-slate-600"
            @click="cancelReplace"
          >
            取消
          </button>
        </template>
        <template v-else>
          <div
            class="flex h-9 min-w-0 flex-1 items-center rounded-lg border border-slate-200 bg-white px-3"
          >
            <span class="truncate font-mono text-[12px] text-slate-500">{{ modelValue }}</span>
          </div>
          <button
            v-if="!field.disabled"
            type="button"
            class="press h-9 shrink-0 rounded-lg border border-slate-200 bg-white px-2.5 text-[12px] text-slate-600 hover:bg-panel"
            @click="startReplace"
          >
            替换
          </button>
        </template>
      </div>

      <div
        v-else-if="field.type === 'readonly'"
        class="flex w-full max-w-[420px] items-center gap-2"
      >
        <div
          class="flex h-9 min-w-0 flex-1 items-center rounded-lg border border-dashed border-slate-200 bg-panel/60 px-3"
        >
          <span class="truncate font-mono text-[12px] text-slate-500">{{ modelValue }}</span>
        </div>
        <button
          type="button"
          class="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-slate-200 bg-white text-slate-400 transition-colors hover:bg-panel hover:text-slate-600"
          title="复制"
          @click="copyValue"
        >
          <Copy :size="12" />
        </button>
      </div>

      <div v-else-if="field.type === 'switch'" class="flex h-9 items-center">
        <ToggleSwitch
          :model-value="modelValue === true"
          :disabled="field.disabled"
          :label="field.label"
          @update:model-value="update($event)"
        />
      </div>

      <div v-else-if="field.type === 'select'" class="w-full max-w-[220px]">
        <SelectField
          :model-value="String(modelValue ?? '')"
          :options="field.options ?? []"
          :label="field.label"
          :disabled="field.disabled"
          @update:model-value="update($event)"
        />
      </div>

      <div v-else-if="field.type === 'tags'" class="max-w-[420px]">
        <div class="flex flex-wrap items-center gap-1.5">
          <span
            v-for="tag in tagList"
            :key="tag"
            class="inline-flex items-center gap-1 rounded-md bg-panel px-2 py-1 font-mono text-[11.5px] text-slate-600"
          >
            {{ tag }}
            <button
              type="button"
              class="text-slate-300 transition-colors hover:text-slate-500"
              @click="removeTag(tag)"
            >
              <X :size="11" />
            </button>
          </span>
          <span v-if="!field.disabled" class="flex items-center">
            <input
              v-model="draftTag"
              :placeholder="tagList.length ? '添加' : (field.placeholder ?? undefined)"
              class="h-7 w-24 rounded-md border border-dashed border-slate-200 bg-transparent px-1.5 text-[11.5px] text-slate-700 outline-none transition-colors placeholder:text-slate-300 focus:border-brand-400"
              @keydown.enter.prevent="addTag"
            />
            <button
              type="button"
              class="grid h-7 w-7 place-items-center rounded-md text-slate-300 transition-colors hover:bg-panel hover:text-slate-500"
              @click="addTag"
            >
              <Plus :size="12" />
            </button>
          </span>
        </div>
      </div>

      <div v-else-if="field.type === 'number'" class="w-full max-w-[150px]">
        <span class="relative block">
          <input
            :value="numberText"
            type="text"
            inputmode="numeric"
            :disabled="field.disabled"
            :placeholder="field.placeholder ?? undefined"
            class="h-9 w-full rounded-lg border border-slate-200 bg-white px-3 text-[13px] tabular-nums text-slate-800 outline-none transition duration-150 placeholder:text-slate-300 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25 disabled:cursor-not-allowed disabled:bg-panel disabled:text-slate-400"
            @input="onNumberInput"
          />
          <span
            v-if="field.unit"
            class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-slate-400"
          >
            {{ field.unit }}
          </span>
        </span>
      </div>

      <input
        v-else
        :value="textValue"
        type="text"
        :disabled="field.disabled"
        :placeholder="field.placeholder ?? undefined"
        class="h-9 w-full max-w-[380px] rounded-lg border border-slate-200 bg-white px-3 text-[13px] text-slate-800 outline-none transition duration-150 placeholder:text-slate-300 focus:border-brand-400 focus:ring-4 focus:ring-brand-300/25 disabled:cursor-not-allowed disabled:bg-panel disabled:text-slate-400"
        @input="update(($event.target as HTMLInputElement).value)"
      />
    </div>
  </div>
</template>
