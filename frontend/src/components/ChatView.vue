<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ChatResponse } from '../types'
import {
  ApiError, api, describeError, isAbortError, type ApiErrorKind,
} from '../api'
import ChartPanel from './ChartPanel.vue'
import TablePanel from './TablePanel.vue'
import {
  formatCostUsd, formatDuration, formatPercent, formatTokens, guardTone, statusBadge,
} from '../format'

const props = defineProps<{ user: string; theme: string }>()

interface TurnError {
  message: string
  kind: ApiErrorKind
  retryable: boolean
  retryAt: number
}

interface Turn {
  id: number
  role: 'user' | 'assistant'
  question?: string
  response?: ChatResponse
  error?: TurnError
  pending?: boolean
  startedAt?: number
}

const REQUEST_TIMEOUT_MS = 120_000

const turns = ref<Turn[]>([])
const input = ref('')
const busy = ref(false)
const conversationId = ref<string | null>(null)
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
const copyNote = ref('')
const now = ref(Date.now())

let nextId = 1
let requestSeq = 0
let controller: AbortController | null = null
let clock: number | undefined
let copyTimer: number | undefined

const suggestions = [
  { q: '今年各区域销售额', tag: '销售 · 分组对比' },
  { q: '近6个月销售额趋势', tag: '趋势 · 时间序列' },
  { q: '今年维修成本最高的5个设备', tag: 'EAM · Top N' },
  { q: '当前各仓库库存金额', tag: '库存 · 分布' },
]

const userLabel = computed(() => props.user)
const isEmpty = computed(() => turns.value.length === 0)
const hasHistory = computed(() => turns.value.length > 0 || Boolean(conversationId.value))
const draftLength = computed(() => input.value.length)

function reset() {
  requestSeq += 1
  controller?.abort()
  controller = null
  turns.value = []
  conversationId.value = null
  input.value = ''
  busy.value = false
  copyNote.value = ''
  nextTick(() => textarea.value?.focus())
}

watch(() => props.user, () => reset())

function scrollToBottom() {
  nextTick(() => {
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight, behavior: 'smooth' })
  })
}

/**
 * After an answer arrives, stop at the top of that answer instead of the
 * absolute bottom: the conclusion, chart and table then read top-down, and the
 * SQL inspector stays one scroll away.
 */
function scrollToLatestAnswer() {
  nextTick(() => {
    const element = scroller.value
    if (!element) return
    const answers = element.querySelectorAll<HTMLElement>('.turn.assistant')
    const latest = answers.item(answers.length - 1)
    const top = latest
      ? latest.getBoundingClientRect().top - element.getBoundingClientRect().top + element.scrollTop - 12
      : element.scrollHeight
    element.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
  })
}

