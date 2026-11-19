/** 时间/文本格式化辅助（不引额外依赖） */

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  // 微信的 create_time/update_time 是秒级时间戳，低于 1e11 的数字按秒处理（毫秒级早已超过该阈值）
  if (typeof value === 'number') return new Date(value < 1e11 ? value * 1000 : value)
  // 兼容 "2026-11-13 15:04:05"（Safari 需要替换 - 为 /）
  const normalized = value.includes('T') ? value : value.replace(/-/g, '/')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

function pad(num: number): string {
  return num < 10 ? `0${num}` : String(num)
}

export function formatDateTime(value: string | number | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  if (!date) return fallback
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes()
  )}:${pad(date.getSeconds())}`
}

export function formatDate(value: string | number | Date | null | undefined, fallback = '—'): string {
  const date = toDate(value)
  if (!date) return fallback
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 相对时间，例如“3 分钟前” */
export function formatRelativeTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  if (!date) return '—'
  const diff = Date.now() - date.getTime()
  if (diff < 0) return formatDateTime(date)
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return formatDate(date)
}

/** 秒数转“x 分 y 秒” */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '—'
  const safe = Math.max(0, Math.floor(seconds))
  const minutes = Math.floor(safe / 60)
  const rest = safe % 60
  if (minutes === 0) return `${rest} 秒`
  return `${minutes} 分 ${rest} 秒`
}

export function formatMillis(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

/** 未知值统一展示 */
export function displayValue(value: string | number | boolean | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined) return fallback
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string' && value.trim() === '') return fallback
  return String(value)
}

/** 尝试美化 JSON，失败原样返回 */
export function prettyJson(value: string | null | undefined): string {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 忽略，走兜底
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}

