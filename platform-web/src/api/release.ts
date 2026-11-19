import type { JsonNode } from '@/api/types'
import { buildRawUrl, del, get, getBlobUrl, post } from '@/utils/request'

/* ---------------- 代码模板 / 草稿 ---------------- */

export interface CodeTemplate {
  templateId: string | number
  userVersion: string | null
  userDesc: string | null
  draftId: string | number | null
  source: string | number | null
  syncedAt: string | number | null
}

/** 微信没有删除草稿的接口，草稿只能转为模板。 */
export interface DraftItem {
  draftId: string | number
  userVersion: string | null
  userDesc: string | null
  screenshot: string | null
  createdTime: string | number | null
  createdAt: string | number | null
  syncedAt: string | number | null
}

export function listTemplates(): Promise<CodeTemplate[]> {
  return get<CodeTemplate[]>('/templates')
}

/** 不带 template_type 时微信返回普通模板（0）与标准模板库（1）的全部模板。 */
export function syncTemplates(): Promise<CodeTemplate[]> {
  return post<CodeTemplate[]>('/templates/sync')
}

export function deleteTemplate(templateId: string | number): Promise<null> {
  return del<null>(`/templates/${templateId}`)
}

export function listDrafts(): Promise<DraftItem[]> {
  return get<DraftItem[]>('/drafts')
}

export function syncDrafts(): Promise<DraftItem[]> {
  return post<DraftItem[]>('/drafts/sync')
}

/** 草稿转模板，返回微信的 { template_id, user_version, user_desc } */
export function draftToTemplate(
  draftId: string | number,
  templateType?: number
): Promise<JsonNode<Record<string, unknown>>> {
  return post<JsonNode<Record<string, unknown>>>(
    `/drafts/${draftId}/to-template`,
    templateType === undefined ? undefined : { templateType }
  )
}

/* ---------------- 上传代码 ---------------- */

export interface CommitExtItem {
  key: string
  value: string
}

export interface CommitParams {
  templateId: string | number
  /** 微信必填，最长 64 字符 */
  userVersion: string
  /** 微信必填 */
  userDesc: string
  /** 直接给出 ext_json 时优先于 ext / extPages */
  extJson?: string
  ext?: Record<string, unknown>
  extPages?: string[]
}

/* ---------------- 发布记录 ---------------- */

export type ReleaseStatus =
  | 'INIT'
  | 'COMMITTED'
  | 'AUDITING'
  | 'AUDIT_PASS'
  | 'AUDIT_REJECT'
  | 'PUBLISHED'
  | 'GRAY'
  | 'REVERTED'
  | 'FAILED'

export interface ReleaseRecord {
  id: number | string
  authorizerAppid: string | null
  templateId: string | number | null
  draftId: string | number | null
  extAppid: string | null
  userVersion: string | null
  userDesc: string | null
  submitDuration: number | null
  qrcodeMediaId: string | null
  status: ReleaseStatus | string | null
  lastAuditId: string | number | null
  auditReason: string | null
  errcode: number | null
  errmsg: string | null
  createdBy: number | string | null
  createdAt: string | number | null
  updatedAt: string | number | null
}

/* ---------------- 审核 ---------------- */

export interface AuditKeywordItem {
  keyword?: string
  hint?: string
  url?: string
}

/** 微信按「类目名称 + 类目 id」定位类目，一级/二级必填；tag 是页面标签，与类目无关 */
export interface AuditSubmitItem {
  address: string
  title?: string
  tag?: string[]
  firstClass?: string
  firstId?: number
  secondClass?: string
  secondId?: number
  thirdClass?: string
  thirdId?: number
  keyWordList?: AuditKeywordItem[]
}

export interface AuditSubmitParams {
  itemList: AuditSubmitItem[]
  feedbackInfo?: string
  versionDesc?: string
  extra?: Record<string, unknown>
}

