import type { Row } from './types'

export function readPath(source: unknown, path: string): any {
  return path.replace(/\[(\d+)\]/g, '.$1').split('.').reduce((value: any, key) => {
    if (['__proto__', 'prototype', 'constructor'].includes(key)) return undefined
    return value != null && Object.prototype.hasOwnProperty.call(value, key) ? value[key] : undefined
  }, source)
}
// A deliberately small expression grammar; metadata is never executed as JavaScript.
export function visible(expression: string | undefined, row: Row = {}, form: Row = {}): boolean {
  if (!expression) return true
  const context = { ...row, row, form }
  const value = (token: string): unknown => {
    const text = token.trim()
    if (/^(['"]).*\1$/.test(text)) return text.slice(1, -1)
    if (text === 'true') return true
    if (text === 'false') return false
    if (text === 'null') return null
    if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text)
    return readPath(context, text)
  }
  return expression.split('||').some(group => group.split('&&').every(term => {
    const match = term.trim().match(/^([\w.]+)\s*(===|!==|==|!=|>=|<=|>|<)\s*(.+)$/)
    if (!match) return Boolean(value(term))
    const a: any = value(match[1]!), b: any = value(match[3]!)
    switch (match[2]) {
      case '==': case '===': return a === b
      case '!=': case '!==': return a !== b
      case '>': return a > b
      case '<': return a < b
      case '>=': return a >= b
      case '<=': return a <= b
      default: return false
    }
  }))
}
export function mapParams(map: Record<string, unknown>, context: Row, requestId: string): Row {
  return Object.fromEntries(Object.entries(map).map(([key, value]) => {
    if (value && typeof value === 'object' && 'const' in value) return [key, (value as { const: unknown }).const]
    if (value === '$requestId') return [key, requestId]
    if (typeof value === 'string' && /^(row|form|search|params)\./.test(value)) return [key, readPath(context, value)]
    return [key, value]
  }))
}
