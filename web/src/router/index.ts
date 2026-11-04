import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated } from '@/lib/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { layout: 'blank', public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { layout: 'blank', public: true },
    },
    {
      path: '/',
      name: 'overview',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/agents',
      name: 'agents',
      component: () => import('@/views/AgentsView.vue'),
    },
    {
      path: '/channels',
      name: 'channels',
      component: () => import('@/views/ChannelsView.vue'),
    },
    {
      path: '/models',
      name: 'models',
      component: () => import('@/views/ModelsView.vue'),
    },
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/MessagesView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 所有页面都要求先登录；登录页和注册页是例外。
 * 注册页只能通过带 token 的绑定链接进入，是否合法由页面自己判断。
 */
router.beforeEach((to) => {
  if (to.meta.public) {
    // 已经登录的人没必要再看到登录页。
    if (to.name === 'login' && isAuthenticated()) return { name: 'overview' }
    return true
  }

  if (!isAuthenticated()) {
    return {
      name: 'login',
      query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
    }
  }

  return true
})

export default router