export type AuditStatusValue = 0 | 1 | 2 | 3 | 4 | number

export interface AuditRecord {
  id?: number | string
  authorizerAppid?: string | null
  auditId: string | number | null
  status: AuditStatusValue | null
  reason: string | null
  detailJson: string | null
  submittedAt: string | number | null
  lastQueriedAt: string | number | null
}

/* ---------------- 灰度 / 回退 ---------------- */

/** 微信的分阶段发布按比例（0~100 的整数）生效，100 即全量；0 需搭配优先人群 */
export interface GrayReleaseParams {
  grayPercentage: number
  supportDebuggerFirst?: boolean
  supportExperiencerFirst?: boolean
}

export interface RevertParams {
  /** 留空表示回退到上一个线上版本 */
  appVersion?: string
}

/** 上传代码，后端返回落库后的发布记录 AppRelease */
export function commitCode(appid: string, params: CommitParams): Promise<ReleaseRecord> {
  return post<ReleaseRecord>(`/authorizers/${appid}/code`, params)
}

/** 体验版二维码：后端直接返回 PNG/JPEG 图片流 */
export function experienceQrcodePath(appid: string, path?: string): string {
  const query = path ? `?path=${encodeURIComponent(path)}` : ''
  return `/authorizers/${appid}/experience-qrcode${query}`
}

export function fetchExperienceQrcodeUrl(appid: string, path?: string): Promise<string> {
  return getBlobUrl(experienceQrcodePath(appid, path))
}

