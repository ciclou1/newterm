<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { AuditStats, QueryAudit } from '../types'
import { ApiError, api } from '../api'

const props = defineProps<{ user: string; isAdmin: boolean }>()

const stats = ref<AuditStats | null>(null)
const audits = ref<QueryAudit[]>([])
const days = ref(7)
const all = ref(false)
const statusFilter = ref('')
const limit = ref(50)
const loading = ref(true)
const error = ref('')
const expanded = ref<number | null>(null)
const autoRefresh = ref(false)
let timer: number | undefined

const stat = computed<Record<string, number>>(() => {
  const raw = stats.value?.stats ?? {}
  const result: Record<string, number> = {}
  Object.entries(raw).forEach(([key, value]) => {
    result[key] = Number(value ?? 0)
  })
  return result
})

const cards = computed(() => [
  { label: '查询总数', value: stat.value.total ?? 0, hint: `近 ${days.value} 天` },
  { label: '成功率', value: stat.value.total ? Number(((stat.value.ok_count ?? 0) / stat.value.total * 100).toFixed(1)) : 0, suffix: '%' },
  { label: '平均延迟', value: stat.value.avg_ms ?? 0, suffix: 'ms' },
  { label: 'P95 延迟', value: stat.value.p95_ms ?? 0, suffix: 'ms' },
  { label: '拦截 / 错误', value: `${stat.value.blocked_count ?? 0} / ${stat.value.error_count ?? 0}` },
  { label: 'Token 消耗', value: (stat.value.prompt_tokens ?? 0) + (stat.value.completion_tokens ?? 0) },
  { label: '模型成本', value: `$${Number(stat.value.cost_usd ?? 0).toFixed(6)}` },
])

const scopedAll = computed(() => props.isAdmin && all.value)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ days: String(days.value), all: String(scopedAll.value) })
    const auditQuery = new URLSearchParams({
      limit: String(limit.value),
      all: String(scopedAll.value),
    })
    if (statusFilter.value) auditQuery.set('status', statusFilter.value)
    const [statsData, auditData] = await Promise.all([
      api.get<AuditStats>(`/api/audit/stats?${query}`, props.user),
      api.get<QueryAudit[]>(`/api/audit?${auditQuery}`, props.user),
    ])
    stats.value = statsData
    audits.value = auditData
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : '审计数据加载失败'
  } finally {
    loading.value = false
  }
}

function toggle(id: number) {
  expanded.value = expanded.value === id ? null : id
}

function timeLabel(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function tokens(audit: QueryAudit): number {
  return (audit.promptTokens ?? 0) + (audit.completionTokens ?? 0)
}

function toggleAuto() {
  autoRefresh.value = !autoRefresh.value
  clearInterval(timer)
  if (autoRefresh.value) {
    timer = window.setInterval(load, 15000)
  }
}

onMounted(load)
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="view-pad">
    <div class="page-head">
      <div>
        <div class="eyebrow">Observability</div>
        <h1>查询审计</h1>
        <p>每次问数都记录 SQL、守卫结果、行数、延迟、Token 与成本。</p>
      </div>
      <div style="display: flex; gap: 8px">
        <button class="btn" type="button" :class="{ primary: autoRefresh }" @click="toggleAuto">
          {{ autoRefresh ? '自动刷新中' : '自动刷新' }}
        </button>
        <button class="btn" type="button" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="error-box" style="margin-bottom: 16px">{{ error }}</div>

    <div class="audit-grid">
      <div v-for="card in cards" :key="card.label" class="stat-card">
        <div class="label">{{ card.label }}</div>
        <div class="value" style="font-size: 21px">
          {{ card.value }}<span v-if="card.suffix" class="muted" style="font-size: 11px; margin-left: 3px">{{ card.suffix }}</span>
        </div>
        <div v-if="card.hint" class="muted" style="font-size: 10.5px">{{ card.hint }}</div>
      </div>
    </div>

    <div class="filter-row">
      <select v-model.number="days" class="select" @change="load">
        <option :value="1">近 1 天</option>
        <option :value="7">近 7 天</option>
        <option :value="30">近 30 天</option>
        <option :value="90">近 90 天</option>
      </select>
      <select v-model="statusFilter" class="select" @change="load">
        <option value="">全部状态</option>
        <option value="OK">OK</option>
        <option value="CLARIFY">CLARIFY</option>
        <option value="BLOCKED">BLOCKED</option>
        <option value="ERROR">ERROR</option>
      </select>
      <select v-model.number="limit" class="select" @change="load">
        <option :value="20">20 条</option>
        <option :value="50">50 条</option>
        <option :value="100">100 条</option>
        <option :value="200">200 条</option>
      </select>
      <label v-if="props.isAdmin" class="badge neutral" style="cursor: pointer; padding: 7px 11px">
        <input v-model="all" type="checkbox" style="margin-right: 4px" @change="load" />
        查看全部用户
      </label>
      <span class="muted" style="font-size: 11px; margin-left: auto">
        {{ scopedAll ? '全局视图' : '仅本人数据' }}
      </span>
    </div>

    <div v-if="loading" class="empty-state"><span class="spinner"></span></div>

    <div v-else class="table-card">
      <table class="sem-table audit-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>用户</th>
            <th>状态</th>
            <th>问题</th>
            <th>生成方式</th>
            <th>行数</th>
            <th>延迟</th>
            <th>Token</th>
            <th>成本</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="audit in audits" :key="audit.id">
            <tr class="clickable" @click="toggle(audit.id)">
              <td class="sub" style="white-space: nowrap">{{ timeLabel(audit.createdAt) }}</td>
              <td>{{ audit.userId }}</td>
              <td>
                <span class="badge" :class="audit.status === 'OK' ? 'ok'
                  : audit.status === 'CLARIFY' ? 'warn' : 'bad'">
                  {{ audit.status }}
                </span>
              </td>
              <td class="q" style="max-width: 320px; white-space: normal">{{ audit.question }}</td>
              <td class="sub">{{ audit.generatedBy ?? '—' }}</td>
              <td class="sub">{{ audit.rowCount ?? '—' }}</td>
              <td class="sub">{{ audit.elapsedMs ?? '—' }} ms</td>
              <td class="sub">{{ tokens(audit) }}</td>
              <td class="sub">${{ Number(audit.costUsd ?? 0).toFixed(6) }}</td>
            </tr>
            <tr v-if="expanded === audit.id" class="detail-row">
              <td colspan="9">
                <div style="padding: 2px 0">
                  <div class="mini-badges" style="margin-bottom: 6px">
                    <span class="badge neutral">attempts {{ audit.attempts ?? 0 }}</span>
                    <span class="badge neutral">stage {{ audit.stage ?? '—' }}</span>
                    <span class="badge neutral">request {{ audit.requestId }}</span>
                    <span v-if="audit.errorMessage" class="badge bad">{{ audit.errorMessage }}</span>
                  </div>
                  <div class="muted" style="font-size: 11px">最终 SQL</div>
                  <div class="sql-block">{{ audit.finalSql ?? audit.generatedSql ?? '—' }}</div>
                  <div v-if="audit.schemaHits" class="muted mono" style="font-size: 10.5px">
                    schema hits: {{ audit.schemaHits }}
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!audits.length">
            <td colspan="9" class="empty-state">当前筛选条件下暂无审计记录</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
