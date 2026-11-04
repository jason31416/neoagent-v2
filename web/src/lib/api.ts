import { currentToken, signOut } from './auth'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export interface SystemInfo {
  osInfo: string
  systemTime: string
  /** 无法采集时为负数。 */
  cpuPercentage: number
  /** 无法采集时为负数。 */
  memory: number
}

export interface Statistics {
  /** 已运行毫秒数。 */
  running: number
  /** 无法统计时为负数。 */
  agents: number
  /** 无法统计时为负数。 */
  channels: number
  /** 天序号（epoch/86400）到数值；旧版是数组，两种都认。 */
  dailyTokenUsage: Record<string, number> | number[]
  dailyMessagesHandled: Record<string, number> | number[]
  systemInfo: SystemInfo
}

export interface RuntimeStatus {
  version: string
  statistics: Statistics
}

export interface AuthResponse {
  success: boolean
  token: string
}

interface RequestOptions {
  method?: string
  body?: unknown
}

/**
 * 任意一次请求被判成 401（多半是后端重启、token 换了），
 * 就把本地登录状态清掉并通知外层回登录页，而不是只弹一条错误。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

function describeStatus(status: number): string {
  switch (status) {
    case 400:
      return '请求参数不正确'
    case 401:
      return '登录状态已失效，请重新登录'
    case 403:
      return '没有权限执行这个操作'
    case 404:
      return '接口不存在'
    default:
      return `服务返回了异常状态 ${status}`
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })

  const token = currentToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let body: string | undefined
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body,
    })
  } catch {
    throw new ApiError(0, '无法连接到 NeoAgent 服务')
  }

  if (response.status === 401) {
    signOut()
    unauthorizedHandler?.()
    throw new ApiError(401, describeStatus(401))
  }

  if (!response.ok) {
    throw new ApiError(response.status, describeStatus(response.status))
  }

  const text = await response.text()
  if (!text) return undefined as T

  try {
    return JSON.parse(text) as T
  } catch {
    throw new ApiError(response.status, '服务返回了无法解析的数据')
  }
}

export const api = {
  isRegistered: () => request<{ status: boolean }>('/api/auth/is_registered'),

  login: (password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: { password } }),

  register: (secret: string, password: string) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: { secret, password } }),

  status: () => request<RuntimeStatus>('/api/status'),
}
