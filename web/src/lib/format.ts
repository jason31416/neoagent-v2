/** 后端用负数表示“这一项采集不到”。 */
export function isAvailable(value: number): boolean {
  return Number.isFinite(value) && value >= 0
}

export function formatCount(value: number): string {
  if (!isAvailable(value)) return '--'
  return new Intl.NumberFormat('zh-CN').format(Math.round(value))
}

export function formatPercent(value: number): string {
  if (!isAvailable(value)) return '--'
  return `${Math.round(value * 10) / 10}%`
}

/** 内存字段目前只给一个数，按 MB 处理；读不出单位时退回原值。 */
export function formatMemory(value: number): string {
  if (!isAvailable(value)) return '--'
  if (value >= 1024) return `${Math.round((value / 1024) * 10) / 10} GB`
  return `${Math.round(value)} MB`
}

export function formatCompact(value: number): string {
  if (!Number.isFinite(value)) return '--'
  if (value >= 1_000_000) return `${Math.round((value / 1_000_000) * 10) / 10}M`
  if (value >= 1_000) return `${Math.round((value / 1_000) * 10) / 10}k`
  return `${Math.round(value)}`
}

export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60

  if (days > 0) return `${days} 天 ${hours} 小时 ${minutes} 分`
  if (hours > 0) return `${hours} 小时 ${minutes} 分 ${seconds} 秒`
  if (minutes > 0) return `${minutes} 分 ${seconds} 秒`
  return `${seconds} 秒`
}
