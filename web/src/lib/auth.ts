import { ref } from 'vue'

const STORAGE_KEY = 'neoagent.token'

function readStoredToken(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

const token = ref<string | null>(readStoredToken())

export function currentToken(): string | null {
  return token.value
}

export function isAuthenticated(): boolean {
  return token.value !== null && token.value !== ''
}

export function setToken(next: string | null) {
  token.value = next
  try {
    if (next) window.localStorage.setItem(STORAGE_KEY, next)
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 隐私模式下 localStorage 可能不可写，此时仅本次会话有效。
  }
}

export function signOut() {
  setToken(null)
}
