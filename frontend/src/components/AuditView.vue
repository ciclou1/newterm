<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AuditStats, QueryAudit } from '../types'
import { api, describeError } from '../api'
import { formatCostUsd, formatDateTime, formatDuration, formatTokens, statusBadge } from '../format'

const props = defineProps<{ isAdmin: boolean }>()

const REFRESH_INTERVAL_MS = 15_000

const stats = ref<AuditStats | null>(null)
const audits = ref<QueryAudit[]>([])
const days = ref(7)
const all = ref(false)
const statusFilter = ref('')
const limit = ref(50)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(false)
const expanded = ref<number | null>(null)
const autoRefresh = ref(false)
let timer: number | undefined
let loadSeq = 0
let moreSeq = 0
let disposed = false

const stat = computed<Record<string, number>>(() => {
  const raw = stats.value?.stats ?? {}
  const result: Record<string, number> = {}
  Object.entries(raw).forEach(([key, value]) => {
    result[key] = Number(value ?? 0)
  })
  return result
})

const cards = computed(() => {
  const total = stat.value.total ?? 0
  return [
    {
      label: '查询总数', value: total.toLocaleString('zh-CN'),
      hint: `近 ${days.value} 天`,
    },
    {
      label: '成功率',
      value: total ? `${((stat.value.ok_count ?? 0) / total * 100).toFixed(1)}%` : '—',
      hint: `${(stat.value.ok_count ?? 0).toLocaleString('zh-CN')} 次成功`,
    },
    { label: '平均延迟', value: formatDuration(stat.value.avg_ms), hint: '全部状态' },
    { label: 'P95 延迟', value: formatDuration(stat.value.p95_ms), hint: '近端分布' },
    {
      label: '拦截 / 错误', value: `${stat.value.blocked_count ?? 0} / ${stat.value.error_count ?? 0}`,
      hint: `澄清 ${stat.value.clarify_count ?? 0} 次`,
    },
    {
      label: 'Token 消耗',
      value: formatTokens((stat.value.prompt_tokens ?? 0) + (stat.value.completion_tokens ?? 0)),
      hint: '输入 + 输出',
    },
    { label: '模型成本', value: formatCostUsd(stat.value.cost_usd), hint: '按供应商计价' },
  ]
})

const scopedAll = computed(() => Boolean(props.isAdmin && all.value))
const scopeLabel = computed(() => scopedAll.value ? '全局视图 · 全部用户' : '仅本人数据')

function buildListQuery(beforeId?: number): string {
  const query = new URLSearchParams({
    limit: String(limit.value),
    all: String(scopedAll.value),
  })
  if (statusFilter.value) query.set('status', statusFilter.value)
  if (beforeId) query.set('beforeId', String(beforeId))
  return query.toString()
}

