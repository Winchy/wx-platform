import { get, post, put, type AxiosRequestConfig } from '@/utils/request'

/** 平台配置：密钥类字段后端只回布尔（secretSet / messageTokenSet / aesKeySet），原值不下发 */
export interface PlatformConfig {
  componentAppid: string | null
  secretSet: boolean
  messageTokenSet: boolean
  aesKeySet: boolean
  siteBase: string | null
  apiBase: string | null
  /** 数据库存储值：0 未启用 / 1 已启用 */
  status: number | null
  ticketReceivedAt: string | number | null
  tokenExpiresAt: string | number | null
  /** 开放平台后台需配置的授权事件接收 URL */
  callbackUrl: string | null
  /** 开放平台后台需配置的消息与事件接收 URL，结尾的 $APPID$ 由微信替换成实际 appid */
  messageUrl: string | null
  /** 为 true 时说明后端使用临时主密钥加密凭据，重启后会失效 */
  ephemeralMasterKey: boolean | null
}

/** 只提交填写过的字段；secret / aesKey / token 留空表示保持原值 */
export interface PlatformConfigUpdateParams {
  componentAppid?: string
  componentSecret?: string
  messageToken?: string
  encodingAesKey?: string
  siteBase?: string
  apiBase?: string
  status?: number
}

export interface ConnectivityCheckResult {
  ok: boolean
  errcode?: number | null
  errmsg?: string | null
  expiresIn?: number | null
  ticketAgeSeconds?: number | null
}

export interface VerifyTicket {
  /** 后端已脱敏 */
  ticket: string
  receivedAt: string | number | null
}

export interface TicketPushRestartResult {
  errcode: number
  errmsg: string
  ticketAgeSeconds: number | null
}

export interface PlatformHealth {
  configReady: boolean
  ticketAgeSeconds: number | null
  ticketFresh: boolean
  componentTokenValid: boolean
  authorizerTotal: number
  authorizedCount: number
  tokenExpiresAt: string | number | null
  componentTokenCheck?: string | null
}

export function getPlatformConfig(): Promise<PlatformConfig> {
  return get<PlatformConfig>('/platform/config')
}

export function updatePlatformConfig(params: PlatformConfigUpdateParams): Promise<PlatformConfig> {
  return put<PlatformConfig>('/platform/config', params)
}

export function checkPlatformConnectivity(): Promise<ConnectivityCheckResult> {
  return post<ConnectivityCheckResult>('/platform/config/check', undefined, { silent: true })
}

export function fetchPlatformHealth(config?: AxiosRequestConfig): Promise<PlatformHealth> {
  return get<PlatformHealth>('/platform/health', undefined, config)
}

/** ticket 长时间没推送时，请求微信重新开始推送 */
export function restartTicketPush(): Promise<TicketPushRestartResult> {
  return post<TicketPushRestartResult>('/platform/ticket-push/restart')
}

export function listVerifyTickets(limit = 20): Promise<VerifyTicket[]> {
  return get<VerifyTicket[]>('/platform/tickets', { limit })
}

export type PlatformDomainKind = 'server' | 'jump'

export interface PlatformDomains {
  kind: PlatformDomainKind
  /** 全网发布版清单 */
  published: string[]
  /** 测试版清单，授权小程序在开发期以这份为准 */
  testing: string[]
  /** 未通过验证的域名，通常是校验文件没放对 */
  invalid: string[]
  limit: number
}

export interface PlatformDomainUpdateParams {
  action: 'add' | 'delete' | 'set'
  /** 裸主机名（不带 https:// 与路径）；add 传新增项，delete 传要删的项，set 传完整清单 */
  domains: string[]
  modifyPublishedTogether?: boolean
}

export interface PlatformDomainConfirmFile {
  fileName: string
  fileContent: string
}

/** 第三方平台账号自身的小程序域名白名单：授权小程序只能用这里登记过的域名。 */
export function getPlatformDomains(kind: PlatformDomainKind): Promise<PlatformDomains> {
  return get<PlatformDomains>(`/platform/domains/${kind}`)
}

export function updatePlatformDomains(
  kind: PlatformDomainKind,
  params: PlatformDomainUpdateParams
): Promise<PlatformDomains> {
  return put<PlatformDomains>(`/platform/domains/${kind}`, params)
}

export function getPlatformDomainConfirmFile(): Promise<PlatformDomainConfirmFile> {
  return get<PlatformDomainConfirmFile>('/platform/domains/confirm-file')
}
