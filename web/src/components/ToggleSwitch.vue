<script setup lang="ts">
defineProps<{
  modelValue: boolean
  disabled?: boolean
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <button
    type="button"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="label"
    :disabled="disabled"
    class="relative h-5 w-9 shrink-0 rounded-full transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-40"
    :class="modelValue ? 'bg-brand-300' : 'bg-slate-200'"
    @click="emit('update:modelValue', !modelValue)"
  >
    <span
      class="absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-transform duration-200"
      :style="{ transform: modelValue ? 'translateX(16px)' : 'translateX(0)' }"
    ></span>
  </button>
</template>