async function load() {
  if (loadingMore.value) return
  const seq = ++loadSeq
  moreSeq += 1
  loading.value = true
  error.value = ''
  try {
    const statsQuery = new URLSearchParams({ days: String(days.value), all: String(scopedAll.value) })
    const [statsData, auditData] = await Promise.all([
      api.get<AuditStats>(`/api/audit/stats?${statsQuery}`),
      api.get<QueryAudit[]>(`/api/audit?${buildListQuery()}`),
    ])
    if (seq !== loadSeq || disposed) return
    stats.value = statsData
    audits.value = auditData
    hasMore.value = auditData.length >= limit.value
  } catch (caught) {
    if (seq !== loadSeq || disposed) return
    error.value = describeError(caught)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function loadMore() {
  if (loading.value || loadingMore.value || !audits.value.length || !hasMore.value) return
  const seq = ++moreSeq
  loadingMore.value = true
  error.value = ''
  try {
    const beforeId = audits.value[audits.value.length - 1]?.id
    const page = await api.get<QueryAudit[]>(`/api/audit?${buildListQuery(beforeId)}`)
    if (seq !== moreSeq || disposed) return
    const seen = new Set(audits.value.map((item) => item.id))
    audits.value = [...audits.value, ...page.filter((item) => !seen.has(item.id))]
    hasMore.value = page.length >= limit.value
  } catch (caught) {
    if (seq !== moreSeq || disposed) return
    error.value = describeError(caught)
  } finally {
    if (seq === moreSeq) loadingMore.value = false
  }
}

function applyFilters() {
  expanded.value = null
  void load()
}

function toggle(id: number) {
  expanded.value = expanded.value === id ? null : id
}

function tokens(audit: QueryAudit): number {
  return (audit.promptTokens ?? 0) + (audit.completionTokens ?? 0)
}

function toggleAuto() {
  autoRefresh.value = !autoRefresh.value
  clearInterval(timer)
  timer = undefined
  if (autoRefresh.value) {
    timer = window.setInterval(() => {
      if (!loading.value && !loadingMore.value && !document.hidden) void load()
    }, REFRESH_INTERVAL_MS)
  }
}

watch(() => props.isAdmin, (admin) => {
  if (!admin && all.value) {
    all.value = false
    applyFilters()
  }
})

onMounted(() => {
  void load()
})

onBeforeUnmount(() => {
  disposed = true
  loadSeq += 1
  moreSeq += 1
  clearInterval(timer)
})
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
        <button
          class="btn"
          type="button"
          :class="{ primary: autoRefresh }"
          :aria-pressed="autoRefresh"
          @click="toggleAuto"
        >
          {{ autoRefresh ? '自动刷新中' : '自动刷新' }}
        </button>
        <button class="btn" type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="error-box" role="alert" style="margin-bottom: 16px">
      {{ error }}
      <div class="error-actions">
        <button class="btn sm" type="button" :disabled="loading" @click="load">重试</button>
      </div>
    </div>

    <div class="audit-grid">
      <div v-for="card in cards" :key="card.label" class="stat-card">
        <div class="label">{{ card.label }}</div>
        <div class="value" style="font-size: 21px">{{ card.value }}</div>
        <div v-if="card.hint" class="muted" style="font-size: 10.5px">{{ card.hint }}</div>
      </div>
    </div>

    <div class="filter-row">
      <label class="sr-only" for="audit-days">统计时间范围</label>
      <select id="audit-days" v-model.number="days" class="select" @change="applyFilters">
        <option :value="1">近 1 天</option>
        <option :value="7">近 7 天</option>
        <option :value="30">近 30 天</option>
        <option :value="90">近 90 天</option>
      </select>
      <label class="sr-only" for="audit-status">状态筛选</label>
      <select id="audit-status" v-model="statusFilter" class="select" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="OK">OK</option>
        <option value="CLARIFY">CLARIFY</option>
        <option value="DEGRADED">DEGRADED</option>
        <option value="BLOCKED">BLOCKED</option>
        <option value="ERROR">ERROR</option>
      </select>
      <label class="sr-only" for="audit-limit">每页条数</label>
      <select id="audit-limit" v-model.number="limit" class="select" @change="applyFilters">
        <option :value="20">20 条 / 页</option>
        <option :value="50">50 条 / 页</option>
        <option :value="100">100 条 / 页</option>
        <option :value="200">200 条 / 页</option>
      </select>
      <label v-if="props.isAdmin" class="checkbox-field" style="font-size: 12px">
        <input v-model="all" type="checkbox" @change="applyFilters" />
        <span>查看全部用户</span>
      </label>
      <span class="badge" :class="scopedAll ? 'warn' : 'neutral'" style="margin-left: auto">
        {{ scopeLabel }}
      </span>
    </div>

    <div v-if="loading" class="empty-state" role="status">
      <span class="spinner"></span>
      <span>审计记录加载中…</span>
    </div>

    <template v-else>
      <div class="table-card">
        <table class="sem-table audit-table">
          <caption class="sr-only">查询审计记录{{ scopeLabel }}</caption>
          <thead>
            <tr>
              <th scope="col">时间</th>
              <th scope="col">用户</th>
              <th scope="col">状态</th>
              <th scope="col">问题</th>
              <th scope="col">生成方式</th>
              <th scope="col">行数</th>
              <th scope="col">延迟</th>
              <th scope="col">Token</th>
              <th scope="col">成本</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="audit in audits" :key="audit.id">
              <tr
                class="clickable"
                tabindex="0"
                :aria-expanded="expanded === audit.id"
                @click="toggle(audit.id)"
                @keydown.enter.prevent="toggle(audit.id)"
                @keydown.space.prevent="toggle(audit.id)"
              >
                <td class="sub" style="white-space: nowrap">{{ formatDateTime(audit.createdAt) }}</td>
                <td>{{ audit.userId }}</td>
                <td>
                  <span class="badge" :class="statusBadge(audit.status).tone">
                    {{ statusBadge(audit.status).label }}
                  </span>
                </td>
                <td class="q" style="max-width: 320px; white-space: normal">{{ audit.question }}</td>
                <td class="sub">{{ audit.generatedBy ?? '—' }}</td>
                <td class="sub">{{ audit.rowCount ?? '—' }}</td>
                <td class="sub">{{ formatDuration(audit.elapsedMs) }}</td>
                <td class="sub">{{ formatTokens(tokens(audit)) }}</td>
                <td class="sub">{{ formatCostUsd(audit.costUsd) }}</td>
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

      <div v-if="hasMore" style="display: flex; justify-content: center; margin-top: 14px">
        <button class="btn" type="button" :disabled="loadingMore" :aria-busy="loadingMore" @click="loadMore">
          <span v-if="loadingMore" class="spinner"></span>
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </button>
      </div>
      <p v-else-if="audits.length" class="muted" style="text-align: center; font-size: 11px; margin-top: 14px">
        已显示全部 {{ audits.length }} 条记录
      </p>
    </template>
  </div>
</template>
