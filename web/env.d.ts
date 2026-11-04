/// <reference types="vite/client" />

import type { ripple } from '@/lib/ripple'

declare module 'vue' {
  interface GlobalDirectives {
    vRipple: typeof ripple
  }
}

export {}
