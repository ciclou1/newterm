<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec, TableView } from '../types'
import { asNumber, formatAxisValue, formatCompact, formatTableValue, formatPercent } from '../format'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  chart: ChartSpec
  table: TableView
  theme: string
}>()

const container = ref<HTMLDivElement | null>(null)
let instance: ReturnType<typeof echarts.init> | null = null
let observer: ResizeObserver | null = null

const isKpi = computed(() => props.chart.type === 'kpi')
const note = ref('')

function css(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function hintFor(seriesName: string) {
  const column = props.table.columns.find((c) => c.label === seriesName || c.key === seriesName)
  return { format: column?.format ?? null, unit: column?.unit ?? null }
}

/**
 * Month categories would otherwise render as "2026-042026-05…" on a narrow
 * chart. Keep the year only when the series crosses a year boundary.
 */
function axisLabels(values: string[]): string[] {
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

function readableInk(hex: string): string {  const value = hex.replace('#', '')
  if (value.length !== 6) return '#ffffff'
  const r = parseInt(value.slice(0, 2), 16) / 255
  const g = parseInt(value.slice(2, 4), 16) / 255
  const b = parseInt(value.slice(4, 6), 16) / 255
  const linear = (channel: number) => (channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  const luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
  return luminance > 0.42 ? '#0b0b0b' : '#ffffff'
}

interface CompositionSlice {
  name: string
  value: number
  color: string
}

function composition(): { slices: CompositionSlice[]; total: number; folded: boolean } {
  const palette = [1, 2, 3, 4, 5, 6, 7, 8].map((index) => css(`--series-${index}`))
  const values = props.chart.series[0]?.data ?? []
  const entries = props.chart.categories
    .map((name, index) => ({ name, value: Math.max(0, asNumber(values[index]) ?? 0) }))
    .filter((entry) => entry.value > 0)
    .sort((a, b) => b.value - a.value)
  let folded = false
  let visible = entries
  if (entries.length > 7) {
    const head = entries.slice(0, 7)
    const tail = entries.slice(7)
    const other = tail.reduce((sum, entry) => sum + entry.value, 0)
    if (other > 0) {
      head.push({ name: '其他', value: other })
    }
    visible = head
    folded = true
  }
  const total = entries.reduce((sum, entry) => sum + entry.value, 0)
  return {
    slices: visible.map((entry, index) => ({
      ...entry,
      color: palette[index % palette.length],
    })),
    total,
    folded,
  }
}

function buildOption(): EChartsCoreOption {
  const ink = css('--ink')
  const ink2 = css('--ink-2')
  const muted = css('--muted')
  const gridColor = css('--grid')
  const axisColor = css('--axis')
  const surface = css('--surface-raised')
  const lineColor = css('--line')
  const palette = [1, 2, 3, 4, 5, 6, 7, 8].map((index) => css(`--series-${index}`))
  const baseText = { color: ink2, fontFamily: css('--font') || 'system-ui, sans-serif', fontSize: 11 }

  const tooltip = {
    backgroundColor: surface,
    borderColor: lineColor,
    borderWidth: 1,
    padding: [8, 11],
    textStyle: { color: ink, fontSize: 12, fontFamily: baseText.fontFamily },
    extraCssText: 'box-shadow: var(--shadow-md); border-radius: 9px;',
  }

  const categoryAxis = {
    type: 'category' as const,
    data: props.chart.categories,
    axisLine: { lineStyle: { color: axisColor } },
    axisTick: { show: false },
    axisLabel: { color: muted, fontSize: 11, fontFamily: baseText.fontFamily },
    splitLine: { show: false },
  }

  const valueAxis = {
    type: 'value' as const,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: muted, fontSize: 11, formatter: (value: number) => formatAxisValue(value) },
    splitLine: { lineStyle: { color: gridColor, type: 'solid' as const } },
  }

  if (props.chart.type === 'pie') {
    const { slices, total, folded } = composition()
    note.value = folded ? '类别较多，末位合并为“其他”；完整数据见下方表格。' : ''
    const series = slices.map((slice) => ({
      name: slice.name,
      type: 'bar' as const,
      stack: 'total',
      barWidth: 46,
      itemStyle: { color: slice.color, borderColor: surface, borderWidth: 2 },
      label: {
        show: true,
        position: 'inside' as const,
        color: readableInk(slice.color),
        fontSize: 10.5,
        formatter: () => {
          const share = total > 0 ? (slice.value / total) * 100 : 0
          return share >= 8 ? `${share.toFixed(0)}%` : ''
        },
      },
      data: [slice.value],
    }))
    return {
      animationDuration: 420,
      textStyle: baseText,
      tooltip: {
        ...tooltip,
        trigger: 'item',
        formatter: (params: { seriesName: string; value: number }) => {
          const share = total > 0 ? (params.value / total) * 100 : 0
          return `<div style="font-weight:600;margin-bottom:3px">${escapeHtml(params.seriesName)}</div>
            <div style="font-variant-numeric:tabular-nums">${formatTableValue(params.value)} · ${formatPercent(share)}</div>`
        },
      },
      legend: {
        bottom: 0,
        left: 'center',
        icon: 'roundRect',
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 14,
        textStyle: { color: ink2, fontSize: 11, fontFamily: baseText.fontFamily },
      },
      grid: { left: 16, right: 16, top: 18, bottom: 42 },
      xAxis: { type: 'value', max: total || 1, show: false },
      yAxis: { type: 'category', data: ['构成'], show: false },
      series,
    }
  }

  let categories = props.chart.categories.slice()
  let values = props.chart.series.map((series) => series.data.slice())
  if (props.chart.type === 'bar') {
    const order = categories.map((_, index) => index)
    order.sort((a, b) => (asNumber(values[0]?.[b]) ?? 0) - (asNumber(values[0]?.[a]) ?? 0))
    const limit = 20
    const shown = order.slice(0, limit)
    categories = shown.map((index) => categories[index])
    values = values.map((series) => shown.map((index) => series[index]))
    note.value = order.length > limit
      ? `已按首个指标降序展示前 ${limit} 项，完整数据见下方表格。`
      : ''
  } else {
    note.value = ''
  }

  const horizontal = props.chart.type === 'bar' && props.chart.horizontal
  if (horizontal) {
    categories = categories.slice().reverse()
    values = values.map((series) => series.slice().reverse())
  }
  // Bar ordering / horizontal reversal above changes the category sequence, so
  // the axis must use the final list rather than the original one.
  categoryAxis.data = axisLabels(categories)

  const series = props.chart.series.map((item, index) => {
    const color = palette[index % palette.length]
    const hint = hintFor(item.name)
    if (props.chart.type === 'line') {
      return {
        name: item.name,
        type: 'line' as const,
        data: values[index],
        smooth: false,
        symbol: 'circle',
        symbolSize: 8,
        showSymbol: categories.length <= 24,
        lineStyle: { width: 2, color },
        itemStyle: { color, borderColor: surface, borderWidth: 2 },
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

  const multi = props.chart.series.length > 1
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

  return {
    animationDuration: 420,
    textStyle: baseText,
    color: palette,
    tooltip: {
      ...tooltip,
      trigger: props.chart.type === 'line' ? 'axis' : 'item',
      axisPointer: props.chart.type === 'line'
        ? { type: 'line' as const, lineStyle: { color: axisColor, width: 1 } }
        : undefined,
      formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params]
        const rows = list.map((entry: { marker?: string; seriesName?: string; value?: unknown }) => {
          const hint = hintFor(entry.seriesName ?? '')
          return `<div style="display:flex;align-items:center;gap:8px;justify-content:space-between;min-width:150px">
            <span>${entry.marker ?? ''}${escapeHtml(entry.seriesName ?? '')}</span>
            <strong style="font-variant-numeric:tabular-nums">${formatTableValue(entry.value, hint)}</strong>
          </div>`
        })
        const axisValue = (list[0] as { axisValueLabel?: string } | undefined)?.axisValueLabel
        return `${axisValue ? `<div style="font-weight:600;margin-bottom:4px">${escapeHtml(axisValue)}</div>` : ''}${rows.join('')}`
      },
    },
    legend: multi ? {
      bottom: 0,
      left: 'center',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 16,
      textStyle: { color: ink2, fontSize: 11, fontFamily: baseText.fontFamily },
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
}

function render() {
  if (!container.value || isKpi.value) return
  if (!instance) {
    instance = echarts.init(container.value, undefined, { renderer: 'canvas' })
  }
  instance.setOption(buildOption(), true)
  instance.resize()
}

function kpiItems() {
  const values = props.chart.series[0]?.data ?? []
  return props.chart.categories.map((label, index) => {
    const hint = hintFor(label)
    return {
      label,
      value: values[index],
      unit: hint.unit,
      display: formatCompact(values[index], hint),
    }
  })
}

onMounted(() => {
  render()
  if (container.value) {
    observer = new ResizeObserver(() => instance?.resize())
    observer.observe(container.value)
  }
})

watch(() => props.chart, async () => {
  await nextTick()
  render()
}, { deep: true })

watch(() => props.theme, async () => {
  await nextTick()
  render()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  instance?.dispose()
  instance = null
})
</script>

<template>
  <div v-if="isKpi" class="kpi-strip">
    <div v-for="item in kpiItems()" :key="item.label" class="kpi">
      <div class="kpi-label">{{ item.label }}</div>
      <div class="kpi-value">
        {{ item.display }}
        <small v-if="item.unit && !String(item.display).includes(item.unit)">{{ item.unit }}</small>
      </div>
    </div>
  </div>
  <div v-else class="chart-wrap">
    <div class="chart-title">{{ props.chart.title }}</div>
    <div ref="container" class="chart-canvas" role="img" :aria-label="props.chart.title"></div>
    <div v-if="note" class="muted" style="font-size: 11px; margin: 2px 6px 0">{{ note }}</div>
  </div>
</template>
