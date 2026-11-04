import './assets/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './lib/api'
import { signOut } from './lib/auth'
import { ripple } from './lib/ripple'

/** 后端说 token 不认了：清掉本地状态，带上当前地址回登录页。 */
setUnauthorizedHandler(() => {
  signOut()

  const current = router.currentRoute.value
  if (current.name === 'login') return

  const redirect = current.fullPath
  const query: Record<string, string> = { expired: '1' }
  if (redirect && redirect !== '/') query.redirect = redirect

  void router.replace({ name: 'login', query })
})

const app = createApp(App)

app.directive('ripple', ripple)
app.use(router)

app.mount('#app')
