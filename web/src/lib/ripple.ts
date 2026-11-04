import type { Directive } from 'vue'

const handlers = new WeakMap<HTMLElement, (event: PointerEvent) => void>()

/** 在指针按下的位置生成一圈向外扩散的水波，动画结束自动移除。 */
function spawnRipple(el: HTMLElement, event: PointerEvent) {
  const rect = el.getBoundingClientRect()
  const size = Math.max(rect.width, rect.height) * 2

  const ink = document.createElement('span')
  ink.className = 'ripple-ink'
  ink.style.width = `${size}px`
  ink.style.height = `${size}px`
  ink.style.left = `${event.clientX - rect.left - size / 2}px`
  ink.style.top = `${event.clientY - rect.top - size / 2}px`

  el.appendChild(ink)
  ink.addEventListener('animationend', () => ink.remove(), { once: true })
}

/**
 * 用法：<button v-ripple class="...">
 *
 * 定位和裁切写成行内样式，不用 class：
 * Vue 更新 class 时是直接赋值 className，会把手动加的类冲掉，
 * 那样 RouterLink 一切换选中态，水波就会跑到别处去。
 */
export const ripple: Directive<HTMLElement> = {
  mounted(el) {
    if (getComputedStyle(el).position === 'static') {
      el.style.position = 'relative'
    }
    el.style.overflow = 'hidden'

    const handler = (event: PointerEvent) => spawnRipple(el, event)
    handlers.set(el, handler)
    el.addEventListener('pointerdown', handler)
  },

  unmounted(el) {
    const handler = handlers.get(el)
    if (handler) {
      el.removeEventListener('pointerdown', handler)
      handlers.delete(el)
    }
  },
}