/** 直链（<img> 用 ?token= 查询参数鉴权，后端只对 GET 放行） */
export function experienceQrcodeDirectUrl(appid: string, path?: string): string {
  const token = getTokenForRawUrl()
  const base = buildRawUrl(experienceQrcodePath(appid, path))
  if (!token) return base
  return `${base}${base.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
}

/* ---------------- 页面 / 版本信息 ---------------- */

/** get_page 返回的页面列表 */
export function listPages(appid: string): Promise<JsonNode> {
  return get<JsonNode>(`/authorizers/${appid}/pages`)
}

/** 微信侧的一个版本。后端把 exp_time / release_time（epoch 秒）转成了本地时间字符串。 */
export interface WxVersion {
  version: string | null
  desc: string | null
  at: string | null
}

export interface VersionInfoResult {
  /** null 表示微信侧没有体验版（不是没读到） */
  exp: WxVersion | null
  release: WxVersion | null
  visitStatus: number | null
}

export function getCodeInfo(appid: string): Promise<VersionInfoResult> {
  return get<VersionInfoResult>(`/authorizers/${appid}/code-info`)
}

/** 服务状态：0 已暂停服务（主动暂停或违规暂停）/ 1 未暂停服务 / null 未读到 */
export function getVisitStatus(appid: string): Promise<number | null> {
  return get<number | null>(`/authorizers/${appid}/visit-status`)
}

/** 暂停（close）或恢复（open）服务，返回改完之后重新读到的状态。 */
export function setVisitStatus(appid: string, action: 'open' | 'close'): Promise<number | null> {
  return post<number | null>(`/authorizers/${appid}/visit-status`, { action })
}

/** 备案状态。微信用 1024~1031 表示未备案并编码缺失的前置条件，后端已解码成中文。 */
export interface IcpStatus {
  status: number
  statusText: string
  filed: boolean
  canceling: boolean
  entranceAvailable: boolean
  missingPrerequisites: string[]
  rejectReasons: { key_name?: string; error?: string; suggest?: string }[]
}

export function getIcpStatus(appid: string): Promise<IcpStatus> {
  return get<IcpStatus>(`/authorizers/${appid}/icp-status`)
}

/** 上传临时素材，返回 mediaid */
export function uploadMedia(appid: string, file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file, file.name)
  return post<string>(`/authorizers/${appid}/media`, form)
}

/* ---------------- 发布记录 ---------------- */

export function getLatestRelease(appid: string): Promise<ReleaseRecord | null> {
  return get<ReleaseRecord | null>(`/authorizers/${appid}/release/latest`)
}

export function listReleaseHistory(appid: string, limit = 20): Promise<ReleaseRecord[]> {
  return get<ReleaseRecord[]>(`/authorizers/${appid}/release/history`, { limit })
}

/* ---------------- 审核接口 ---------------- */

export function submitAudit(appid: string, params: AuditSubmitParams): Promise<string> {
  return post<string>(`/authorizers/${appid}/audit`, params)
}

export function getLatestAudit(appid: string): Promise<AuditRecord | null> {
  return get<AuditRecord | null>(`/authorizers/${appid}/audit/latest`)
}

export interface AuditScreenshot {
  mediaId: string
  contentType: string
  dataUrl: string
}

/** 驳回截图：微信只给 media_id，内容由后端逐个调永久素材接口取回。 */
export function getAuditScreenshots(appid: string): Promise<AuditScreenshot[]> {
  return get<AuditScreenshot[]>(`/authorizers/${appid}/audit/screenshots`)
}

/** 不传 auditId 时刷新最近一次提审单 */
export function refreshAudit(appid: string, auditId?: string | number): Promise<AuditRecord> {
  return post<AuditRecord>(`/authorizers/${appid}/audit/refresh`, auditId ? { auditId } : undefined)
}

export function undoAudit(appid: string): Promise<JsonNode> {
  return del<JsonNode>(`/authorizers/${appid}/audit`)
}

export function speedupAudit(appid: string, auditId?: string | number): Promise<JsonNode> {
  const query = auditId ? `?auditId=${encodeURIComponent(String(auditId))}` : ''
  return post<JsonNode>(`/authorizers/${appid}/audit/speedup${query}`)
}

/* ---------------- 发布 / 灰度 / 回退 ---------------- */

export function publishOnline(appid: string): Promise<ReleaseRecord> {
  return post<ReleaseRecord>(`/authorizers/${appid}/publish`)
}

export function grayRelease(appid: string, params: GrayReleaseParams): Promise<JsonNode> {
  return post<JsonNode>(`/authorizers/${appid}/gray-release`, params)
}

/** 微信侧的分阶段发布计划（getgrayreleaseplan）：status 0 初始 / 1 执行中 / 2 暂停中 / 3 执行完毕 / 4 被删除 */
export interface GrayPlan {
  status: number | null
  statusText: string
  /** 只有执行中/暂停中才还能结束或取消灰度 */
  actionable: boolean
  grayPercentage: number
  supportDebuggerFirst: boolean
  supportExperiencerFirst: boolean
  createdAt: string | null
  /** 微信侧已结束但本地发布单还挂着 GRAY 时，后端读计划的同时把它纠正了 */
  localCorrected: boolean
}

export function getGrayPlan(appid: string): Promise<GrayPlan> {
  return get<GrayPlan>(`/authorizers/${appid}/gray-release`)
}

export function cancelGray(appid: string): Promise<JsonNode> {
  return del<JsonNode>(`/authorizers/${appid}/gray-release`)
}

export function grayFinish(appid: string): Promise<ReleaseRecord> {
  return post<ReleaseRecord>(`/authorizers/${appid}/gray-release/finish`)
}

export function revertRelease(appid: string, params: RevertParams): Promise<JsonNode> {
  return post<JsonNode>(`/authorizers/${appid}/revert`, { appVersion: params.appVersion ?? '' })
}

/** 可回退的历史版本：微信原样返回，字段名不做保证 */
export function listRevertVersions(appid: string): Promise<JsonNode> {
  return get<JsonNode>(`/authorizers/${appid}/revert-versions`)
}

function getTokenForRawUrl(): string {
  try {
    return localStorage.getItem('wx_platform_token') ?? ''
  } catch {
    return ''
  }
}
