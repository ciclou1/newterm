import { describe, expect, it } from 'vitest'
import {
  formatCostUsd, formatDateTime, formatDuration, formatPercent, formatTokens, statusBadge,
} from './format'

describe('format helpers', () => {
  it('formats token counts with thousands separators and handles missing values', () => {
    expect(formatTokens(1234567)).toBe('1,234,567')
    expect(formatTokens(0)).toBe('0')
    expect(formatTokens(null)).toBe('—')
    expect(formatTokens(undefined)).toBe('—')
    expect(formatTokens(Number.NaN)).toBe('—')
  })

  it('formats USD costs with micro precision only when needed', () => {
    expect(formatCostUsd(0)).toBe('$0.00')
    expect(formatCostUsd(0.000123)).toBe('$0.000123')
    expect(formatCostUsd(0.5)).toBe('$0.50')
    expect(formatCostUsd(12.345)).toBe('$12.35')
    expect(formatCostUsd(null)).toBe('—')
  })

  it('formats durations in ms and seconds', () => {
    expect(formatDuration(0)).toBe('0 ms')
    expect(formatDuration(999)).toBe('999 ms')
    expect(formatDuration(1500)).toBe('1.50 s')
    expect(formatDuration(12_300)).toBe('12.3 s')
    expect(formatDuration(undefined)).toBe('—')
  })

  it('formats timestamps and rejects invalid dates', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime('not-a-date')).toBe('—')
    expect(formatDateTime('2026-05-01T10:30:00Z')).toMatch(/2026/)
  })

  it('uses a single badge vocabulary for every answer status', () => {
    expect(statusBadge('OK')).toEqual({ label: '已完成', tone: 'ok' })
    expect(statusBadge('CLARIFY')).toEqual({ label: '待澄清', tone: 'warn' })
    expect(statusBadge('BLOCKED')).toEqual({ label: '已拦截', tone: 'bad' })
    expect(statusBadge('DEGRADED')).toEqual({ label: '降级完成', tone: 'warn' })
    expect(statusBadge('')).toEqual({ label: '未知', tone: 'neutral' })
  })

  it('formats percentages consistently', () => {
    expect(formatPercent(12.345, 1)).toBe('12.3%')
  })
})
