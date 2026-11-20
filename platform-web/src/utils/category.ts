import type { AuthorizerCategory, CategoryTreeNode } from '@/api/config'

/**
 * 级联选择器把 `children: []` 当成还能往下展开的一层，末级必须整个省掉这个键。
 */
export function toCascaderOptions(nodes: CategoryTreeNode[]): Array<Record<string, unknown>> {
  return nodes.map((node) => {
    const option: Record<string, unknown> = { value: node.id, label: node.name }
    if (node.children && node.children.length) {
      option.children = toCascaderOptions(node.children)
    }
    return option
  })
}

/** 级联选择器只回传 id 路径，提审还要类目名称，按 id 逐级回查节点 */
export function resolveCategoryRefs(nodes: CategoryTreeNode[], ids: number[]): CategoryTreeNode[] {
  const refs: CategoryTreeNode[] = []
  let level = nodes
  for (const id of ids) {
    const hit = level.find((node) => node.id === id)
    if (!hit) break
    refs.push(hit)
    level = hit.children ?? []
  }
  return refs
}

/** 沿 id 路径找到最深那个节点，敏感类目的资质要求就挂在它身上 */
export function nodeAt(nodes: CategoryTreeNode[], ids: number[]): CategoryTreeNode | null {
  const refs = resolveCategoryRefs(nodes, ids)
  return refs.length === ids.length ? (refs[refs.length - 1] ?? null) : null
}

/** 已设置类目的中文路径 */
export function categoryPathText(category: AuthorizerCategory): string {
  return [category.firstName, category.secondName].filter((text) => Boolean(text)).join(' / ')
}

/** 已设置类目的 ID 路径文案 */
export function categoryPathIds(category: AuthorizerCategory): string {
  return [category.firstId, category.secondId].join(' / ')
}

const AUDIT_STATUS_TEXT: Record<number, string> = { 1: '审核中', 2: '审核不通过', 3: '审核通过' }

/** 微信用 audit_status 标出这条类目的审核结论，0 表示没走到审核这一步 */
export function categoryAuditText(category: AuthorizerCategory): string {
  return AUDIT_STATUS_TEXT[category.auditStatus] ?? ''
}

/** 只有驳回这一档微信会同时给原因，其余状态里它是空串 */
export function categoryAuditReason(category: AuthorizerCategory): string {
  return category.auditStatus === 2 ? category.auditReason ?? '' : ''
}

export function categoryAuditType(category: AuthorizerCategory): 'info' | 'warning' | 'success' {
  if (category.auditStatus === 3) return 'success'
  if (category.auditStatus === 2) return 'warning'
  return 'info'
}