function autoGrow(event: Event) {
  const element = event.target as HTMLTextAreaElement
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 140)}px`
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    if (!busy.value) void send()
  }
}

function toTurnError(caught: unknown): TurnError {
  if (caught instanceof ApiError) {
    if (caught.kind === 'quota') {
      return {
        message: caught.message || '今日模型用量配额已用完，请明天再试或联系管理员。',
        kind: caught.kind,
        retryable: false,
        retryAt: 0,
      }
    }
    if (caught.kind === 'rate_limit') {
      return {
        message: caught.message || '请求过于频繁，请稍后再试。',
        kind: caught.kind,
        retryable: true,
        retryAt: Date.now() + (caught.retryAfterSeconds ?? 5) * 1000,
      }
    }
    return {
      message: describeError(caught),
      kind: caught.kind,
      retryable: caught.retryable,
      retryAt: 0,
    }
  }
  return {
    message: describeError(caught),
    kind: 'unknown',
    retryable: false,
    retryAt: 0,
  }
}

async function send(question?: string) {
  const fromInput = question === undefined
  const text = (question ?? input.value).trim()
  if (!text || busy.value) return

  if (fromInput) {
    input.value = ''
    if (textarea.value) textarea.value.style.height = 'auto'
  }

  const seq = ++requestSeq
  controller?.abort()
  controller = new AbortController()
  busy.value = true

  turns.value.push({ id: nextId++, role: 'user', question: text })
  const assistant: Turn = {
    id: nextId++,
    role: 'assistant',
    pending: true,
    question: text,
    startedAt: Date.now(),
  }
  turns.value.push(assistant)
  scrollToBottom()

  try {
    const response = await api.post<ChatResponse>('/api/chat/query', {
      question: text,
      conversationId: conversationId.value,
    }, {
      signal: controller.signal,
      timeoutMs: REQUEST_TIMEOUT_MS,
    })
    // A reset or a newer request must never be overwritten by this response.
    if (seq !== requestSeq) return
    assistant.pending = false
    assistant.response = response
    assistant.error = undefined
    conversationId.value = response.conversationId
  } catch (caught) {
    if (seq !== requestSeq || isAbortError(caught)) return
    assistant.pending = false
    assistant.error = toTurnError(caught)
  } finally {
    if (seq === requestSeq) {
      busy.value = false
      scrollToLatestAnswer()
    }
  }
}

function retry(turn: Turn) {
  if (!turn.question || busy.value) return
  if (turn.error && turn.error.retryAt > Date.now()) return
  void send(turn.question)
}

function editQuestion(turn: Turn) {
  if (!turn.question) return
  input.value = turn.question
  nextTick(() => {
    textarea.value?.focus()
    autoGrowFromElement()
  })
}

function autoGrowFromElement() {
  const element = textarea.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 140)}px`
}

async function copySql(sql: string | null) {
  if (!sql) return
  try {
    await navigator.clipboard?.writeText(sql)
    copyNote.value = 'SQL 已复制'
  } catch {
    copyNote.value = '复制失败，请手动选择 SQL'
  }
  window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => { copyNote.value = '' }, 2600)
}

function retryCountdown(turn: Turn): number {
  if (!turn.error || !turn.error.retryAt) return 0
  return Math.max(0, Math.ceil((turn.error.retryAt - now.value) / 1000))
}

function elapsedSeconds(turn: Turn): number {
  if (!turn.startedAt) return 0
  return Math.max(0, Math.round((now.value - turn.startedAt) / 1000))
}

function modeLabel(mode: string) {
  return mode === 'AI' ? 'AI 生成' : '离线规则引擎'
}

function rowsLabel(count: number) {
  return `${count} 行`
}

function failedChecks(response: ChatResponse) {
  return response.result.guard.checks.filter((check) => !check.passed)
}

function isResultStatus(status: string): boolean {
  return status === 'OK' || status === 'DEGRADED'
}

