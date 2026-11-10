import { Activity, Bot, Boxes, MessagesSquare, Radio, Settings } from 'lucide-vue-next'

export const primaryNav = [
  { label: '概览', to: '/', icon: Activity },
  { label: 'Agents', to: '/agents', icon: Bot },
  { label: '频道', to: '/channels', icon: Radio },
  { label: '模型', to: '/models', icon: Boxes },
  { label: '消息记录', to: '/messages', icon: MessagesSquare },
]

export const secondaryNav = [{ label: '系统设置', to: '/settings', icon: Settings }]

const allNav = [...primaryNav, ...secondaryNav]

/** `/` 必须精确匹配，否则 startsWith('/') 会命中所有页面。 */
export function isNavActive(path: string, to: string): boolean {
  return to === '/' ? path === '/' : path.startsWith(to)
}

/** 侧栏高亮和移动端顶栏共用这一份结果，两边读的是同一个 route.path。 */
export function activeNavLabel(path: string): string {
  return allNav.find((item) => isNavActive(path, item.to))?.label ?? 'NeoAgent WebUI'
}
