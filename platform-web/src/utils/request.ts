import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

import type { ApiEnvelope } from '@/api/types'

export type { AxiosRequestConfig } from 'axios'

const TOKEN_STORAGE_KEY = 'wx_platform_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_STORAGE_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY)
}

/** 接口异常：携带后端 code / 微信 errcode / errmsg，供页面做局部展示 */
export class ApiError extends Error {
  readonly code: number
  readonly errcode?: number
  readonly errmsg?: string
  readonly httpStatus?: number
  readonly payload?: unknown

  constructor(
    message: string,
    options: {
      code?: number
      errcode?: number
      errmsg?: string
      httpStatus?: number
      payload?: unknown
    } = {}
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = options.code ?? -1
    this.errcode = options.errcode
    this.errmsg = options.errmsg
    this.httpStatus = options.httpStatus
    this.payload = options.payload
  }
}

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 为 true 时不弹出全局错误提示（轮询接口、可自持错误的表单等） */
    silent?: boolean
    /** 跳过 401 自动跳转登录（登录接口自身使用） */
    skipAuthRedirect?: boolean
  }
}

export const API_BASE = import.meta.env.VITE_API_BASE || '/api'

type ErrorPayload = ApiEnvelope<unknown> | string | ArrayBuffer | Blob | null | undefined

const service = axios.create({
  baseURL: API_BASE,
  timeout: 30000
})

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

service.interceptors.response.use(
  (response: AxiosResponse<unknown>) => {
    if (response.config.responseType === 'blob') {
      // 图片流：交给调用方异步解包，出错时抛 ApiError
      return unwrapBlob(response) as unknown as AxiosResponse
    }
    return unwrapEnvelope(response) as unknown as AxiosResponse
  },
  (error: AxiosError<ErrorPayload>) => Promise.reject(toApiError(error))
)

function unwrapEnvelope(response: AxiosResponse<unknown>): unknown {
  const body = response.data as ApiEnvelope<unknown> | null
  const silent = response.config.silent === true
  if (body === null || typeof body !== 'object' || body.code === undefined) {
    // 非标准包装（例如后端直接返回数组/字符串），按原样返回
    return response.data
  }
  if (body.code === 0) {
    return body.data
  }
  throw buildBusinessError(body, response.status, silent)
}

async function unwrapBlob(response: AxiosResponse<unknown>): Promise<unknown> {
  const blob = response.data as Blob
  if (blob.type.includes('application/json')) {
    const text = await blob.text()
    let parsed: ApiEnvelope<unknown> | null = null
    try {
      parsed = JSON.parse(text) as ApiEnvelope<unknown>
    } catch {
      parsed = null
    }
    if (parsed && parsed.code !== 0) {
      throw buildBusinessError(parsed, response.status, response.config.silent === true)
    }
    throw new ApiError('接口返回了非预期的 JSON 数据', { httpStatus: response.status, payload: text })
  }
  return blob
}

function buildBusinessError(body: ApiEnvelope<unknown>, httpStatus: number, silent: boolean): ApiError {
  const data = body.data
  const wechat = asWechatError(data)
  const message = wechat.errmsg || body.message || wechat.errcodeText || '请求失败'
  if (!silent) {
    ElMessage.error(message)
  }
  return new ApiError(message, {
    code: body.code,
    errcode: wechat.errcode,
    errmsg: wechat.errmsg || body.message,
    httpStatus,
    payload: data
  })
}

function asWechatError(data: unknown): { errcode?: number; errmsg?: string; errcodeText: string } {
  if (data && typeof data === 'object') {
    const raw = data as Record<string, unknown>
    const errcode = typeof raw.errcode === 'number' ? raw.errcode : undefined
    const errmsg = typeof raw.errmsg === 'string' ? raw.errmsg : undefined
    if (errcode !== undefined) {
      return { errcode, errmsg, errcodeText: `微信接口错误 ${errcode}${errmsg ? `：${errmsg}` : ''}` }
    }
  }
  return { errcodeText: '' }
}

function toApiError(error: AxiosError<ErrorPayload>): ApiError {
  const status = error.response?.status
  const silent = error.config?.silent === true
  let message = error.message || '网络异常'

  if (status === 401) {
    message = '登录状态已失效，请重新登录'
    clearToken()
    if (!error.config?.skipAuthRedirect && !window.location.pathname.startsWith('/login')) {
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.href = `/login?redirect=${redirect}`
    }
  } else if (!error.response) {
    message = '无法连接到服务，请确认后端服务已启动'
  } else if (typeof error.response.data === 'string') {
    message = error.response.data.slice(0, 200)
  } else if (error.response.data && typeof error.response.data === 'object') {
    const body = error.response.data as ApiEnvelope<unknown>
    const wechat = asWechatError(body.data)
    message = wechat.errmsg || body.message || message
  }

  if (!silent) {
    ElMessage.error(message)
  }
  return new ApiError(message, { code: status ?? -1, httpStatus: status, payload: error.response?.data })
}

/** 统一发起请求，返回已解包的 data */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const data = await service.request<unknown>(config)
  return data as T
}

export function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'GET', params })
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'POST', data })
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'PUT', data })
}

export function del<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ ...config, url, method: 'DELETE', data })
}

/** multipart / form 提交 */
export function putFormData<T>(url: string, form: FormData, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({
    ...config,
    url,
    method: 'PUT',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data', ...(config?.headers ?? {}) }
  })
}

/** 图片等二进制资源：带 token 拉取，返回可用于 <img src> 的 objectURL */
export async function getBlobUrl(url: string, config?: AxiosRequestConfig): Promise<string> {
  const blob = await request<Blob>({ ...config, url, method: 'GET', responseType: 'blob' })
  return URL.createObjectURL(blob)
}

/** 直接生成后端资源 URL（不带鉴权头，仅作降级直链） */
export function buildRawUrl(path: string): string {
  return `${API_BASE.replace(/\/$/, '')}${path}`
}

/** 把未知异常转成可展示文案 */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return '操作失败'
}

/** 把未知异常里的微信 errcode/errmsg 提取成一行细节文案 */
export function describeErrorDetail(error: unknown): string {
  if (error instanceof ApiError) {
    const parts: string[] = []
    if (error.errcode !== undefined) parts.push(`errcode=${error.errcode}`)
    if (error.errmsg) parts.push(`errmsg=${error.errmsg}`)
    if (error.httpStatus !== undefined && parts.length === 0) parts.push(`HTTP ${error.httpStatus}`)
    return parts.join(' · ')
  }
  return ''
}
