import type { AuthorizerCategory, CategoryRef } from '@/api/config'

export interface CategoryLeaf {
  id: number
  name: string
}

export interface CategoryNode extends CategoryLeaf {
  children: CategoryNode[]
}

/** 归一化后的两级（含三级可选）类目树，用于级联选择器 */
export function toCascaderOptions(nodes: CategoryNode[]): Array<Record<string, unknown>> {
  return nodes.map((first) => ({
    value: first.id,
    label: first.name,
    children: first.children.map((second) => ({
      value: second.id,
      label: second.name,
      children: second.children.map((third) => ({ value: third.id, label: third.name }))
    }))
  }))
}

/** 级联选择器只回传 id 路径，提审还要类目名称，按 id 逐级回查节点 */
export function resolveCategoryRefs(nodes: CategoryNode[], ids: number[]): CategoryLeaf[] {
  const refs: CategoryLeaf[] = []
  let level = nodes
  for (const id of ids) {
    const hit = level.find((node) => node.id === id)
    if (!hit) break
    refs.push({ id: hit.id, name: hit.name })
    level = hit.children
  }
  return refs
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return null
}

function pickNumber(record: Record<string, unknown> | null, keys: string[]): number | null {
  if (!record) return null
  for (const key of keys) {
    const raw = record[key]
    if (typeof raw === 'number' && Number.isFinite(raw)) return raw
    if (typeof raw === 'string' && raw.trim() !== '' && !Number.isNaN(Number(raw))) return Number(raw)
    if (asRecord(raw)) {
      const nested = pickNumber(asRecord(raw), ['id', 'value', 'code'])
      if (nested !== null) return nested
    }
  }
  return null
}

function pickName(record: Record<string, unknown> | null, keys: string[]): string | null {
  if (!record) return null
  for (const key of keys) {
    const raw = record[key]
    if (typeof raw === 'string' && raw.trim()) return raw.trim()
    if (asRecord(raw)) {
      const nested = pickName(asRecord(raw), ['name', 'label', 'text'])
      if (nested) return nested
    }
  }
  return null
}

function pickChildren(value: unknown): unknown[] {
  if (Array.isArray(value)) return value
  const record = asRecord(value)
  if (!record) return []
  for (const key of ['sub_categories', 'children', 'sub', 'list', 'items', 'secondList', 'categories']) {
    const child = record[key]
    if (Array.isArray(child)) return child
  }
  return []
}

/** 把 /categories/all 的未知结构转成统一节点树 */
export function normalizeCategoryTree(raw: unknown): CategoryNode[] {
  const list = Array.isArray(raw) ? raw : pickChildren(raw)
  const merged = new Map<number, CategoryNode>()

  for (const entry of list) {
    const record = asRecord(entry)
    if (!record) continue

    // 形态一：扁平结构 { first, firstClass, second, secondClass, third, thirdClass }
    const firstId = pickNumber(record, ['first', 'firstId', 'firstClassId', 'id'])
    const firstName = pickName(record, ['firstClass', 'firstName', 'first', 'name'])
    const secondId = pickNumber(record, ['second', 'secondId', 'secondClassId'])
    const secondName = pickName(record, ['secondClass', 'secondName', 'second', 'name'])
    const thirdId = pickNumber(record, ['third', 'thirdId', 'thirdClassId'])
    const thirdName = pickName(record, ['thirdClass', 'thirdName', 'third'])

    if (firstId !== null && firstName) {
      let first = merged.get(firstId)
      if (!first) {
        first = { id: firstId, name: firstName, children: [] }
        merged.set(firstId, first)
      } else if (!first.name) {
        first.name = firstName
      }

      if (secondId !== null && secondName) {
        let second = first.children.find((item) => item.id === secondId)
        if (!second) {
          second = { id: secondId, name: secondName, children: [] }
          first.children.push(second)
        }
        if (thirdId !== null && thirdName) {
          if (!second.children.some((item) => item.id === thirdId)) {
            second.children.push({ id: thirdId, name: thirdName, children: [] })
          }
        }
      }
    }

    // 形态二：嵌套结构 { id, name, sub_categories: [...] }
    const nestedId = pickNumber(record, ['id', 'value', 'code'])
    const nestedName = pickName(record, ['name', 'label', 'text'])
    if (nestedId !== null && nestedName) {
      const node: CategoryNode = {
        id: nestedId,
        name: nestedName,
        children: normalizeCategoryTree(pickChildren(record))
      }
      const existing = merged.get(nestedId)
      if (existing) {
        existing.children = dedupeNodes([...existing.children, ...node.children])
      } else {
        merged.set(nestedId, node)
      }
    }
  }

  return dedupeNodes(Array.from(merged.values()))
}

function dedupeNodes(nodes: CategoryNode[]): CategoryNode[] {
  const map = new Map<number, CategoryNode>()
  for (const node of nodes) {
    const prev = map.get(node.id)
    map.set(node.id, {
      id: node.id,
      name: node.name || prev?.name || '',
      children: dedupeNodes([...(prev?.children ?? []), ...node.children])
    })
  }
  return Array.from(map.values())
}

function refName(ref: CategoryRef | null | undefined): string {
  if (!ref) return ''
  return ref.name || String(ref.id ?? '')
}

function refId(ref: CategoryRef | null | undefined): number | null {
  if (!ref) return null
  const id = Number(ref.id)
  return Number.isFinite(id) ? id : null
}

/** 三级类目容错归一化：数组 / 单对象 / 数字都能读出来 */
export function thirdRefs(category: AuthorizerCategory | null | undefined): CategoryRef[] {
  const third = category?.third as unknown
  if (!third) return []
  const list = Array.isArray(third) ? third : [third]
  const refs: CategoryRef[] = []
  for (const entry of list) {
    if (entry && typeof entry === 'object') {
      const record = entry as Record<string, unknown>
      const id = Number(record.id ?? record.value ?? record.code)
      if (Number.isFinite(id)) {
        refs.push({ id, name: typeof record.name === 'string' ? record.name : String(id) })
      }
      continue
    }
    const id = Number(entry)
    if (Number.isFinite(id)) refs.push({ id, name: String(id) })
  }
  return refs
}

/** 已选类目的中文路径 */
export function categoryPathText(category: AuthorizerCategory): string {
  const thirds = thirdRefs(category).map((ref) => ref.name)
  return [refName(category.first), refName(category.second), ...thirds]
    .filter((text) => Boolean(text))
    .join(' / ')
}

/** 已选类目的 ID 路径文案 */
export function categoryPathIds(category: AuthorizerCategory): string {
  const thirds = thirdRefs(category).map((ref) => ref.id)
  return [refId(category.first), refId(category.second), ...thirds]
    .filter((id): id is number => id !== null)
    .join(' / ')
}

export function categoryIds(category: AuthorizerCategory): number[] {
  return [refId(category.first), refId(category.second), ...thirdRefs(category).map((ref) => ref.id)].filter(
    (id): id is number => id !== null
  )
}

/** 类目层级：一级 + 二级（+ 三级） */
export function categoryDepth(category: AuthorizerCategory): number {
  const hasFirst = refId(category.first) !== null
  const hasSecond = refId(category.second) !== null
  if (!hasFirst || !hasSecond) return categoryIds(category).length
  return thirdRefs(category).length > 0 ? 3 : 2
}

/** 删除类目时用的三级 ID（微信一次只认一个） */
export function firstThirdId(category: AuthorizerCategory): number | undefined {
  const third = thirdRefs(category)[0]
  return third ? third.id : undefined
}
