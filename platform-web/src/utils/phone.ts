/**
 * 与后端 PhoneNumbers.normalize 同规则：先去空格/横线/括号，再摘掉 +86 / 0086 / 86 前缀，
 * 剩下必须是 1[3-9] 开头的 11 位。客户端提前规范化，是为了让「粘贴带 +86 的号」
 * 这种输入在这里就通过，而不是到服务端报「手机号格式不正确」。
 */
const MAINLAND = /^1[3-9]\d{9}$/

export function normalizePhone(raw: string | null | undefined): string | null {
  if (!raw) return null
  let digits = raw.replace(/[\s\-()]/g, '')
  if (digits.startsWith('+86')) {
    digits = digits.slice(3)
  } else if (digits.startsWith('0086')) {
    digits = digits.slice(4)
  } else if (digits.startsWith('86') && digits.length > 11) {
    digits = digits.slice(2)
  }
  return MAINLAND.test(digits) ? digits : null
}

export function isValidPhone(raw: string | null | undefined): boolean {
  return normalizePhone(raw) !== null
}
