<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ChatResponse } from '../types'
import { ApiError, api } from '../api'
import ChartPanel from './ChartPanel.vue'
import TablePanel from './TablePanel.vue'
import { formatPercent, statusBadge } from '../format'

const props = defineProps<{ user: string; theme: string }>()

interface Turn {
  id: number
  role: 'user' | 'assistant'
  question?: string
  response?: ChatResponse
  error?: string
  pending?: boolean
}

const turns = ref<Turn[]>([])
const input = ref('')
const busy = ref(false)
const conversationId = ref<string | null>(null)
const scroller = ref<HTMLElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
let nextId = 1

const suggestions = [
  { q: '今年各区域销售额', tag: '销售 · 分组对比' },
  { q: '近6个月销售额趋势', tag: '趋势 · 时间序列' },
  { q: '今年维修成本最高的5个设备', tag: 'EAM · Top N' },
  { q: '当前各仓库库存金额', tag: '库存 · 分布' },
]

const userLabel = computed(() => ({
  zhangsan: '张杉 · 华东销售经理',
  wangwu: '王梧 · 华南销售经理',
  lisi: '李思 · 数据管理员',
}[props.user] ?? props.user))

const isEmpty = computed(() => turns.value.length === 0)

function reset() {
  turns.value = []
  conversationId.value = null
  input.value = ''
  busy.value = false
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
    send()
  }
}

async function send(question?: string) {
  const text = (question ?? input.value).trim()
  if (!text || busy.value) return
  input.value = ''
  if (textarea.value) textarea.value.style.height = 'auto'
  busy.value = true

  turns.value.push({ id: nextId++, role: 'user', question: text })
  const assistant: Turn = { id: nextId++, role: 'assistant', pending: true }
  turns.value.push(assistant)
  scrollToBottom()

  try {
    const response = await api.post<ChatResponse>('/api/chat/query', {
      question: text,
      conversationId: conversationId.value,
    }, props.user)
    assistant.pending = false
    assistant.response = response
    conversationId.value = response.conversationId
  } catch (error) {
    assistant.pending = false
    assistant.error = error instanceof ApiError
      ? error.message
      : '网络异常，请确认后端服务已启动。'
  } finally {
    busy.value = false
    scrollToLatestAnswer()
  }
}

function copySql(sql: string | null) {
  if (sql) navigator.clipboard?.writeText(sql)
}

function guardTone(passed: boolean) {
  return passed ? 'ok' : 'bad'
}

function modeLabel(mode: string) {
  return mode === 'AI' ? 'AI 生成' : '离线规则引擎'
}

function rowsLabel(count: number) {
  return `${count} 行`
}

function answerBadges(response: ChatResponse) {
  const result = response.result
  return {
    status: statusBadge(result.status),
    attempts: result.usage.attempts,
    latency: result.performance.totalMs,
  }
}

</script>

<template>
  <div class="chat-layout">
    <div ref="scroller" class="chat-scroll">
      <div class="chat-inner">
        <section v-if="isEmpty" class="hero">
          <div class="hero-kicker">
            <span class="dot"></span>
            语义层已加载 · 当前身份 {{ userLabel }}
          </div>
          <h1>用一句中文，<br /><em>问清企业的数据。</em></h1>
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
            <div class="avatar">BI</div>
            <div class="answer-body">
              <div v-if="turn.pending" class="thinking">
                <span class="spinner"></span>
                <div>
                  正在理解问题并生成 SQL…
                  <div class="thinking-steps">
                    <span>Schema Linking</span>
                    <span>指标口径</span>
                    <span>安全校验</span>
                    <span>只读执行</span>
                  </div>
                </div>
              </div>

              <div v-else-if="turn.error" class="error-box">
                {{ turn.error }}
              </div>

              <template v-else-if="turn.response">
                <div class="answer-meta">
                  <span class="badge" :class="answerBadges(turn.response).status.tone">
                    <span class="dot"></span>{{ answerBadges(turn.response).status.label }}
                  </span>
                  <span class="badge neutral">{{ modeLabel(turn.response.result.mode) }}</span>
                  <span v-if="turn.response.result.usage.attempts > 1" class="badge warn">
                    自修复 {{ turn.response.result.usage.attempts - 1 }} 次
                  </span>
                  <span class="muted" style="font-size: 11px">
                    {{ answerBadges(turn.response).latency }} ms
                    · {{ rowsLabel(turn.response.result.result.rowCount) }}
                  </span>
                </div>

                <p class="answer-text">{{ turn.response.answer }}</p>

                <div
                  v-if="turn.response.result.explanation && turn.response.result.status === 'OK'"
                  class="muted"
                  style="font-size: 12px; margin: -6px 0 12px"
                >
                  口径：{{ turn.response.result.explanation }}
                </div>

                <div v-if="turn.response.result.status === 'OK'" class="answer-panel">
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
                        <dt>链路面耗时</dt>
                        <dd>{{ turn.response.result.performance.totalMs }} ms</dd>
                      </dl>
                      <dl class="inspector-stat">
                        <dt>行数 / Token</dt>
                        <dd>
                          {{ turn.response.result.result.rowCount }} /
                          {{ turn.response.result.usage.promptTokens + turn.response.result.usage.completionTokens }}
                        </dd>
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
                      >{{ scope.table }} · {{ scope.predicate }}　</span>
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
                        <span class="muted">{{ trace.elapsedMs }} ms</span>
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
        <textarea
          ref="textarea"
          v-model="input"
          rows="1"
          placeholder="问一个业务问题，例如：今年华东区各产品类别的销售额"
          @input="autoGrow"
          @keydown="onKeydown"
        ></textarea>
        <button class="send-btn" type="button" :disabled="busy || !input.trim()" aria-label="发送" @click="send()">
          <span v-if="busy" class="spinner" style="border-top-color: var(--surface)"></span>
          <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2.5 8h10M8.5 4l4 4-4 4" stroke="currentColor" stroke-width="1.6"
              stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
      <div class="composer-hint">
        <span>Enter 发送 · Shift + Enter 换行</span>
        <span v-if="conversationId">多轮会话已开启 · 结果仅可读</span>
        <button v-if="turns.length" class="btn ghost sm" type="button" @click="reset">新对话</button>
      </div>
    </div>
  </div>
</template>
