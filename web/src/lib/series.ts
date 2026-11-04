const DAY_MS = 86_400_000

export interface SeriesPoint {
  key: string
  /** 图上显示的日期，比如 11/5。 */
  label: string
  value: number
}

/** 后端的日序号是 epoch/86400，按 UTC 自然日算，这里保持一致。 */
function dayLabel(dayIndex: number): string {
  const date = new Date(dayIndex * DAY_MS)
  return `${date.getUTCMonth() + 1}/${date.getUTCDate()}`
}

function toNumber(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : 0
}

/**
 * 把后端给的日统计规整成固定长度的一串点。
 *
 * 后端给的是 { 天序号: 数值 } 这种稀疏结构，缺的日子直接按 0 补上，
 * 这样图表的横轴长度永远是固定的，不会因为某天没数据就变形。
 * 也兼容旧版的数组形式（按末尾对齐）。
 */
export function buildDailySeries(
  raw: Record<string, number> | number[] | null | undefined,
  window = 7,
): SeriesPoint[] {
  const today = Math.floor(Date.now() / DAY_MS)

  if (Array.isArray(raw)) {
    const tail = raw.slice(-window)
    const padding = Math.max(0, window - tail.length)
    return Array.from({ length: window }, (_, index) => {
      const day = today - (window - 1 - index)
      const value = index < padding ? 0 : tail[index - padding]
      return { key: String(day), label: dayLabel(day), value: toNumber(value) }
    })
  }

  const map = raw ?? {}

  return Array.from({ length: window }, (_, index) => {
    const day = today - (window - 1 - index)
    return {
      key: String(day),
      label: dayLabel(day),
      value: toNumber(map[String(day)]),
    }
  })
}
