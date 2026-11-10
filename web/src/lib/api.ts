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

export type ProviderStatus = 'connected' | 'untested' | 'error'

export interface ProviderModel {
  /** 模型 id，同时也是后端 model-id -> Model 表的 key，全局唯一。 */
  id: string
  displayName: string
  /** 上下文窗口，单位 token。 */
  contextLength: number
  /** 图片输入的最大边长；-1 表示不支持图片。 */
  maxImage: number
}

export interface ModelProvider {
  id: string
  name: string
  /** 适配器类型：openai-compatible / deepseek / local。 */
  type: string
  baseUrl: string
  status: ProviderStatus
  apiKeySet: boolean
  /** 服务端给的掩码，例如 sk-••••••••3f9a；没有密钥时为 null。 */
  apiKeyPreview: string | null
  models: ProviderModel[]
}

export interface ProviderInput {
  name: string
  type: string
  baseUrl: string
  /** 省略表示不修改，空字符串表示清除。 */
  apiKey?: string
  models: ProviderModel[]
}

export interface RemoteModel {
  id: string
  displayName: string
  contextLength: number
  maxImage: number
}

/** 配置项的控件类型；后端只下发类型，前端按类型渲染，不认识具体字段。 */
export type SchemaFieldType =
  'text' | 'secret' | 'number' | 'switch' | 'select' | 'tags' | 'textarea' | 'readonly'

export type SchemaValue = string | number | boolean | string[] | null

export interface SchemaOption {
  value: string
  label: string
}

export interface SchemaField {
  key: string
  label: string
  type: SchemaFieldType
  value: SchemaValue
  help?: string | null
  required?: boolean
  unit?: string | null
  placeholder?: string | null
  options?: SchemaOption[] | null
  /** 只读或由外部控制的项，仍然展示但不能改。 */
  disabled?: boolean
  /** 值是否已经过掩码处理（密钥只回显末四位）。 */
  masked?: boolean
}

export interface SchemaGroup {
  id: string
  title: string
  fields: SchemaField[]
}

export type ChannelStatus = 'running' | 'stopped' | 'error' | 'idle'

export interface ChannelConfig {
  id: string
  name: string
  enabled: boolean
  status: ChannelStatus
  groups: SchemaGroup[]
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
    let message = describeStatus(response.status)
    try {
      const parsed = JSON.parse(await response.text()) as { error?: unknown }
      if (typeof parsed.error === 'string' && parsed.error) message = parsed.error
    } catch {
      // 后端没给 JSON 就用状态码的通用说明。
    }
    throw new ApiError(response.status, message)
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

  models: () => request<{ providers: ModelProvider[] }>('/api/models'),

  createProvider: (body: ProviderInput) =>
    request<{ provider: ModelProvider }>('/api/models/providers', { method: 'POST', body }),

  updateProvider: (id: string, body: ProviderInput) =>
    request<{ provider: ModelProvider }>(`/api/models/providers/${encodeURIComponent(id)}`, {
      method: 'POST',
      body,
    }),

  deleteProvider: (id: string) =>
    request<{ success: boolean }>(`/api/models/providers/${encodeURIComponent(id)}/delete`, {
      method: 'POST',
    }),

  testProvider: (id: string) =>
    request<{ provider: ModelProvider }>(`/api/models/providers/${encodeURIComponent(id)}/test`, {
      method: 'POST',
    }),

  remoteModels: (id: string) =>
    request<{ models: RemoteModel[] }>(`/api/models/providers/${encodeURIComponent(id)}/remote`),

  channels: () => request<{ channels: ChannelConfig[] }>('/api/channels'),

  /** 整页提交：只传改动过的频道也行，服务端按 key 合并。 */
  saveChannels: (values: Record<string, Record<string, unknown>>) =>
    request<{ channels: ChannelConfig[] }>('/api/channels', { method: 'POST', body: { values } }),

  settings: () => request<{ groups: SchemaGroup[] }>('/api/settings'),

  saveSettings: (values: Record<string, unknown>) =>
    request<{ groups: SchemaGroup[] }>('/api/settings', { method: 'POST', body: { values } }),
}
