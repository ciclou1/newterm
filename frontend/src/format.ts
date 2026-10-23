interface FormatHint {
  format?: string | null
  unit?: string | null
}

const decimal = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const integer = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

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

export function statusBadge(status: string): { label: string; tone: 'ok' | 'warn' | 'bad' | 'info' | 'neutral' } {
  switch (status) {
    case 'OK':
      return { label: '已完成', tone: 'ok' }
    case 'CLARIFY':
      return { label: '待澄清', tone: 'warn' }
    case 'BLOCKED':
      return { label: '已拦截', tone: 'bad' }
    case 'ERROR':
      return { label: '失败', tone: 'bad' }
    default:
      return { label: status || '未知', tone: 'neutral' }
  }
}
