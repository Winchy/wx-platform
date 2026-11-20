import type { JsonNode } from '@/api/types'
import { del, get, post, postFormData, put, putFormData } from '@/utils/request'

/** 改名相关的错误由页面自己内联展示或带上下文提示，不再让拦截器重复弹一次 toast */
const SILENT = { silent: true } as const

/* ---------------- 基本信息 ---------------- */

export interface BasicInfo {
  nickname: string | null
  signature: string | null
  avatarUrl: string | null
  qrCodeUrl: string | null
  userName: string | null
  /** 微信原始值：0/1；未配置时为 -1 */
  openVerify: number | null
  openScannedSearch: number | null
  /** 微信 register_type：0 普通方式注册，非 0 才是通过 API 注册；未刷新授权资料时为 null */
  registerType: number | null
  /** 微信只允许「通过 API 注册」的小程序由第三方平台改名（否则 91001）；null 表示未知 */
  apiRenameSupported: boolean | null
  /** getaccountbasicinfo 原样返回，便于排查字段缺失 */
  raw: Record<string, unknown> | null
}

/** 名称检测：只有命中关键词（hitCondition）才需要提交资质材料 */
export interface NicknameCheckResult {
  hitCondition: boolean
  wording: string
}

/** 材料都是「新增临时素材」换来的 media_id：个人主体填 idCard，组织主体（协会/企业）填 license */
export interface NicknameUpdateParams {
  nickname: string
  idCard?: string
  license?: string
  /** 对应微信的 naming_other_stuff_1..5，最多 5 份 */
  namingOtherStuff?: string[]
}

/** 微信只在需要审核时返回 auditId；为空说明名称已直接生效 */
export interface NicknameUpdateResult {
  auditId: string | null
  wording: string
}

export interface SignatureUpdateParams {
  signature: string
}

/* ---------------- 服务器域名 / 业务域名 ---------------- */

/** 后端统一输出驼峰字段 */
export type DomainType = 'request' | 'wsRequest' | 'uploadFile' | 'downloadFile' | 'udp' | 'tcp'

export type DomainAction = 'add' | 'delete' | 'set'

/** 被微信拒绝的域名，键为驼峰域名类型；noIcp 为未备案域名 */
export interface InvalidDomains {
  request?: string[]
  wsRequest?: string[]
  uploadFile?: string[]
  downloadFile?: string[]
  udp?: string[]
  tcp?: string[]
  noIcp?: string[]
}

export interface DomainConfig {
  request: string[]
  wsRequest: string[]
  uploadFile: string[]
  downloadFile: string[]
  udp: string[]
  tcp: string[]
  invalid: InvalidDomains
  webview: string[]
}

/** 只回填被改动的类型 + invalid */
export type DomainUpdateResult = Partial<Omit<DomainConfig, 'invalid' | 'webview'>> & {
  invalid?: InvalidDomains
}

export interface DomainUpdateParams {
  action: DomainAction
  type: DomainType
  domains: string[]
}

export interface WebviewDomainUpdateParams {
  action: DomainAction
  domains: string[]
}

export interface WebviewConfirmFile {
  fileName: string | null
  fileContent: string | null
}

/* ---------------- 类目 ---------------- */

export interface CategoryRef {
  id: number
  name: string
}

/** 微信返回的已选类目：first/second 为对象，third 为数组（可能为空） */
export interface AuthorizerCategory {
  first: CategoryRef | null
  second: CategoryRef | null
  third: CategoryRef[] | null
}

export interface CategoryListResult {
  categories: AuthorizerCategory[]
  limit: number
  quota: number
  categoryLimit: number
}

export interface CategoryAddParams {
  first: number
  second: number
  third?: number
  certificates?: Array<Record<string, unknown>>
}

export interface CategoryRemoveParams {
  first: number
  second: number
  third?: number
}

/** /all-categories 结构由微信决定，交给 utils/category 归一化 */
export type RawCategoryTree = unknown

/* ---------------- 体验成员 ---------------- */

/** 微信只返回成员唯一标识，wechatId 后端可能为空 */
export interface TesterItem {
  userstr: string | null
  wechatId: string | null
}

export interface TesterBindParams {
  wechatId: string
}

export interface TesterUnbindParams {
  wechatId?: string
  userStr?: string
}

/* ---------------- 接口 ---------------- */

export function getBasicInfo(appid: string): Promise<BasicInfo> {
  return get<BasicInfo>(`/authorizers/${appid}/basic-info`)
}

/** 改名前先检测名称是否命中关键词 */
export function checkNickname(appid: string, nickname: string): Promise<NicknameCheckResult> {
  return post<NicknameCheckResult>(`/authorizers/${appid}/nickname/check`, { nickname }, SILENT)
}

export function updateNickname(
  appid: string,
  params: NicknameUpdateParams
): Promise<NicknameUpdateResult> {
  return put<NicknameUpdateResult>(`/authorizers/${appid}/nickname`, {
    nickname: params.nickname,
    idCard: params.idCard ?? '',
    license: params.license ?? '',
    namingOtherStuff: params.namingOtherStuff ?? []
  }, SILENT)
}

/** 资质材料图片先换成临时素材 media_id，微信侧只保存 3 天 */
export function uploadNicknameMaterial(appid: string, file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file, file.name)
  return postFormData<string>(`/authorizers/${appid}/nickname/materials`, form, SILENT)
}

export function queryNicknameAudit(appid: string, auditId: string): Promise<JsonNode> {
  return get<JsonNode>(`/authorizers/${appid}/nickname/audit`, { auditId })
}

export function updateSignature(appid: string, params: SignatureUpdateParams): Promise<JsonNode> {
  return put<JsonNode>(`/authorizers/${appid}/signature`, { signature: params.signature })
}

