import { del, get, post, put } from '@/utils/request'

export interface LoginParams {
  username: string
  password: string
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  role: string
  /** 已绑定手机号的中段脱敏形式（138****8000）；没绑过则整个字段不出现（后端 NON_NULL） */
  phoneMasked?: string | null
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

/** 后端 SmsCodeResult：冷却与有效期都在这儿，界面用它起倒计时，不信本地时钟 */
export interface SmsCodeResult {
  cooldownSeconds: number
  expiresIn: number
}

export interface SmsLoginParams {
  phone: string
  code: string
}

/**
 * 取登录验证码。silent：命中冷却时后端给的是「验证码发送过于频繁，请 N 秒后再试」，
 * 页面要拿这句起倒计时，再弹一层全局提示就是重复打扰。
 */
export function sendLoginCode(phone: string): Promise<SmsCodeResult> {
  return post<SmsCodeResult>('/auth/sms-code', { phone }, { skipAuthRedirect: true, silent: true })
}

/** 手机号 + 验证码登录。同样 skipAuthRedirect：验证码错也是 401，不该把登录页刷成跳转。 */
export function loginBySms(params: SmsLoginParams): Promise<LoginResult> {
  return post<LoginResult>('/auth/login-sms', params, { skipAuthRedirect: true, silent: true })
}

/** 绑定/换绑前的取码：要已登录，未登录会 401 走全局跳转 */
export function sendBindCode(phone: string): Promise<SmsCodeResult> {
  return post<SmsCodeResult>('/auth/phone/sms-code', { phone }, { silent: true })
}

/**
 * 绑定或更换手机号，password 是当前账号密码（防令牌被盗后直接劫持登录方式）。
 * silent：原密码不正确 / 验证码错 / 号码被别的账号占用（409）都直接显示在弹窗里，不叠全局提示。
 */
export function bindPhone(params: { phone: string; code: string; password: string }): Promise<null> {
  return put<null>('/auth/phone', params, { silent: true })
}

export function unbindPhone(password: string): Promise<null> {
  return del<null>('/auth/phone', { password }, { silent: true })
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
