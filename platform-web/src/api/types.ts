/**
 * 后端统一响应结构与分页结构
 */
export interface ApiEnvelope<T = unknown> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/**
 * 后端把微信原始 JsonNode 原样透传的返回值：字段由微信决定，
 * 默认按对象读取，结构特殊时用泛型参数收窄（如 JsonNode<unknown[]>）。
 */
export type JsonNode<T = Record<string, unknown>> = T
