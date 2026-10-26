<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec, TableView } from '../types'
import {
  buildChartOption, buildKpiItems, type ChartThemeVars,
} from '../chart-options'
import { formatTableValue } from '../format'

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  chart: ChartSpec
  table: TableView
  theme: string
}>()

const container = ref<HTMLDivElement | null>(null)
const mode = ref<'chart' | 'table'>('chart')
const note = ref('')
const summary = ref('')
const empty = ref(false)
let instance: ReturnType<typeof echarts.init> | null = null
let observer: ResizeObserver | null = null

const isKpi = computed(() => props.chart.type === 'kpi')
const kpiItems = computed(() => (isKpi.value ? buildKpiItems(props.chart, props.table) : []))
const hasData = computed(() => props.chart.categories.length > 0 && props.chart.series.length > 0)

function css(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function themeVars(): ChartThemeVars {
  return {
    ink: css('--ink'),
    ink2: css('--ink-2'),
    muted: css('--muted'),
    grid: css('--grid'),
    axis: css('--axis'),
    surfaceRaised: css('--surface-raised'),
    line: css('--line'),
    font: css('--font') || 'system-ui, sans-serif',
    palette: [1, 2, 3, 4, 5, 6, 7, 8].map((index) => css(`--series-${index}`) || '#2a78d6'),
  }
}

function render() {
  if (isKpi.value || mode.value !== 'chart') return
  const built = buildChartOption(props.chart, props.table, themeVars())
  note.value = built.note
  summary.value = built.summary
  empty.value = built.option === null
  if (!built.option || !container.value) {
    instance?.clear()
    return
  }
  if (!instance) {
    instance = echarts.init(container.value, undefined, { renderer: 'canvas' })
  }
  instance.setOption(built.option, true)
  instance.resize()
}

function tableValue(value: unknown, seriesName: string): string {
  const column = props.table.columns.find((item) => item.label === seriesName || item.key === seriesName)
  return formatTableValue(value, { format: column?.format ?? null })
}

async function setMode(next: 'chart' | 'table') {
  mode.value = next
  if (next === 'chart') {
    await nextTick()
    render()
  }
}

onMounted(() => {
  render()
  if (container.value) {
    observer = new ResizeObserver(() => {
      if (instance && !empty.value) instance.resize()
    })
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

watch(() => props.table, async () => {
  await nextTick()
  render()
}, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  instance?.dispose()
  instance = null
})
</script>

<template>
  <div v-if="isKpi" class="kpi-strip">
    <div v-for="item in kpiItems" :key="item.label" class="kpi">
      <div class="kpi-label">{{ item.label }}</div>
      <div class="kpi-value">
        {{ item.display }}
        <small v-if="item.unit && !String(item.display).includes(item.unit)">{{ item.unit }}</small>
      </div>
    </div>
  </div>

  <div v-else class="chart-wrap">
    <div class="chart-head">
      <div class="chart-title">{{ props.chart.title }}</div>
      <div class="chart-toggle" role="group" aria-label="图表显示方式">
        <button
          class="btn sm"
          :class="{ primary: mode === 'chart' }"
          type="button"
          :aria-pressed="mode === 'chart'"
          @click="setMode('chart')"
        >
          图表
        </button>
        <button
          class="btn sm"
          :class="{ primary: mode === 'table' }"
          type="button"
          :aria-pressed="mode === 'table'"
          @click="setMode('table')"
        >
          表格
        </button>
      </div>
    </div>

    <p class="sr-only" aria-live="polite">{{ summary }}</p>

    <template v-if="mode === 'chart'">
      <div
        v-show="!empty"
        ref="container"
        class="chart-canvas"
        role="img"
        :aria-label="`${props.chart.title}。${summary}`"
      ></div>
      <div v-if="empty" class="chart-empty" role="status">
        <span>当前数据没有可绘制的图表，请查看下方表格。</span>
        <button class="btn ghost sm" type="button" @click="setMode('table')">查看表格</button>
      </div>
    </template>

    <div v-else class="chart-table-wrap">
      <table v-if="hasData" class="data-table">
        <caption class="sr-only">{{ props.chart.title }}数据表</caption>
        <thead>
          <tr>
            <th scope="col">类别</th>
            <th v-for="series in props.chart.series" :key="series.name" scope="col">{{ series.name }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(category, index) in props.chart.categories" :key="`${category}-${index}`">
            <th scope="row" style="font-weight: 500">{{ category }}</th>
            <td v-for="series in props.chart.series" :key="series.name" class="num">
              {{ tableValue(series.data[index], series.name) }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="chart-empty">当前数据没有可展示的表格。</div>
    </div>

    <div v-if="note" class="muted" style="font-size: 11px; margin: 2px 6px 0">{{ note }}</div>
  </div>
</template>
