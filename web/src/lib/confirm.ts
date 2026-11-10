import { ref } from 'vue'

export interface ConfirmOptions {
  title: string
  message?: string
  confirmText?: string
  danger?: boolean
}

/** 简单的确认框控制器：返回一个 Promise，避免每个页面各写一套。 */
export function useConfirm() {
  const confirmState = ref<ConfirmOptions | null>(null)
  let resolver: ((value: boolean) => void) | null = null

  function askConfirm(options: ConfirmOptions): Promise<boolean> {
    confirmState.value = options
    return new Promise((resolve) => {
      resolver = resolve
    })
  }

  function answerConfirm(value: boolean) {
    confirmState.value = null
    resolver?.(value)
    resolver = null
  }

  return { confirmState, askConfirm, answerConfirm }
}
