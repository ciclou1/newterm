interface FormatHint {
  format?: string | null
  unit?: string | null
}

const decimal = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const integer = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const tokenFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

export function asNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return Number(value)
  }
  return null
}

export function formatTableValue(value: unknown, hint: FormatHint = {}): string {
  if (value === null || value === undefined || value === '') return '—'
  const number = typeof value === 'number' ? value : null
  if (number === null) return String(value)
  if (hint.format === 'percent') return `${decimal.format(number)}%`
  if (hint.format === 'currency') return decimal.format(number)
  if (hint.format === 'number') {
    return Number.isInteger(number) ? integer.format(number) : decimal.format(number)
  }
  return decimal.format(number)
}

export function formatCompact(value: unknown, hint: FormatHint = {}): string {
  if (value === null || value === undefined || value === '') return '—'
  const number = asNumber(value)
  if (number === null) return String(value)
  if (hint.format === 'percent') return `${decimal.format(number)}%`
  const absolute = Math.abs(number)
  let text: string
  if (absolute >= 100_000_000) text = `${decimal.format(number / 100_000_000)} 亿`
  else if (absolute >= 10_000) text = `${decimal.format(number / 10_000)} 万`
  else if (Number.isInteger(number)) text = integer.format(number)
  else text = decimal.format(number)
  return hint.unit ? `${text} ${hint.unit}` : text
}

export function formatAxisValue(value: number): string {
  const absolute = Math.abs(value)
  if (absolute >= 100_000_000) return `${decimal.format(value / 100_000_000)} 亿`
  if (absolute >= 10_000) return `${decimal.format(value / 10_000)} 万`
  return decimal.format(value)
}

export function formatPercent(value: number, digits = 2): string {
  return `${value.toFixed(digits)}%`
}

/** Unified token counter used by both the chat inspector and the audit page. */
export function formatTokens(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return tokenFormat.format(value)
}

/**
 * Unified USD display. Small model costs need micro-dollar precision, while
 * larger totals read better with cents; every screen calls this one function.
 */
export function formatCostUsd(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (value === 0) return '$0.00'
  const absolute = Math.abs(value)
  if (absolute < 0.01) return `$${value.toFixed(6)}`
  return `$${value.toFixed(2)}`
}

/** Unified duration formatting for latency columns and stat cards. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}

/** Unified zh-CN timestamp. Invalid or missing values render as an em dash. */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', { hour12: false })
}

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'info' | 'neutral'

export function statusBadge(status: string): { label: string; tone: BadgeTone } {
  switch (status) {
    case 'OK':
      return { label: '已完成', tone: 'ok' }
    case 'CLARIFY':
      return { label: '待澄清', tone: 'warn' }
    case 'BLOCKED':
      return { label: '已拦截', tone: 'bad' }
    case 'DEGRADED':
      return { label: '降级完成', tone: 'warn' }
    case 'ERROR':
      return { label: '失败', tone: 'bad' }
    default:
      return { label: status || '未知', tone: 'neutral' }
  }
}

export function guardTone(passed: boolean): BadgeTone {
  return passed ? 'ok' : 'bad'
}
