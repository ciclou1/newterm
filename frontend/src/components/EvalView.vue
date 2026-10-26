<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { EvalCaseResult, EvalDataset, EvalJob } from '../types'
import { ApiError, api, describeError } from '../api'
import { formatDateTime, formatDuration, statusBadge } from '../format'

const props = defineProps<{ isAdmin: boolean }>()

const categoryLabels: Record<string, string> = {
  sales: '销售',
  purchase: '采购',
  inventory: '库存',
  maintenance: '维修',
  receivable: '应收',
  equipment: '设备',
  clarification: '澄清',
  multi_metric: '多指标',
}

const POLL_INTERVAL_MS = 1200

const dataset = ref<EvalDataset | null>(null)
const jobs = ref<EvalJob[]>([])
const active = ref<EvalJob | null>(null)
const category = ref('')
const limit = ref<number | ''>('')
const loading = ref(true)
const starting = ref(false)
const error = ref('')
const errorRetryable = ref(true)
const filter = ref<'all' | 'failed'>('all')
const expandedCase = ref<number | null>(null)
let timer: number | undefined
let loadSeq = 0
let pollSeq = 0
let disposed = false

const results = computed(() => {
  const list = active.value?.results ?? []
  return filter.value === 'failed' ? list.filter((item) => !item.passed) : list
})

const failed = computed(() => (active.value ? Math.max(0, active.value.completed - active.value.passed) : 0))
const averageLatency = computed(() => {
  const list = active.value?.results ?? []
  if (!list.length) return 0
  return Math.round(list.reduce((sum, item) => sum + item.latencyMs, 0) / list.length)
})
const progressPercent = computed(() => active.value && active.value.total
  ? Math.round((active.value.completed / active.value.total) * 100) : 0)
