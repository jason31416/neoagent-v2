<script setup lang="ts">
defineProps<{
  open: boolean
  title: string
  message?: string
  confirmText?: string
  danger?: boolean
}>()

defineEmits<{
  confirm: []
  cancel: []
}>()
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/20 px-6"
        @click.self="$emit('cancel')"
      >
        <div
          class="dialog-panel w-full max-w-[380px] rounded-2xl bg-white p-6 shadow-[0_24px_80px_-24px_rgba(15,23,42,0.35)]"
        >
          <h2 class="text-[16px] font-semibold text-slate-900">{{ title }}</h2>
          <p v-if="message" class="mt-2 text-[13px] leading-relaxed text-slate-500">
            {{ message }}
          </p>

          <div class="mt-6 flex items-center justify-end gap-1.5">
            <button
              type="button"
              class="press rounded-lg px-3.5 py-2 text-[12.5px] text-slate-500 hover:bg-panel hover:text-slate-700"
              @click="$emit('cancel')"
            >
              取消
            </button>
            <button
              type="button"
              class="press rounded-lg px-4 py-2 text-[12.5px] font-medium"
              :class="
                danger
                  ? 'bg-red-500 text-white hover:bg-red-600'
                  : 'bg-brand-300 text-brand-800 hover:bg-brand-400 hover:text-white'
              "
              @click="$emit('confirm')"
            >
              {{ confirmText ?? '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
