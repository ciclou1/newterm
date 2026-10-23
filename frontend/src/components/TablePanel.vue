<script setup lang="ts">
import { computed } from 'vue'
import type { TableView } from '../types'
import { formatTableValue, asNumber } from '../format'

const props = defineProps<{ table: TableView }>()

const numericColumns = computed(() => new Set(
  props.table.columns
    .filter((column) => column.format === 'currency' || column.format === 'number'
      || column.format === 'percent'
      || props.table.rows.some((row) => asNumber(row[column.key]) !== null
        && typeof row[column.key] !== 'string'))
    .map((column) => column.key),
))

function headerLabel(label: string, unit: string | null): string {
  return unit ? `${label}（${unit}）` : label
}

function cell(row: Record<string, unknown>, key: string, format: string | null) {
  return formatTableValue(row[key], { format })
}

function downloadCsv() {
  const header = props.table.columns.map((column) => csvCell(column.label)).join(',')
  const lines = props.table.rows.map((row) =>
    props.table.columns.map((column) => csvCell(row[column.key])).join(','))
  const content = `﻿${[header, ...lines].join('\n')}`
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `chatbi-${new Date().toISOString().slice(0, 19).replaceAll(':', '')}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

function csvCell(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value)
  return `"${text.replaceAll('"', '""')}"`
}
</script>

<template>
  <div>
    <div class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th v-for="column in table.columns" :key="column.key">{{ headerLabel(column.label, column.unit) }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in table.rows" :key="index">
            <td v-for="column in table.columns" :key="column.key" :class="{ num: numericColumns.has(column.key) }">
              {{ cell(row, column.key, column.format) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="table-foot">
      <span>
        共 {{ table.rows.length }} 行<template v-if="table.truncated">，结果已截断</template>
      </span>
      <button class="btn ghost sm" type="button" @click="downloadCsv">导出 CSV</button>
    </div>
  </div>
</template>