onMounted(() => {
  clock = window.setInterval(() => { now.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(clock)
  window.clearTimeout(copyTimer)
  requestSeq += 1
  controller?.abort()
})
</script>

<template>
  <div class="chat-layout">
    <div ref="scroller" class="chat-scroll">
      <div class="chat-inner">
        <section v-if="isEmpty" class="hero" aria-labelledby="chat-hero-title">
          <div class="hero-kicker">
            <span class="dot"></span>
            语义层已加载 · 当前身份 {{ userLabel }}
          </div>
          <h1 id="chat-hero-title">用一句中文，<br /><em>问清企业的数据。</em></h1>
          <p>
            自然语言经语义层裁剪、SQL 生成与安全校验后，在只读账号上执行。
            下方同时给出结论、图表与可追溯的 SQL。
          </p>
          <div class="suggestions">
            <button
              v-for="item in suggestions"
              :key="item.q"
              class="suggestion"
              type="button"
              :disabled="busy"
              @click="send(item.q)"
            >
              <span class="q">{{ item.q }}</span>
              <span class="tag">{{ item.tag }}</span>
            </button>
          </div>
        </section>

        <template v-for="turn in turns" :key="turn.id">
          <div v-if="turn.role === 'user'" class="turn user">
            <div class="user-bubble">{{ turn.question }}</div>
          </div>

          <div v-else class="turn assistant">
            <div class="avatar" aria-hidden="true">BI</div>
            <div class="answer-body">
              <div v-if="turn.pending" class="thinking" role="status" aria-live="polite">
                <span class="spinner" aria-hidden="true"></span>
                <div>
                  正在理解问题并生成 SQL…
                  <span class="muted" style="font-size: 11px">已用 {{ elapsedSeconds(turn) }} 秒</span>
                  <div class="thinking-steps">
                    <span>Schema Linking</span>
                    <span>指标口径</span>
                    <span>安全校验</span>
                    <span>只读执行</span>
                  </div>
                </div>
              </div>

              <div v-else-if="turn.error" class="error-box" role="alert">
                <div style="display: flex; align-items: flex-start; gap: 10px; flex-wrap: wrap">
                  <span style="flex: 1; min-width: 180px">{{ turn.error.message }}</span>
                  <span v-if="turn.error.kind === 'rate_limit'" class="badge warn">
                    限流 · {{ retryCountdown(turn) }} 秒后可重试
                  </span>
                  <span v-else-if="turn.error.kind === 'quota'" class="badge warn">今日配额</span>
                  <span v-else-if="turn.error.kind === 'timeout'" class="badge warn">超时</span>
                  <span v-else-if="turn.error.kind === 'network'" class="badge warn">网络异常</span>
                </div>
                <div v-if="turn.error.retryable || turn.question" class="error-actions">
                  <button
                    v-if="turn.error.retryable"
                    class="btn sm"
                    type="button"
                    :disabled="busy || retryCountdown(turn) > 0"
                    @click="retry(turn)"
                  >
                    重试
                  </button>
                  <button
                    v-if="turn.question"
                    class="btn ghost sm"
                    type="button"
                    :disabled="busy"
                    @click="editQuestion(turn)"
                  >
                    编辑问题
                  </button>
                </div>
              </div>

              <template v-else-if="turn.response">
                <div class="answer-meta" aria-live="polite">
                  <span class="badge" :class="statusBadge(turn.response.result.status).tone">
                    <span class="dot"></span>{{ statusBadge(turn.response.result.status).label }}
                  </span>
                  <span v-if="turn.response.result.degraded" class="badge warn" title="主链路不可用，已使用降级路径">
                    降级路径
                  </span>
                  <span class="badge neutral">{{ modeLabel(turn.response.result.mode) }}</span>
                  <span v-if="turn.response.result.usage.attempts > 1" class="badge warn">
                    自修复 {{ turn.response.result.usage.attempts - 1 }} 次
                  </span>
                  <span class="muted" style="font-size: 11px">
                    {{ formatDuration(turn.response.result.performance.totalMs) }}
                    · {{ rowsLabel(turn.response.result.result.rowCount) }}
                  </span>
                </div>

                <div v-if="turn.response.result.degraded" class="notice-box" role="status">
                  <strong>已降级完成：</strong>
                  {{ turn.response.result.degradedReason || '主模型暂不可用，结果由离线规则引擎生成，请核对口径。' }}
                </div>

                <p class="answer-text">{{ turn.response.answer }}</p>

                <div
                  v-if="turn.response.result.explanation && isResultStatus(turn.response.result.status)"
                  class="muted"
                  style="font-size: 12px; margin: -6px 0 12px"
                >
                  口径：{{ turn.response.result.explanation }}
                </div>

                <div v-if="turn.response.result.status === 'BLOCKED'" class="notice-box warn" role="alert">
                  <strong>安全校验未通过</strong>
                  <ul v-if="failedChecks(turn.response).length" class="reason-list">
                    <li v-for="check in failedChecks(turn.response)" :key="check.name">
                      {{ check.name }}<template v-if="check.detail">：{{ check.detail }}</template>
                    </li>
                  </ul>
                  <p v-else style="margin: 4px 0 0">
                    {{ turn.response.result.failure?.message || '本次查询未通过只读与对象白名单校验。' }}
                  </p>
                </div>

                <div v-else-if="turn.response.result.status === 'ERROR'" class="error-box" role="alert">
                  {{ turn.response.result.failure?.message || '本次查询未能完成，请稍后重试。' }}
                  <div class="error-actions">
                    <button class="btn sm" type="button" :disabled="busy" @click="retry(turn)">重试</button>
                  </div>
                </div>

                <div v-if="isResultStatus(turn.response.result.status)" class="answer-panel">
                  <ChartPanel
                    v-if="turn.response.chart"
                    :chart="turn.response.chart"
                    :table="turn.response.table"
                    :theme="props.theme"
                  />
                  <TablePanel v-if="turn.response.table.columns.length" :table="turn.response.table" />
                  <div class="answer-tools">
                    <button class="btn ghost sm" type="button" @click="copySql(turn.response.result.finalSql)">
                      复制 SQL
                    </button>
                    <button
                      v-if="turn.response.result.generatedSql
                        && turn.response.result.generatedSql !== turn.response.result.finalSql"
                      class="btn ghost sm"
                      type="button"
                      @click="copySql(turn.response.result.generatedSql)"
                    >
                      复制模型原始 SQL
                    </button>
                    <span v-if="copyNote" class="badge ok" role="status">{{ copyNote }}</span>
                    <span class="muted" style="font-size: 11px; margin-left: auto; align-self: center">
                      {{ turn.response.result.schema.tableCount }} 表 /
                      {{ turn.response.result.schema.metricCount }} 指标参与裁剪
                    </span>
                  </div>
                </div>

                <div
                  v-else-if="turn.response.result.status === 'CLARIFY' && turn.response.result.clarification"
                  class="followups"
                >
                  <span class="label">补充口径</span>
                  <button
                    v-for="option in turn.response.result.clarification.options"
                    :key="option.label"
                    class="chip"
                    type="button"
                    :disabled="busy"
                    @click="send(option.question)"
                  >
                    {{ option.label }}
                  </button>
                </div>

                <div v-if="turn.response.followUps.length" class="followups">
                  <span class="label">继续追问</span>
                  <button
                    v-for="followUp in turn.response.followUps"
                    :key="followUp"
                    class="chip"
                    type="button"
                    :disabled="busy"
                    @click="send(followUp)"
                  >
                    {{ followUp }}
                  </button>
                </div>

                <details class="inspector">
                  <summary>SQL、安全校验与执行轨迹</summary>
                  <div class="inspector-body">
                    <div class="inspector-grid">
                      <dl class="inspector-stat">
                        <dt>链路总耗时</dt>
                        <dd>{{ formatDuration(turn.response.result.performance.totalMs) }}</dd>
                      </dl>
                      <dl class="inspector-stat">
                        <dt>行数 / Token</dt>
                        <dd>
                          {{ formatTokens(turn.response.result.result.rowCount) }} /
                          {{ formatTokens(turn.response.result.usage.promptTokens + turn.response.result.usage.completionTokens) }}
                        </dd>
                      </dl>
                      <dl class="inspector-stat">
                        <dt>模型成本</dt>
                        <dd>{{ formatCostUsd(turn.response.result.usage.costUsd) }}</dd>
                      </dl>
                      <dl class="inspector-stat">
                        <dt>EXPLAIN 代价</dt>
                        <dd>{{ turn.response.result.performance.explainCost ?? '—' }}</dd>
                      </dl>
                      <dl class="inspector-stat">
                        <dt>置信度</dt>
                        <dd>{{ formatPercent(turn.response.result.confidence * 100, 0) }}</dd>
                      </dl>
                    </div>

                    <div class="muted" style="font-size: 11px; margin-bottom: 2px">最终执行 SQL</div>
                    <div class="sql-block">{{ turn.response.result.finalSql ?? '未生成 SQL' }}</div>

                    <template v-if="turn.response.result.generatedSql
                      && turn.response.result.generatedSql !== turn.response.result.finalSql">
                      <div class="muted" style="font-size: 11px; margin-bottom: 2px">模型原始 SQL</div>
                      <div class="sql-block">{{ turn.response.result.generatedSql }}</div>
                    </template>

                    <div class="muted" style="font-size: 11px; margin: 10px 0 2px">安全校验</div>
                    <div class="check-list">
                      <span
                        v-for="check in turn.response.result.guard.checks"
                        :key="check.name"
                        class="badge"
                        :class="guardTone(check.passed)"
                        :title="check.detail ?? check.name"
                      >
                        {{ check.passed ? '✓' : '✗' }} {{ check.name }}
                      </span>
                      <span v-if="!turn.response.result.guard.checks.length" class="muted" style="font-size: 11px">
                        无校验记录
                      </span>
                    </div>

                    <div v-if="turn.response.result.guard.rowScopes.length" class="muted" style="font-size: 11px">
                      行级权限：
                      <span
                        v-for="scope in turn.response.result.guard.rowScopes"
                        :key="scope.table"
                        class="mono"
                      >{{ scope.table }} · {{ scope.predicate }}</span>
                    </div>

                    <div v-if="turn.response.result.guard.tables.length" class="muted" style="font-size: 11px; margin-top: 4px">
                      命中表：<span class="mono">{{ turn.response.result.guard.tables.join(', ') }}</span>
                    </div>

                    <template v-if="turn.response.result.trace.length > 1">
                      <div class="muted" style="font-size: 11px; margin: 10px 0 2px">自修复轨迹</div>
                      <div
                        v-for="trace in turn.response.result.trace"
                        :key="trace.index"
                        class="mono"
                        style="font-size: 11px; color: var(--ink-2); padding: 3px 0"
                      >
                        #{{ trace.index }} {{ trace.stage }}
                        <span :class="trace.passed ? 'status-OK' : 'status-ERROR'">
                          {{ trace.passed ? '通过' : (trace.code ?? '失败') }}
                        </span>
                        <span class="muted">{{ formatDuration(trace.elapsedMs) }}</span>
                        <span v-if="trace.message" class="muted">· {{ trace.message }}</span>
                      </div>
                    </template>

                    <div v-if="turn.response.result.failure" class="error-box" style="margin-top: 10px">
                      {{ turn.response.result.failure.stage }} /
                      {{ turn.response.result.failure.code }}：
                      {{ turn.response.result.failure.message }}
                    </div>
                  </div>
                </details>
              </template>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="composer-shell">
      <div class="composer">
        <label class="sr-only" for="chat-question">输入业务问题</label>
        <textarea
          id="chat-question"
          ref="textarea"
          v-model="input"
          name="question"
          rows="1"
          maxlength="500"
          placeholder="问一个业务问题，例如：今年华东区各产品类别的销售额"
          :aria-describedby="draftLength > 450 ? 'chat-question-count' : undefined"
          @input="autoGrow"
          @keydown="onKeydown"
        ></textarea>
        <span v-if="draftLength > 450" id="chat-question-count" class="sr-only">
          {{ draftLength }} / 500 字
        </span>
        <button
          class="send-btn"
          type="button"
          :disabled="busy || !input.trim()"
          :aria-label="busy ? '正在生成回答' : '发送问题'"
          @click="send()"
        >
          <span v-if="busy" class="spinner" style="border-top-color: var(--surface)" aria-hidden="true"></span>
          <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2.5 8h10M8.5 4l4 4-4 4" stroke="currentColor" stroke-width="1.6"
              stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
      <div class="composer-hint">
        <span>Enter 发送 · Shift + Enter 换行</span>
        <span v-if="busy">正在生成，请稍候…</span>
        <span v-else-if="conversationId">多轮会话已开启 · 结果仅可读</span>
        <button v-if="hasHistory" class="btn ghost sm" type="button" @click="reset">新对话</button>
      </div>
    </div>
  </div>
</template>
