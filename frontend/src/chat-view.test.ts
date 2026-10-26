import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ChatView from './components/ChatView.vue'
import { ApiError, api } from './api'
import type { ChatResponse } from './types'

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      post: vi.fn(),
    },
  }
})

const postMock = vi.mocked(api.post)

function response(answer: string): ChatResponse {
  return {
    requestId: `req-${answer}`,
    conversationId: `conv-${answer}`,
    auditId: 1,
    answer,
    chart: null,
    table: { columns: [], rows: [], truncated: false },
    followUps: [],
    result: {
      status: 'ERROR',
      question: '',
      mode: 'AI',
      generatedSql: null,
      finalSql: null,
      explanation: null,
      confidence: 0,
      clarification: null,
      guard: { checks: [], tables: [], rowScopes: [] },
      result: { columns: [], rows: [], truncated: false, rowCount: 0 },
      usage: { attempts: 1, promptTokens: 0, completionTokens: 0, costUsd: 0, model: null },
      performance: {
        linkMs: 0, generateMs: 0, guardMs: 0, executeMs: 0, totalMs: 10,
        explainCost: null, planNodes: [],
      },
      schema: { tableCount: 0, metricCount: 0, columnCount: 0, exampleCount: 0, hits: [] },
      failure: { stage: 'execute', code: 'TEST', message: '未完成' },
      degraded: false,
      degradedReason: null,
      trace: [],
    },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function enterQuestion(wrapper: ReturnType<typeof mount>, question: string) {
  const textarea = wrapper.find('#chat-question')
  await textarea.setValue(question)
  await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })
  await flushPromises()
}

describe('ChatView request lifecycle', () => {
  it('ignores an older response after the conversation was reset', async () => {
    const first = deferred<ChatResponse>()
    const second = deferred<ChatResponse>()
    postMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const wrapper = mount(ChatView, { props: { user: 'tester', theme: 'light' } })
    await enterQuestion(wrapper, '问题一')
    expect(postMock).toHaveBeenCalledTimes(1)

    const reset = wrapper.findAll('button').find((button) => button.text().includes('新对话'))
    expect(reset).toBeTruthy()
    await reset!.trigger('click')
    await enterQuestion(wrapper, '问题二')
    expect(postMock).toHaveBeenCalledTimes(2)

    first.resolve(response('旧回答'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧回答')

    second.resolve(response('新回答'))
    await flushPromises()
    expect(wrapper.text()).toContain('新回答')
    expect(wrapper.text()).not.toContain('旧回答')
  })

  it('submits only one request while the first is in flight', async () => {
    const pending = deferred<ChatResponse>()
    postMock.mockReturnValue(pending.promise)

    const wrapper = mount(ChatView, { props: { user: 'tester', theme: 'light' } })
    const textarea = wrapper.find('#chat-question')
    await textarea.setValue('重复提交')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })
    await flushPromises()

    expect(postMock).toHaveBeenCalledTimes(1)
    pending.resolve(response('完成'))
    await flushPromises()
  })

  it('shows the rate-limit state and disables retry until the window passes', async () => {
    postMock.mockRejectedValue(
      new ApiError(429, 'RATE_LIMITED', '请求过于频繁，请稍后再试。', 'rate_limit', 30),
    )

    const wrapper = mount(ChatView, { props: { user: 'tester', theme: 'light' } })
    await enterQuestion(wrapper, '高频问题')

    expect(wrapper.text()).toContain('限流')
    const retry = wrapper.findAll('button').find((button) => button.text() === '重试')
    expect(retry).toBeTruthy()
    expect(retry!.attributes('disabled')).toBeDefined()
  })

  it('keeps the question available for editing after a failure', async () => {
    postMock.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '网络连接失败', 'network'))

    const wrapper = mount(ChatView, { props: { user: 'tester', theme: 'light' } })
    await enterQuestion(wrapper, '网络问题')

    expect(wrapper.text()).toContain('网络异常')
    const edit = wrapper.findAll('button').find((button) => button.text() === '编辑问题')
    expect(edit).toBeTruthy()
    await edit!.trigger('click')
    const textarea = wrapper.find<HTMLTextAreaElement>('#chat-question')
    expect(textarea.element.value).toBe('网络问题')
  })
})
