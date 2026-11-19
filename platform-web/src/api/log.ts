import type { PageResult } from '@/api/types'
import { get } from '@/utils/request'

/* ---------------- 事件推送 ---------------- */

/** 列表接口不返回 rawBody / decryptedBody（报文可能很大），详情接口再取 */
export interface EventItem {
  id: number | string
  infoType: string | null
  appid: string | null
  bodyType: string | null
  signatureOk: number | boolean | null
  handled: number | boolean | null
  errorMessage: string | null
  receivedAt: string | number | null
}

export interface EventDetail extends EventItem {
  rawBody: string | null
  decryptedBody: string | null
}

export interface EventQueryParams {
  infoType?: string
  appid?: string
  page?: number
  size?: number
}

export type EventPage = PageResult<EventItem>

/* ---------------- 操作日志 ---------------- */

export interface OperationLogItem {
  id: number | string
  operatorId: number | string | null
  operatorName: string | null
  action: string | null
  authorizerAppid: string | null
  apiPath: string | null
  requestSummary: string | null
  responseSummary: string | null
  success: number | boolean | null
  errcode: number | null
  errmsg: string | null
  costMs: number | null
  createdAt: string | number | null
}

export interface OperationLogQueryParams {
  action?: string
  appid?: string
  success?: boolean | string | number
  page?: number
  size?: number
}

export type OperationLogPage = PageResult<OperationLogItem>

export function listEvents(params: EventQueryParams): Promise<EventPage> {
  return get<EventPage>('/events', { ...params })
}

export function getEventDetail(id: number | string): Promise<EventDetail | null> {
  return get<EventDetail | null>(`/events/${id}`, undefined, { silent: true })
}

export function listOperationLogs(params: OperationLogQueryParams): Promise<OperationLogPage> {
  return get<OperationLogPage>('/logs', { ...params })
}

export function listRecentOperationLogs(limit = 50): Promise<OperationLogItem[]> {
  return get<OperationLogItem[]>('/logs/recent', { limit })
}