const categoryRows = computed(() => {
  const entries = Object.entries(active.value?.categories ?? {})
  return entries
    .map(([key, stat]) => ({ key, label: categoryLabels[key] ?? key, ...stat }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
})
const questionCounts = computed(() => {
  const counts = new Map<string, number>()
  dataset.value?.questions.forEach((question) => {
    counts.set(question.category, (counts.get(question.category) ?? 0) + 1)
  })
  return [...counts.entries()].map(([key, value]) => ({ key, label: categoryLabels[key] ?? key, value }))
})
const datasetEmpty = computed(() => Boolean(dataset.value) && dataset.value!.questions.length === 0)

async function load() {
  const seq = ++loadSeq
  stopPolling()
  if (!props.isAdmin) {
    loading.value = false
    dataset.value = null
    jobs.value = []
    active.value = null
    error.value = '评测控制台需要管理员权限，请使用管理员账号登录。'
    errorRetryable.value = false
    return
  }
  loading.value = true
  error.value = ''
  errorRetryable.value = true
  try {
    const [datasetData, jobData] = await Promise.all([
      api.get<EvalDataset>('/api/eval/questions'),
      api.get<EvalJob[]>('/api/eval/jobs'),
    ])
    if (seq !== loadSeq || disposed) return
    dataset.value = datasetData
    jobs.value = jobData
    const current = active.value
    if (current) {
      const refreshed = jobData.find((job) => job.id === current.id)
      active.value = refreshed ?? (jobData.length ? jobData[0] : null)
    } else {
      active.value = jobData.length ? jobData[0] : null
    }
    if (active.value?.status === 'RUNNING') poll(active.value.id)
  } catch (caught) {
    if (seq !== loadSeq || disposed) return
    error.value = describeError(caught)
    errorRetryable.value = !(caught instanceof ApiError && caught.kind === 'forbidden')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function startRun() {
  if (starting.value || active.value?.status === 'RUNNING') return
  starting.value = true
  error.value = ''
  try {
    const job = await api.post<EvalJob>('/api/eval/run', {
      category: category.value || null,
      limit: limit.value === '' ? null : limit.value,
    })
    if (disposed) return
    active.value = job
    jobs.value = [job, ...jobs.value.filter((item) => item.id !== job.id)]
    filter.value = 'all'
    expandedCase.value = null
    poll(job.id)
  } catch (caught) {
    if (!disposed) {
      error.value = describeError(caught)
      errorRetryable.value = true
    }
  } finally {
    if (!disposed) starting.value = false
  }
}

function stopPolling() {
  pollSeq += 1
  clearTimeout(timer)
  timer = undefined
}

function poll(id: string) {
  stopPolling()
  const seq = pollSeq
  timer = window.setTimeout(async () => {
    if (disposed || seq !== pollSeq || active.value?.id !== id) return
    try {
      const job = await api.get<EvalJob>(`/api/eval/jobs/${id}`)
      if (disposed || seq !== pollSeq || active.value?.id !== id) return
      active.value = job
      jobs.value = jobs.value.map((item) => (item.id === job.id ? job : item))
      if (job.status === 'RUNNING') {
        poll(id)
      }
    } catch (caught) {
      if (disposed || seq !== pollSeq) return
      error.value = describeError(caught)
      errorRetryable.value = false
      active.value = active.value ? { ...active.value, status: 'FAILED' } : null
    }
  }, POLL_INTERVAL_MS)
}

function selectJob(id: string) {
  const job = jobs.value.find((item) => item.id === id)
  if (!job) return
  active.value = job
  expandedCase.value = null
  filter.value = 'all'
  error.value = ''
  if (job.status === 'RUNNING') poll(job.id)
  else stopPolling()
}

function toggleCase(id: number) {
  expandedCase.value = expandedCase.value === id ? null : id
}

function caseTone(result: EvalCaseResult) {
  return result.passed ? 'ok' : 'bad'
}

function caseStatus(status: string) {
  return statusBadge(status)
}

watch(() => props.isAdmin, load, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  loadSeq += 1
  stopPolling()
})
</script>

<template>
  <div class="view-pad">
    <div class="page-head">
      <div>
        <div class="eyebrow">Evaluation</div>
        <h1>执行准确率评测</h1>
        <p>逐题执行生成 SQL 与基准 SQL，按结果集比对，覆盖口径、维度与澄清场景。</p>
      </div>
      <span v-if="dataset" class="badge neutral">{{ dataset.questions.length }} 题 · v{{ dataset.version }}</span>
    </div>

    <div v-if="error" class="error-box" role="alert" style="margin-bottom: 16px">
      {{ error }}
      <div v-if="errorRetryable || props.isAdmin" class="error-actions">
        <button v-if="props.isAdmin" class="btn sm" type="button" :disabled="loading" @click="load">
          重新加载
        </button>
      </div>
    </div>

    <div v-if="loading" class="empty-state" role="status">
      <span class="spinner"></span>
      <span>评测数据加载中…</span>
    </div>

    <template v-else-if="props.isAdmin && dataset">
      <div v-if="datasetEmpty" class="empty-state">
        评测集为空，请先配置评测问题。
      </div>

      <template v-else>
        <div class="card" style="padding: 16px 18px; margin-bottom: 18px">
          <div class="filter-row" style="margin: 0">
            <select v-model="category" class="select" aria-label="评测分类" style="min-width: 130px">
              <option value="">全部分类</option>
              <option v-for="entry in questionCounts" :key="entry.key" :value="entry.key">
                {{ entry.label }}（{{ entry.value }}）
              </option>
            </select>
            <select v-model="limit" class="select" aria-label="评测题数" style="min-width: 120px">
              <option value="">全部题数</option>
              <option :value="20">前 20 题</option>
              <option :value="50">前 50 题</option>
              <option :value="100">前 100 题</option>
            </select>
            <button
              class="btn primary"
              type="button"
              :disabled="starting || active?.status === 'RUNNING'"
              @click="startRun"
            >
              <span v-if="starting || active?.status === 'RUNNING'" class="spinner"
                style="border-top-color: var(--surface)"></span>
              {{ active?.status === 'RUNNING' ? '评测进行中' : '开始评测' }}
            </button>
            <label v-if="jobs.length" class="sr-only" for="eval-job-select">历史评测任务</label>
            <select
              v-if="jobs.length"
              id="eval-job-select"
              class="select"
              style="margin-left: auto; min-width: 230px"
              :value="active?.id ?? ''"
              @change="selectJob(($event.target as HTMLSelectElement).value)"
            >
              <option v-for="job in jobs.slice(0, 10)" :key="job.id" :value="job.id">
                {{ formatDateTime(job.createdAt) }} · {{ job.passed }}/{{ job.total }}
              </option>
            </select>
          </div>
          <p class="muted" style="font-size: 11.5px; margin: 10px 0 0">
            评测使用与线上相同的 Agent、SQL 守卫与只读执行器；以当前管理员身份运行，避免行级权限干扰口径对比。
          </p>
        </div>

        <template v-if="active">
          <div class="eval-hero">
            <div class="accuracy-card">
              <div>
                <div class="label">EXECUTION ACCURACY</div>
                <div class="number" aria-live="polite">
                  {{ active.accuracy.toFixed(1) }}<small>%</small>
                </div>
                <div class="sub">
                  {{ active.passed }} / {{ active.completed }} 通过
                  <template v-if="active.status === 'RUNNING'"> · 已完成 {{ progressPercent }}%</template>
                </div>
              </div>
              <div class="progress" style="background: rgba(255,255,255,0.16)">
                <div :style="{ width: `${progressPercent}%`, background: 'var(--surface)' }"></div>
              </div>
            </div>
            <div class="accuracy-side">
              <div class="mini-stat">
                <span class="k">通过 / 失败</span>
                <span class="v">{{ active.passed }} <span class="muted" style="font-size: 12px">/ {{ failed }}</span></span>
              </div>
              <div class="mini-stat">
                <span class="k">平均单题耗时</span>
                <span class="v">{{ formatDuration(averageLatency) }}</span>
              </div>
              <div class="mini-stat">
                <span class="k">任务状态</span>
                <span class="badge" :class="active.status === 'COMPLETED' ? 'ok'
                  : active.status === 'FAILED' ? 'bad' : 'warn'">
                  {{ active.status === 'RUNNING' ? '运行中' : active.status === 'COMPLETED' ? '已完成' : '失败' }}
                </span>
              </div>
              <div v-if="active.finishedAt" class="mini-stat">
                <span class="k">完成时间</span>
                <span class="v" style="font-size: 12px">{{ formatDateTime(active.finishedAt) }}</span>
              </div>
            </div>
          </div>

          <div v-if="active.error" class="error-box" role="alert" style="margin-bottom: 16px">
            {{ active.error }}
          </div>

          <div v-if="categoryRows.length" class="card" style="padding: 16px 18px; margin-bottom: 18px">
            <div class="section-head" style="margin-bottom: 12px">
              <div>
                <h2>分类准确率</h2>
                <p>按语义主题拆分，定位口径或 Schema Linking 的薄弱环节。</p>
              </div>
            </div>
            <div class="category-list">
              <div v-for="row in categoryRows" :key="row.key" class="category-row">
                <span>{{ row.label }}</span>
                <div class="track">
                  <div class="fill" :style="{ width: `${row.accuracy}%` }"></div>
                </div>
                <span class="rate">{{ row.accuracy.toFixed(1) }}% · {{ row.passed }}/{{ row.total }}</span>
              </div>
            </div>
          </div>

          <div class="section-head">
            <div>
              <h2>逐题结果</h2>
              <p>{{ filter === 'all' ? '全部题目' : '仅显示未通过题目' }} · 点击行查看 SQL 对比</p>
            </div>
            <div class="filter-row" style="margin: 0">
              <button class="btn sm" :class="{ primary: filter === 'all' }" type="button" @click="filter = 'all'">
                全部 {{ active.completed }}
              </button>
              <button class="btn sm" :class="{ primary: filter === 'failed' }" type="button" @click="filter = 'failed'">
                失败 {{ failed }}
              </button>
            </div>
          </div>

          <div class="table-card">
            <table class="sem-table result-table">
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">结果</th>
                  <th scope="col">问题</th>
                  <th scope="col">分类</th>
                  <th scope="col">状态</th>
                  <th scope="col">行数</th>
                  <th scope="col">耗时</th>
                  <th scope="col">说明</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="result in results" :key="result.id">
                  <tr class="clickable" :class="{ 'row-fail': !result.passed }" tabindex="0"
                    :aria-expanded="expandedCase === result.id"
                    @click="toggleCase(result.id)"
                    @keydown.enter.prevent="toggleCase(result.id)"
                    @keydown.space.prevent="toggleCase(result.id)">
                    <td class="sub">{{ result.id }}</td>
                    <td>
                      <span class="badge" :class="caseTone(result)">{{ result.passed ? '通过' : '失败' }}</span>
                    </td>
                    <td class="q">{{ result.question }}</td>
                    <td><span class="badge neutral">{{ categoryLabels[result.category] ?? result.category }}</span></td>
                    <td>
                      <span class="badge" :class="caseStatus(result.status).tone">{{ caseStatus(result.status).label }}</span>
                    </td>
                    <td class="sub">{{ result.actualRows }}<span v-if="result.expectedRows !== result.actualRows"
                      class="muted"> / {{ result.expectedRows }}</span></td>
                    <td class="sub">{{ formatDuration(result.latencyMs) }}</td>
                    <td class="msg">{{ result.message }}</td>
                  </tr>
                  <tr v-if="expandedCase === result.id" class="detail-row">
                    <td colspan="8">
                      <div class="eval-sql">
                        <div class="muted" style="font-size: 11px">基准 SQL</div>
                        <div class="sql-block">{{ result.goldenSql ?? '—' }}</div>
                        <div class="muted" style="font-size: 11px">生成 SQL</div>
                        <div class="sql-block">{{ result.generatedSql ?? '—' }}</div>
                        <div v-if="result.code" class="badge bad">{{ result.code }}</div>
                      </div>
                    </td>
                  </tr>
                </template>
                <tr v-if="!results.length">
                  <td colspan="8" class="empty-state">
                    {{ active.status === 'RUNNING' ? '评测执行中，结果将实时出现…' : '没有符合条件的题目' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="active.status === 'RUNNING'" class="progress" style="margin-top: 14px" aria-hidden="true">
            <div :style="{ width: `${progressPercent}%` }"></div>
          </div>
        </template>

        <template v-else>
          <div class="card" style="padding: 22px 24px">
            <div class="section-head">
              <div>
                <h2>评测集构成</h2>
                <p>{{ dataset.description }}</p>
              </div>
            </div>
            <div class="stat-grid" style="margin-bottom: 0">
              <div v-for="entry in questionCounts" :key="entry.key" class="stat-card">
                <div class="label">{{ entry.label }}</div>
                <div class="value">{{ entry.value }}</div>
              </div>
            </div>
            <hr class="divider" />
            <div class="muted" style="font-size: 12px; line-height: 1.9">
              覆盖单指标聚合、时间口径（今年/去年/季度/近 N 月/本月）、分组维度、Top N、
              多指标同查与需要澄清的模糊问题。通过标准为：状态符合预期，且生成 SQL
              与基准 SQL 的结果集在列匹配后逐行一致（行序无关，数值按小数位容差）。
            </div>
            <div style="margin-top: 12px">
              <button class="btn primary" type="button" :disabled="starting" @click="startRun">
                <span v-if="starting" class="spinner" style="border-top-color: var(--surface)"></span>
                运行全部 {{ dataset.questions.length }} 题
              </button>
            </div>
          </div>
        </template>

        <div v-if="!jobs.length" class="empty-state" style="margin-top: 16px">
          暂无历史评测结果，运行一次评测后会显示在这里。
        </div>
      </template>
    </template>
  </div>
</template>