/** 头像上传：multipart 字段名 file，裁剪框为查询参数 */
export function updateAvatar(
  appid: string,
  file: File,
  crop?: { x1?: number; y1?: number; x2?: number; y2?: number }
): Promise<JsonNode> {
  const form = new FormData()
  form.append('file', file, file.name)
  const params: Record<string, number> = {}
  if (crop) {
    for (const key of ['x1', 'y1', 'x2', 'y2'] as const) {
      const value = crop[key]
      if (typeof value === 'number') params[key] = value
    }
  }
  return putFormData<JsonNode>(`/authorizers/${appid}/avatar`, form, { params, ...SILENT })
}

export function getServerDomains(appid: string): Promise<DomainConfig> {
  return get<DomainConfig>(`/authorizers/${appid}/domains`)
}

export function updateServerDomains(
  appid: string,
  params: DomainUpdateParams
): Promise<DomainUpdateResult> {
  return put<DomainUpdateResult>(`/authorizers/${appid}/domains`, params)
}

export function getWebviewDomains(appid: string): Promise<string[]> {
  return get<string[]>(`/authorizers/${appid}/webview-domains`)
}

export function updateWebviewDomains(
  appid: string,
  params: WebviewDomainUpdateParams
): Promise<string[]> {
  return put<string[]>(`/authorizers/${appid}/webview-domains`, params)
}

/** 业务域名校验文件：需把 fileContent 放到域名根目录下同名文件 */
export function getWebviewConfirmFile(appid: string): Promise<WebviewConfirmFile> {
  return get<WebviewConfirmFile>(`/authorizers/${appid}/webview-confirm-file`)
}

export function listCategories(appid: string): Promise<CategoryListResult> {
  return get<CategoryListResult>(`/authorizers/${appid}/categories`)
}

export function listAllCategories(appid: string): Promise<RawCategoryTree> {
  return get<RawCategoryTree>(`/authorizers/${appid}/all-categories`)
}

export function addCategory(appid: string, params: CategoryAddParams): Promise<JsonNode> {
  return post<JsonNode>(`/authorizers/${appid}/categories`, params)
}

/** 删除类目的三个参数都在 query 上 */
export function removeCategory(appid: string, params: CategoryRemoveParams): Promise<JsonNode> {
  const query: Record<string, number> = { first: params.first, second: params.second }
  if (params.third !== undefined) query.third = params.third
  return del<JsonNode>(`/authorizers/${appid}/categories`, undefined, { params: query })
}

export function listTesters(appid: string): Promise<TesterItem[]> {
  return get<TesterItem[]>(`/authorizers/${appid}/testers`)
}

/** 绑定体验成员，成功返回该成员的唯一标识 userstr */
export function bindTester(appid: string, params: TesterBindParams): Promise<string | null> {
  return post<string | null>(`/authorizers/${appid}/testers`, { wechatId: params.wechatId })
}

export function unbindTester(appid: string, params: TesterUnbindParams): Promise<null> {
  const query: Record<string, string> = {}
  if (params.wechatId) query.wechatId = params.wechatId
  if (params.userStr) query.userStr = params.userStr
  return del<null>(`/authorizers/${appid}/testers`, undefined, { params: query })
}

/* ---------------- 用户隐私保护指引 ---------------- */

/** 1 现网版本（已生效）/ 2 开发版（改动需重新提审并发布才生效） */
export type PrivacyVersion = 1 | 2

/** 字段名沿用微信的 snake_case，提交时要原样传给 setprivacysetting */
export interface PrivacySettingItem {
  privacy_key: string
  privacy_text: string
}

/** privacy_key 的可选值以微信返回的描述列表为准，会随版本新增 */
export interface PrivacyDescItem {
  privacy_key: string
  privacy_desc: string
}

export interface PrivacyOwnerSetting {
  /** 微信必填：收集方式有变动时通知用户的方式，例如「弹窗」「公告」 */
  notice_method?: string
  /** 四种联系方式至少要填一种 */
  contact_phone?: string
  contact_email?: string
  contact_qq?: string
  contact_weixin?: string
  /** 格式需为「数字+天」例如 30天，否则报 87072；留空则展示微信默认承诺文案 */
  store_expire_timestamp?: string
  /** 境外主体小程序需填写，否则审核驳回 */
  store_region?: number
  ext_file_media_id?: string
}

export interface PrivacySettingResult {
  /** 0 表示最近没有通过 commit 上传代码，此时微信检测不到隐私接口 */
  code_exist?: number | null
  /** 代码里检测出来的用户信息类型（privacy_key） */
  privacy_list?: string[] | null
  setting_list?: PrivacySettingItem[] | null
  owner_setting?: PrivacyOwnerSetting | null
  update_time?: number | string | null
  privacy_desc?: { privacy_desc_list?: PrivacyDescItem[] | null } | null
  sdk_privacy_info_list?: Array<{ sdk_name?: string; sdk_biz_name?: string }> | null
}

export interface PrivacyUpdateParams {
  privacyVer: PrivacyVersion
  /** privacyVer=1 时微信不允许传该字段 */
  settingList?: PrivacySettingItem[]
  ownerSetting: PrivacyOwnerSetting
}

export function getPrivacySetting(appid: string, privacyVer: PrivacyVersion): Promise<PrivacySettingResult> {
  return get<PrivacySettingResult>(`/authorizers/${appid}/privacy-setting`, { privacyVer })
}

export function updatePrivacySetting(appid: string, payload: PrivacyUpdateParams): Promise<JsonNode> {
  return put<JsonNode>(`/authorizers/${appid}/privacy-setting`, payload)
}
