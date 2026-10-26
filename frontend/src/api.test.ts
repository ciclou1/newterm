import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, api, clearSession, getStoredUser, getToken, isTokenExpired, storeSession,
  tokenExpiresAt,
} from './api'

function jsonResponse(
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

const user = {
  id: 1,
  username: 'lisi',
  displayName: '李四',
  role: 'ADMIN',
  roles: ['ADMIN'],
  departmentId: null,
  admin: true,
  enabled: true,
}

function tokenWithExpiry(secondsFromNow: number): string {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + secondsFromNow }))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '')
  return `header.${payload}.signature`
}

describe('api error handling', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('clears the session and emits an unauthorized event on 401', async () => {
    storeSession({ accessToken: 'valid-token', tokenType: 'Bearer', expiresIn: 3600, user })
    fetchMock.mockResolvedValue(jsonResponse(401, {
      code: 'TOKEN_EXPIRED',
      message: '登录已过期',
      requestId: 'req-1',
    }))
    const listener = vi.fn()
    window.addEventListener('chatbi:unauthorized', listener)

    await expect(api.get('/api/secret')).rejects.toMatchObject({
      kind: 'auth',
      status: 401,
      requestId: 'req-1',
    })

    expect(getToken()).toBeNull()
    expect(getStoredUser()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    const event = listener.mock.calls[0][0] as CustomEvent<{ reason: string }>
    expect(event.detail.reason).toContain('登录')
    window.removeEventListener('chatbi:unauthorized', listener)
  })

  it('does not treat a failed login as an expired session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, {
      code: 'BAD_CREDENTIALS',
      message: '用户名或口令错误',
    }))
    const listener = vi.fn()
    window.addEventListener('chatbi:unauthorized', listener)

    await expect(api.login('lisi', 'wrong')).rejects.toMatchObject({
      kind: 'auth',
      message: '用户名或口令错误',
    })
    expect(listener).not.toHaveBeenCalled()
    window.removeEventListener('chatbi:unauthorized', listener)
  })

  it('classifies quota vs rate limit and keeps Retry-After', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(429, {
      code: 'RATE_LIMITED',
      message: '请求过于频繁',
    }, { 'Retry-After': '30' }))
    fetchMock.mockResolvedValueOnce(jsonResponse(429, {
      code: 'DAILY_QUOTA_EXCEEDED',
      message: '今日配额已用完',
    }))

    const rateLimit = await api.get('/api/chat/query').catch((error: unknown) => error)
    expect(rateLimit).toBeInstanceOf(ApiError)
    expect((rateLimit as ApiError).kind).toBe('rate_limit')
    expect((rateLimit as ApiError).retryAfterSeconds).toBe(30)
    expect((rateLimit as ApiError).retryable).toBe(true)

    const quota = await api.get('/api/chat/query').catch((error: unknown) => error)
    expect((quota as ApiError).kind).toBe('quota')
    expect((quota as ApiError).retryable).toBe(false)
  })

  it('never exposes upstream 5xx messages to the user', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, {
      code: 'INTERNAL_ERROR',
      message: 'java.lang.NullPointerException at com.chatbi.Secret',
    }))

    const error = await api.get('/api/secret').catch((caught: unknown) => caught)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).kind).toBe('server')
    expect((error as ApiError).message).toBe('服务暂时不可用，请稍后重试。')
    expect((error as ApiError).message).not.toContain('NullPointerException')
  })

  it('turns a slow response into a timeout error', async () => {
    fetchMock.mockImplementation((_input: unknown, init: RequestInit | undefined) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        })
      }))

    const error = await api.get('/api/slow', { timeoutMs: 5 }).catch((caught: unknown) => caught)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).kind).toBe('timeout')
    expect((error as ApiError).retryable).toBe(true)
  })

  it('turns a transport failure into a retryable network error', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const error = await api.get('/api/data').catch((caught: unknown) => caught)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).kind).toBe('network')
    expect((error as ApiError).retryable).toBe(true)
  })

  it('rejects an already expired token locally without calling the network', async () => {
    const token = tokenWithExpiry(-60)
    storeSession({ accessToken: token, tokenType: 'Bearer', expiresIn: 60, user })
    const listener = vi.fn()
    window.addEventListener('chatbi:unauthorized', listener)

    await expect(api.get('/api/data')).rejects.toMatchObject({
      kind: 'auth',
      code: 'TOKEN_EXPIRED',
    })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(getToken()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    window.removeEventListener('chatbi:unauthorized', listener)
  })

  it('posts the change-password payload to the auth endpoint', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, null))
    await api.changePassword('old-secret', 'new-secret')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/auth/password')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ oldPassword: 'old-secret', newPassword: 'new-secret' }))
  })

  it('clears session storage helpers idempotently', () => {
    storeSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 60, user })
    clearSession()
    expect(getToken()).toBeNull()
    expect(getStoredUser()).toBeNull()
  })

  it('decodes exp without trusting malformed tokens', () => {
    const token = tokenWithExpiry(120)
    expect(tokenExpiresAt(token)).toBeGreaterThan(Date.now())
    expect(tokenExpiresAt('not-a-jwt')).toBeNull()
    expect(isTokenExpired(null)).toBe(false)
    expect(isTokenExpired(tokenWithExpiry(-1), 0)).toBe(true)
  })
})
