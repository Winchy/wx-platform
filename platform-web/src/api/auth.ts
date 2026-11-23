import { get, post, put } from '@/utils/request'

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

export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

/**
 * 改密成功即 pwd_ver +1，**这次请求用的令牌也同时失效**，
 * 所以不让 401 自动跳转（那会把「请用新密码重新登录」的提示冲掉），由调用方自己清会话。
 */
export function changePassword(params: ChangePasswordParams): Promise<null> {
  return put<null>('/auth/password', params, { skipAuthRedirect: true })
}

export function logout(): Promise<null> {
  return post<null>('/auth/logout', undefined, { silent: true })
}
