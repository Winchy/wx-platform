import type { PageResult } from '@/api/types'
import { del, get, post, type AxiosRequestConfig } from '@/utils/request'

export interface AuthorizerItem {
  appid: string
  nickName: string | null
  headImg: string | null
  alias: string | null
  principalName: string | null
  originalUsername: string | null
  verifyType: number | string | null
  serviceType: number | string | null
  authState: string | null
  authorizedAt: string | number | null
  releaseVersion: string | null
  remark: string | null
  tokenExpiredAt: string | number | null
}

/** 详情接口在列表字段之外额外返回权限集与体验版二维码路径 */
export interface AuthorizerDetail extends AuthorizerItem {
  funcScopes: string | null
  experienceQrcodeUrl: string | null
}

export interface AuthorizerQueryParams {
  keyword?: string
  authState?: string
  page?: number
  size?: number
}

export type AuthorizerPage = PageResult<AuthorizerItem>

export interface AuthSessionCreateParams {
  /** 可空：不传时由后端生成通用授权链接 */
  bizAppid?: string
  /** 可空：指定后微信只让管理员授权这几项权限集 */
  funcScopeIds?: Array<number | string>
}

export interface AuthSessionCreated {
  state: string
  authUrl: string
  mobileAuthUrl: string | null
  expiresAt: string | number | null
  redirectUri: string | null
}

export type AuthSessionStatus = 'WAITING' | 'CONFIRMED' | 'EXPIRED' | 'FAILED'

export interface AuthSessionResult {
  state: string
  status: AuthSessionStatus
  resultAppid: string | null
  errorMessage: string | null
}

export interface RefreshTokenResult {
  expiresIn: number
  tokenRefreshed: boolean
}

/** 体验版/线上版信息（wxa/getversioninfo 原样透传） */
export interface AuthorizerVersionInfo {
  expInfo: Record<string, unknown> | null
  releaseInfo: Record<string, unknown> | null
}

export function listAuthorizers(
  params: AuthorizerQueryParams,
  config?: AxiosRequestConfig
): Promise<AuthorizerPage> {
  return get<AuthorizerPage>('/authorizers', { ...params }, config)
}

/** 下拉选择用的简版拉取 */
export function fetchAuthorizerOptions(size = 200): Promise<AuthorizerPage> {
  return get<AuthorizerPage>('/authorizers', { page: 1, size })
}

export function getAuthorizer(appid: string): Promise<AuthorizerDetail> {
  return get<AuthorizerDetail>(`/authorizers/${appid}`)
}

export function createAuthSession(params: AuthSessionCreateParams = {}): Promise<AuthSessionCreated> {
  return post<AuthSessionCreated>('/authorizers/auth-sessions', {
    bizAppid: params.bizAppid || undefined,
    funcScopeIds: params.funcScopeIds?.length ? params.funcScopeIds : undefined
  })
}

export function getAuthSession(state: string): Promise<AuthSessionResult> {
  return get<AuthSessionResult>(`/authorizers/auth-sessions/${state}`, undefined, { silent: true })
}

export function refreshAuthorizerInfo(appid: string): Promise<AuthorizerItem> {
  return post<AuthorizerItem>(`/authorizers/${appid}/refresh`)
}

export function getAuthorizerVersion(appid: string): Promise<AuthorizerVersionInfo> {
  return get<AuthorizerVersionInfo>(`/authorizers/${appid}/version`)
}

export function refreshAuthorizerToken(appid: string): Promise<RefreshTokenResult> {
  return post<RefreshTokenResult>(`/authorizers/${appid}/token/refresh`)
}

export function revokeAuthorizer(appid: string): Promise<null> {
  return del<null>(`/authorizers/${appid}`)
}

/** 以微信侧授权关系为准，补录本地缺失的小程序，返回补录条数 */
export function syncAuthorizers(): Promise<number> {
  return post<number>('/authorizers/sync')
}
