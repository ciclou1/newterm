import type { EChartsCoreOption } from 'echarts/core'
import type { ChartSpec, TableView } from './types'
import { asNumber, formatAxisValue, formatCompact, formatPercent, formatTableValue } from './format'

export type ChartKind = ChartSpec['type']

export interface ChartThemeVars {
  ink: string
  ink2: string
  muted: string
  grid: string
  axis: string
  surfaceRaised: string
  line: string
  font: string
  palette: string[]
}

export interface CompositionSlice {
  name: string
  value: number
  color: string
}

export interface Composition {
  slices: CompositionSlice[]
  total: number
  folded: boolean
}

export interface ChartOptionResult {
  kind: ChartKind | 'empty'
  option: EChartsCoreOption | null
  note: string
  /** Human-readable summary used as the chart's accessible description. */
  summary: string
}

export interface KpiItem {
  label: string
  value: unknown
  unit: string | null
  display: string
}

const MAX_PIE_SLICES = 7
const MAX_BAR_CATEGORIES = 20

function safeString(value: unknown): string {
  if (value === null || value === undefined) return ''
  return String(value)
}

export function readableInk(hex: string): string {
  const value = hex.replace('#', '')
  if (value.length !== 6) return '#ffffff'
  const r = parseInt(value.slice(0, 2), 16) / 255
  const g = parseInt(value.slice(2, 4), 16) / 255
  const b = parseInt(value.slice(4, 6), 16) / 255
  const linear = (channel: number) => (channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  const luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
  return luminance > 0.42 ? '#0b0b0b' : '#ffffff'
}

/**
 * Month categories would otherwise render as "2026-042026-05…" on a narrow
 * chart. Keep the year only when the series crosses a year boundary.
 */
export function axisLabels(values: string[]): string[] {
  const months = values.map((value) => /^(\d{4})-(\d{2})$/.exec(value))
  if (months.some((match) => match === null)) {
    return values
  }
  const parsed = months as RegExpExecArray[]
  const years = new Set(parsed.map((match) => match[1]))
  return parsed.map((match) => years.size === 1
    ? `${Number(match[2])}月`
    : `${match[1].slice(2)}-${match[2]}`)
}

/**
 * Build the pie/donut composition from the first series. Non-positive and
 * unparsable values are dropped, the tail is folded into "其他", and the
 * palette cycles predictably so tests and legends stay stable.
 */
export function composePie(
  categories: string[],
  values: (number | string | null)[],
  palette: string[],
  maxSlices = MAX_PIE_SLICES,
): Composition {
  const entries = categories
    .map((name, index) => ({ name: safeString(name), value: asNumber(values[index]) ?? 0 }))
    .filter((entry) => entry.value > 0)
    .sort((a, b) => b.value - a.value || a.name.localeCompare(b.name, 'zh-CN'))

  const total = entries.reduce((sum, entry) => sum + entry.value, 0)
  let visible = entries
  let folded = false
  if (entries.length > Math.max(1, maxSlices)) {
    const head = entries.slice(0, Math.max(1, maxSlices))
    const tail = entries.slice(Math.max(1, maxSlices))
    const other = tail.reduce((sum, entry) => sum + entry.value, 0)
    visible = other > 0 ? [...head, { name: '其他', value: other }] : head
    folded = true
  }
  return {
    slices: visible.map((entry, index) => ({
      ...entry,
      color: palette[index % Math.max(1, palette.length)] ?? '#2a78d6',
    })),
    total,
    folded,
  }
}

function hintFor(table: TableView, seriesName: string) {
  const column = table.columns.find((column) => column.label === seriesName || column.key === seriesName)
  return { format: column?.format ?? null, unit: column?.unit ?? null }
}

function pieOption(chart: ChartSpec, table: TableView, vars: ChartThemeVars): ChartOptionResult {
  const values = chart.series[0]?.data ?? []
  const composition = composePie(chart.categories, values, vars.palette)
  const { slices, total, folded } = composition
  const fontFamily = vars.font || 'system-ui, sans-serif'

  if (!slices.length || total <= 0) {
    return {
      kind: 'empty',
      option: null,
      note: '',
      summary: '当前数据没有可展示的占比。',
    }
  }

  const data = slices.map((slice) => ({
    name: slice.name,
    value: slice.value,
    itemStyle: { color: slice.color },
  }))
  const multipleSeries = chart.series.length > 1
  const notes: string[] = []
  if (folded) notes.push('类别较多，末位合并为“其他”；完整数据见下方表格。')
  if (multipleSeries) notes.push('饼图仅展示首个指标，其余指标见表格。')

  const option: EChartsCoreOption = {
    animationDuration: 420,
    textStyle: { color: vars.ink2, fontFamily, fontSize: 11 },
    tooltip: {
      renderMode: 'richText' as const,
      backgroundColor: vars.surfaceRaised,
      borderColor: vars.line,
      borderWidth: 1,
      padding: [8, 11],
      textStyle: { color: vars.ink, fontSize: 12, fontFamily },
      trigger: 'item',
      formatter: (params: { name?: string; value?: unknown; percent?: number }) => {
        const value = asNumber(params.value) ?? 0
        const share = typeof params.percent === 'number'
          ? formatPercent(params.percent, 1)
          : formatPercent(total > 0 ? (value / total) * 100 : 0, 1)
        return `${params.name ?? ''}\n${formatTableValue(value)} · ${share}`
      },
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      left: 'center',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 14,
      pageIconColor: vars.ink2,
      pageTextStyle: { color: vars.muted, fontFamily },
      textStyle: { color: vars.ink2, fontSize: 11, fontFamily },
      formatter: (name: string) => (name.length > 14 ? `${name.slice(0, 13)}…` : name),
    },
    series: [{
      name: chart.series[0]?.name ?? chart.title,
      type: 'pie',
      radius: ['40%', '68%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      minAngle: 1,
      padAngle: 1,
      itemStyle: {
        borderColor: vars.surfaceRaised,
        borderWidth: 2,
        borderRadius: 4,
      },
      label: {
        show: true,
        color: vars.ink2,
        fontFamily,
        fontSize: 10.5,
        formatter: (params: { name?: string; percent?: number }) =>
          (params.percent ?? 0) >= 6 ? `${params.name ?? ''}\n${formatPercent(params.percent ?? 0, 1)}` : '',
      },
      labelLine: {
        show: true,
        length: 8,
        length2: 10,
        lineStyle: { color: vars.axis },
      },
      emphasis: {
        scaleSize: 5,
        label: { fontWeight: 600 },
      },
      data,
    }],
  }

  const summary = `占比图，共 ${slices.length} 项：`
    + slices.map((slice) => {
      const hint = hintFor(table, chart.series[0]?.name ?? '')
      return `${slice.name} ${formatTableValue(slice.value, hint)}（${formatPercent(total > 0 ? slice.value / total * 100 : 0, 1)}）`
    }).join('，')

  return { kind: 'pie', option, note: notes.join(' '), summary }
}

function cartesianOption(chart: ChartSpec, table: TableView, vars: ChartThemeVars): ChartOptionResult {
  const fontFamily = vars.font || 'system-ui, sans-serif'
  const palette = vars.palette
  let categories = chart.categories.map(safeString)
  let values = chart.series.map((series) => series.data.slice())

  if (!categories.length || !values.length) {
    return { kind: 'empty', option: null, note: '', summary: '当前数据没有可展示的图表。' }
  }

  const allMissing = values.every((series) =>
    series.every((value) => asNumber(value) === null))
  if (allMissing) {
    return { kind: 'empty', option: null, note: '', summary: '当前数据没有数值列。' }
  }

  if (chart.type === 'bar') {
    const order = categories.map((_, index) => index)
    order.sort((a, b) => (asNumber(values[0]?.[b]) ?? 0) - (asNumber(values[0]?.[a]) ?? 0))
    const shown = order.slice(0, MAX_BAR_CATEGORIES)
    categories = shown.map((index) => categories[index])
    values = values.map((series) => shown.map((index) => series[index]))
  }

  const horizontal = chart.type === 'bar' && chart.horizontal
  if (horizontal) {
    categories = categories.slice().reverse()
    values = values.map((series) => series.slice().reverse())
  }
  const labels = axisLabels(categories)

  const baseText = { color: vars.ink2, fontFamily, fontSize: 11 }
  const tooltip = {
    renderMode: 'richText' as const,
    backgroundColor: vars.surfaceRaised,
    borderColor: vars.line,
    borderWidth: 1,
    padding: [8, 11],
    textStyle: { color: vars.ink, fontSize: 12, fontFamily },
  }
  const categoryAxis = {
    type: 'category' as const,
    data: labels,
    axisLine: { lineStyle: { color: vars.axis } },
    axisTick: { show: false },
    axisLabel: { color: vars.muted, fontSize: 11, fontFamily },
    splitLine: { show: false },
  }
  const valueAxis = {
    type: 'value' as const,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: vars.muted, fontSize: 11, formatter: (value: number) => formatAxisValue(value) },
    splitLine: { lineStyle: { color: vars.grid, type: 'solid' as const } },
  }

  const series = chart.series.map((item, index) => {
    const color = palette[index % Math.max(1, palette.length)] ?? '#2a78d6'
    const hint = hintFor(table, item.name)
    if (chart.type === 'line') {
      return {
        name: item.name,
        type: 'line' as const,
        data: values[index],
        smooth: false,
        symbol: 'circle',
        symbolSize: 8,
        showSymbol: categories.length <= 24,
        lineStyle: { width: 2, color },
        itemStyle: { color, borderColor: vars.surfaceRaised, borderWidth: 2 },
        emphasis: { focus: 'series' as const },
        connectNulls: false,
      }
    }
    return {
      name: item.name,
      type: 'bar' as const,
      data: values[index],
      barMaxWidth: 30,
      itemStyle: {
        color,
        borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0],
      },
      emphasis: { focus: 'series' as const },
      tooltip: {
        valueFormatter: (value: unknown) => formatCompact(value, hint),
      },
    }
  })

  const multi = chart.series.length > 1
  const axisCategory = horizontal ? categoryAxis : {
    ...categoryAxis,
    axisLabel: {
      ...categoryAxis.axisLabel,
      interval: 0,
      rotate: categories.length > 8 ? 32 : 0,
      width: 110,
      overflow: 'truncate' as const,
      hideOverlap: true,
    },
  }

  const option: EChartsCoreOption = {
    animationDuration: 420,
    textStyle: baseText,
    color: palette,
    tooltip: {
      ...tooltip,
      trigger: chart.type === 'line' ? 'axis' : 'item',
      axisPointer: chart.type === 'line'
        ? { type: 'line' as const, lineStyle: { color: vars.axis, width: 1 } }
        : undefined,
      formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params]
        const axisValue = (list[0] as { axisValueLabel?: string } | undefined)?.axisValueLabel
        const lines: string[] = []
        if (axisValue) lines.push(axisValue)
        list.forEach((entry: { seriesName?: string; value?: unknown }) => {
          const hint = hintFor(table, entry.seriesName ?? '')
          lines.push(`${entry.seriesName ?? ''}  ${formatTableValue(entry.value, hint)}`)
        })
        return lines.join('\n')
      },
    },
    legend: multi ? {
      type: 'scroll',
      bottom: 0,
      left: 'center',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 16,
      textStyle: { color: vars.ink2, fontSize: 11, fontFamily },
    } : undefined,
    grid: {
      left: horizontal ? 16 : 12,
      right: horizontal ? 28 : 18,
      top: 20,
      bottom: multi ? 42 : 26,
      containLabel: true,
    },
    xAxis: horizontal ? { ...valueAxis, splitLine: { show: false } } : axisCategory,
    yAxis: horizontal ? {
      ...categoryAxis,
      axisLabel: { ...categoryAxis.axisLabel, width: 120, overflow: 'truncate' as const, interval: 0 },
    } : valueAxis,
    series,
  }

  let note = ''
  if (chart.type === 'bar' && chart.categories.length > MAX_BAR_CATEGORIES) {
    note = `已按首个指标降序展示前 ${MAX_BAR_CATEGORIES} 项，完整数据见下方表格。`
  }
  const summary = `${chart.type === 'line' ? '折线图' : '柱状图'}，`
    + `${chart.series.map((series) => series.name).join('、')}，`
    + `共 ${chart.categories.length} 个类别。`

  return {
    kind: chart.type,
    option,
    note,
    summary,
  }
}

export function buildChartOption(chart: ChartSpec, table: TableView, vars: ChartThemeVars): ChartOptionResult {
  if (chart.type === 'pie') return pieOption(chart, table, vars)
  if (chart.type === 'bar' || chart.type === 'line') return cartesianOption(chart, table, vars)
  return { kind: 'empty', option: null, note: '', summary: '当前数据没有图表。' }
}

export function buildKpiItems(chart: ChartSpec, table: TableView): KpiItem[] {
  const values = chart.series[0]?.data ?? []
  return chart.categories.map((label, index) => {
    const hint = hintFor(table, label)
    const value = values[index]
    return {
      label,
      value,
      unit: hint.unit,
      display: formatCompact(value, hint),
    }
  })
}
