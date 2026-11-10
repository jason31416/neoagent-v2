import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发时把 /api 透传给 Ktor，前端就不用处理跨域了。
      // 这台机器上 Steam 占了 127.0.0.1:8080，Ktor 只拿到了 IPv6 的 8080。
      // 走 127.0.0.1 会打到 Steam 上，接口就变成了 404。
      '/api': {
        target: 'http://[::1]:8080',
        changeOrigin: true,
      },
    },
  },
})
