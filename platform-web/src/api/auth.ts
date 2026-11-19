import { get, post } from '@/utils/request'

export interface LoginParams {
  username: string
  password: string
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  role: string
}

export interface LoginResult {
  token: string
  /** 令牌有效期（秒），后端 LoginResponse.expiresIn */
  expiresIn: number | null
  user: UserInfo
}

export function login(params: LoginParams): Promise<LoginResult> {
  // 登录失败不应触发 401 跳登录逻辑
  return post<LoginResult>('/auth/login', params, { skipAuthRedirect: true })
}

export function fetchCurrentUser(): Promise<UserInfo> {
  return get<UserInfo>('/auth/me')
}

export function logout(): Promise<null> {
  return post<null>('/auth/logout', undefined, { silent: true })
}
