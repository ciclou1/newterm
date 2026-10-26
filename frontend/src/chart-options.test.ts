import { describe, expect, it } from 'vitest'
import type { ChartSpec, TableView } from './types'
import {
  axisLabels, buildChartOption, composePie, type ChartThemeVars,
} from './chart-options'

const vars: ChartThemeVars = {
  ink: '#0b0b0b',
  ink2: '#52514e',
  muted: '#898781',
  grid: '#e1e0d9',
  axis: '#c3c2b7',
  surfaceRaised: '#ffffff',
  line: '#e1e0d9',
  font: 'system-ui',
  palette: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4'],
}

const table: TableView = {
  columns: [
    { key: 'region', label: '区域', type: 'text', unit: null, format: null },
    { key: 'amount', label: '销售额', type: 'number', unit: '元', format: 'number' },
  ],
  rows: [],
  truncated: false,
}

function chart(partial: Partial<ChartSpec>): ChartSpec {
  return {
    type: 'bar',
    title: '测试图',
    xAxisName: '区域',
    yAxisName: '销售额',
    categories: ['华东', '华南', '华北'],
    series: [{ name: '销售额', data: [30, 80, 50] }],
    horizontal: false,
    ...partial,
  }
}

interface PieSeries {
  type: string
  radius?: string[]
  data: { name: string; value: number }[]
}

interface TooltipOption {
  renderMode?: string
  formatter: (params: unknown) => string
}

describe('chart option mapping', () => {
  it('maps a backend pie chart to a real donut instead of a stacked bar', () => {
    const result = buildChartOption(chart({ type: 'pie' }), table, vars)
    expect(result.kind).toBe('pie')
    expect(result.option).not.toBeNull()
    const series = (result.option as { series: PieSeries[] }).series
    expect(series).toHaveLength(1)
    expect(series[0].type).toBe('pie')
    expect(series[0].radius).toEqual(['40%', '68%'])
    expect(series[0].data.map((slice) => slice.name)).toEqual(['华南', '华北', '华东'])
    expect(result.summary).toContain('占比图')
  })

  it('folds the long tail into 其他 and exposes the note', () => {
    const categories = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i']
    const result = buildChartOption(chart({
      type: 'pie',
      categories,
      series: [{ name: '销售额', data: [9, 8, 7, 6, 5, 4, 3, 2, 1] }],
    }), table, vars)
    const data = (result.option as { series: PieSeries[] }).series[0].data
    expect(data).toHaveLength(8)
    expect(data[data.length - 1]).toMatchObject({ name: '其他', value: 3 })
    expect(result.note).toContain('其他')
  })

  it('returns an empty state for an all-missing pie', () => {
    const result = buildChartOption(chart({
      type: 'pie',
      series: [{ name: '销售额', data: [null, null, null] }],
    }), table, vars)
    expect(result.kind).toBe('empty')
    expect(result.option).toBeNull()
  })

  it('composes only positive slices and keeps deterministic palette order', () => {
    const composition = composePie(['甲', '乙', '丙'], [5, null, -2], vars.palette)
    expect(composition.total).toBe(5)
    expect(composition.slices).toEqual([{ name: '甲', value: 5, color: vars.palette[0] }])
  })

  it('keeps bar charts as sorted Cartesian bars', () => {
    const result = buildChartOption(chart({ type: 'bar' }), table, vars)
    expect(result.kind).toBe('bar')
    const option = result.option as {
      xAxis: { data: string[] }
      series: { type: string; data: number[] }[]
    }
    expect(option.series[0].type).toBe('bar')
    expect(option.series[0].data).toEqual([80, 50, 30])
    expect(option.xAxis.data).toEqual(['华南', '华北', '华东'])
  })

  it('maps line charts to line series without sorting existing order', () => {
    const result = buildChartOption(chart({ type: 'line' }), table, vars)
    const option = result.option as {
      xAxis: { data: string[] }
      series: { type: string; data: number[] }[]
    }
    expect(result.kind).toBe('line')
    expect(option.series[0].type).toBe('line')
    expect(option.series[0].data).toEqual([30, 80, 50])
    expect(option.xAxis.data).toEqual(['华东', '华南', '华北'])
  })

  it('returns an empty state when the chart has no categories', () => {
    const result = buildChartOption(chart({ categories: [], series: [] }), table, vars)
    expect(result.kind).toBe('empty')
  })

  it('renders tooltips as plain rich text instead of HTML', () => {
    const pie = buildChartOption(chart({ type: 'pie' }), table, vars)
    const pieTooltip = (pie.option as { tooltip: TooltipOption }).tooltip
    expect(pieTooltip.renderMode).toBe('richText')
    const pieText = pieTooltip.formatter({ name: '华东', value: 30, percent: 18.75 })
    expect(pieText).toContain('华东')
    expect(pieText).toContain('\n')
    expect(pieText).not.toContain('<')

    const bar = buildChartOption(chart({ type: 'bar' }), table, vars)
    const barTooltip = (bar.option as { tooltip: TooltipOption }).tooltip
    expect(barTooltip.renderMode).toBe('richText')
    const barText = barTooltip.formatter([{ seriesName: '销售额', value: 80 }])
    expect(barText).toContain('销售额')
    expect(barText).not.toContain('<')
  })

  it('shortens month labels and disambiguates cross-year series', () => {
    expect(axisLabels(['2026-01', '2026-02'])).toEqual(['1月', '2月'])
    expect(axisLabels(['2025-12', '2026-01'])).toEqual(['25-12', '26-01'])
    expect(axisLabels(['华东', '华南'])).toEqual(['华东', '华南'])
  })
})
