import type { TagProps } from 'element-plus'

export type StatusType = TagProps['type']

export interface StatusMeta {
  label: string
  type: StatusType
}

export type StatusKind =
  | 'authState'
  | 'platformState'
  | 'releaseStatus'
  | 'auditStatus'
  | 'authSession'
  | 'infoType'
  | 'verifyType'

const authStateMap: Record<string, StatusMeta> = {
  AUTHORIZED: { label: '已授权', type: 'success' },
  NORMAL: { label: '已授权', type: 'success' },
  UNAUTHORIZED: { label: '未授权', type: 'info' },
  NONE: { label: '未授权', type: 'info' },
  WAITING: { label: '待授权', type: 'warning' },
  PENDING: { label: '待授权', type: 'warning' },
  EXPIRED: { label: '授权已过期', type: 'warning' },
  INVALID: { label: '授权已失效', type: 'warning' },
  REVOKED: { label: '已取消授权', type: 'danger' },
  CANCELED: { label: '已取消授权', type: 'danger' },
  FAILED: { label: '授权失败', type: 'danger' }
}

const platformStateMap: Record<string, StatusMeta> = {
  '0': { label: '未启用', type: 'info' },
  '1': { label: '已启用', type: 'success' }
}

const releaseStatusMap: Record<string, StatusMeta> = {
  INIT: { label: '代码待提交', type: 'info' },
  COMMITTED: { label: '已提交代码', type: 'primary' },
  AUDITING: { label: '审核中', type: 'warning' },
  AUDIT_PASS: { label: '审核通过', type: 'success' },
  AUDIT_REJECT: { label: '审核驳回', type: 'danger' },
  PUBLISHED: { label: '已上线', type: 'success' },
  GRAY: { label: '灰度发布中', type: 'warning' },
  REVERTED: { label: '已回退', type: 'info' },
  FAILED: { label: '发布失败', type: 'danger' }
}

const auditStatusMap: Record<string, StatusMeta> = {
  '0': { label: '审核通过', type: 'success' },
  '1': { label: '审核不通过', type: 'danger' },
  '2': { label: '审核中', type: 'warning' },
  '3': { label: '审核已撤回', type: 'info' },
  '4': { label: '审核延后', type: 'warning' }
}

const authSessionMap: Record<string, StatusMeta> = {
  WAITING: { label: '等待管理员扫码', type: 'warning' },
  CONFIRMED: { label: '授权已完成', type: 'success' },
  EXPIRED: { label: '二维码已过期', type: 'info' },
  FAILED: { label: '授权失败', type: 'danger' }
}

const infoTypeMap: Record<string, StatusMeta> = {
  component_verify_ticket: { label: '票据推送', type: 'primary' },
  authorized: { label: '新增授权', type: 'success' },
  unauthorized: { label: '取消授权', type: 'danger' },
  updateauthorized: { label: '更新授权', type: 'warning' }
}

const verifyTypeMap: Record<string, StatusMeta> = {
  '-1': { label: '未认证', type: 'info' },
  '0': { label: '未认证', type: 'info' },
  '1': { label: '企业认证', type: 'success' },
  '2': { label: '个人认证', type: 'warning' }
}

const maps: Record<StatusKind, Record<string, StatusMeta>> = {
  authState: authStateMap,
  platformState: platformStateMap,
  releaseStatus: releaseStatusMap,
  auditStatus: auditStatusMap,
  authSession: authSessionMap,
  infoType: infoTypeMap,
  verifyType: verifyTypeMap
}

const fallbackLabel: Record<StatusKind, string> = {
  authState: '未知状态',
  platformState: '状态未知',
  releaseStatus: '未知状态',
  auditStatus: '未知审核状态',
  authSession: '未知',
  infoType: '其他事件',
  verifyType: '认证状态未知'
}

/** 归一化：兼容后端大小写/数字混用 */
function normalizeKey(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined || value === '') return ''
  return String(value).trim().toUpperCase()
}

export function resolveStatus(kind: StatusKind, value: string | number | boolean | null | undefined): StatusMeta {
  const table = maps[kind]
  const raw = value === null || value === undefined ? '' : String(value).trim()
  const key = normalizeKey(value)
  if (!key) return { label: fallbackLabel[kind], type: 'info' }
  const hit = table[key] ?? table[raw]
  if (hit) return hit
  return { label: raw, type: 'info' }
}

/** 发布状态 -> 步骤条 active（0 起）：指向当前该看/该做的那一步，提审完成后看结论在审核结果，过审后待办在发布。 */
const releaseStepMap: Record<string, number> = {
  INIT: 0,
  COMMITTED: 1,
  AUDITING: 3,
  AUDIT_REJECT: 3,
  AUDIT_PASS: 4,
  GRAY: 4,
  PUBLISHED: 4,
  REVERTED: 4,
  FAILED: 1
}

export function releaseStepIndex(status: string | null | undefined): number {
  if (!status) return 0
  return releaseStepMap[normalizeKey(status)] ?? 0
}

export const authStateOptions: Array<{ value: string; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'AUTHORIZED', label: '已授权' },
  { value: 'UNAUTHORIZED', label: '未授权' },
  { value: 'EXPIRED', label: '授权已过期' },
  { value: 'REVOKED', label: '已取消授权' }
]

export const infoTypeOptions: Array<{ value: string; label: string }> = [
  { value: '', label: '全部类型' },
  { value: 'component_verify_ticket', label: '票据推送' },
  { value: 'authorized', label: '新增授权' },
  { value: 'unauthorized', label: '取消授权' },
  { value: 'updateauthorized', label: '更新授权' }
]

/** 分阶段发布比例：微信接受 0~100 的整数，0 需开启「开发者优先」或「体验成员优先」之一 */
export const grayPercentageOptions: Array<{ value: number; label: string }> = [
  { value: 0, label: '0%（仅优先人群，需开启开发者/体验成员优先）' },
  { value: 1, label: '1%（约 1/100 用户）' },
  { value: 5, label: '5%（约 1/20 用户）' },
  { value: 10, label: '10%（约 1/10 用户）' },
  { value: 20, label: '20%（约 1/5 用户）' },
  { value: 30, label: '30%（约 3/10 用户）' },
  { value: 50, label: '50%（约一半用户）' },
  { value: 100, label: '100%（灰度计划内的全量）' }
]
