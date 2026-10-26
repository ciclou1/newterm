export type ApiErrorKind =
  | 'auth'
  | 'forbidden'
  | 'rate_limit'
  | 'quota'
  | 'timeout'
  | 'network'
  | 'validation'
  | 'server'
  | 'unknown'

export class ApiError extends Error {
  status: number
  code: string
  kind: ApiErrorKind
  retryAfterSeconds: number | null
  requestId: string | null

  constructor(
    status: number,
    code: string,
    message: string,
    kind: ApiErrorKind = 'unknown',
    retryAfterSeconds: number | null = null,
    requestId: string | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.kind = kind
    this.retryAfterSeconds = retryAfterSeconds
    this.requestId = requestId
  }

  /** Whether repeating the same request may plausibly succeed. */
  get retryable(): boolean {
    return this.kind === 'network'
      || this.kind === 'timeout'
      || this.kind === 'server'
      || this.kind === 'rate_limit'
  }
}

export interface AuthUser {
  id: number
  username: string
  displayName: string
  role: string
  roles: string[]
  departmentId: number | null
  admin: boolean
  enabled: boolean
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

const TOKEN_KEY = 'chatbi.token'
const USER_KEY = 'chatbi.user'
const DEFAULT_TIMEOUT_MS = 30_000

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isAuthUser(parsed)) {
      localStorage.removeItem(USER_KEY)
      return null
    }
    return parsed
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

function isAuthUser(value: unknown): value is AuthUser {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<AuthUser>
  return typeof candidate.username === 'string'
    && typeof candidate.displayName === 'string'
    && Array.isArray(candidate.roles)
    && typeof candidate.admin === 'boolean'
}

export function storeSession(login: LoginResponse): void {
  localStorage.setItem(TOKEN_KEY, login.accessToken)
  localStorage.setItem(USER_KEY, JSON.stringify(login.user))
}

export function storeUser(user: AuthUser): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * Decode the JWT payload without trusting it. We only use `exp` as a local
 * optimisation: the server still verifies the signature on every request.
 */
export function tokenExpiresAt(token: string | null): number | null {
  if (!token) return null
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const normalized = parts[1].replaceAll('-', '+').replaceAll('_', '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    const payload: unknown = JSON.parse(atob(padded))
    if (!payload || typeof payload !== 'object') return null
    const exp = (payload as { exp?: unknown }).exp
    return typeof exp === 'number' && Number.isFinite(exp) ? exp * 1000 : null
  } catch {
    return null
  }
}

export function isTokenExpired(token: string | null, skewSeconds = 15): boolean {
  const expiresAt = tokenExpiresAt(token)
  if (expiresAt === null) return false
  return expiresAt <= Date.now() + skewSeconds * 1000
}

/**
 * Tell the shell that the session can no longer be used. The event carries a
 * user-facing reason only — never a server stack trace or upstream message.
 */
export function notifyUnauthorized(reason: string): void {
  window.dispatchEvent(new CustomEvent('chatbi:unauthorized', { detail: { reason } }))
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.kind === 'server') return '服务暂时不可用，请稍后重试。'
    return error.message
  }
  return '发生未知错误，请稍后重试。'
}

export interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
  timeoutMs?: number
  authenticated?: boolean
}

function parseRetryAfter(response: Response): number | null {
  const header = response.headers.get('Retry-After')
  if (!header) return null
  const seconds = Number(header)
  return Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : null
}

function errorKind(status: number, code: string): ApiErrorKind {
  if (status === 401) return 'auth'
  if (status === 429) {
    return code.includes('QUOTA') ? 'quota' : 'rate_limit'
  }
  if (status === 403) return 'forbidden'
  if (status === 400 || status === 422) return 'validation'
  if (status >= 500) return 'server'
  return 'unknown'
}

function friendlyMessage(status: number, code: string, serverMessage: string | null): string {
  if (status >= 500) return '服务暂时不可用，请稍后重试。'
  if (serverMessage && serverMessage.trim()) return serverMessage.trim()
  if (status === 401) return '登录已失效，请重新登录。'
  if (status === 403) return code === 'USER_DISABLED' ? '账号已禁用，请联系管理员。' : '没有权限执行该操作。'
  if (status === 429) return '请求过于频繁，请稍后再试。'
  return `请求失败（${status}）`
}

function abortError(): DOMException {
  return new DOMException('Aborted', 'AbortError')
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    signal,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    authenticated = true,
  } = options

  const controller = new AbortController()
  let timedOut = false
  const onExternalAbort = () => controller.abort()
  if (signal?.aborted) throw abortError()
  signal?.addEventListener('abort', onExternalAbort, { once: true })
  const timer = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  const headers = new Headers()
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  const token = getToken()
  if (authenticated && token) {
    if (isTokenExpired(token)) {
      clearSession()
      notifyUnauthorized('登录已过期，请重新登录。')
      window.clearTimeout(timer)
      signal?.removeEventListener('abort', onExternalAbort)
      throw new ApiError(401, 'TOKEN_EXPIRED', '登录已过期，请重新登录。', 'auth')
    }
    headers.set('Authorization', `Bearer ${token}`)
  }

  try {
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    const text = await response.text()
    let data: unknown = null
    if (text) {
      try {
        data = JSON.parse(text)
      } catch {
        data = null
      }
    }

    if (!response.ok) {
      const errorBody = data as { code?: string; message?: string; requestId?: string } | null
      const code = errorBody?.code ?? 'HTTP_ERROR'
      const kind = errorKind(response.status, code)
      // A failed login is not a lost session: never clear an existing token or
      // tell the shell to bounce to the login screen for an unauthenticated call.
      if (authenticated && (response.status === 401 || errorBody?.code === 'USER_DISABLED')) {
        clearSession()
        notifyUnauthorized(friendlyMessage(response.status, code, errorBody?.message ?? null))
      }
      throw new ApiError(
        response.status,
        code,
        friendlyMessage(response.status, code, errorBody?.message ?? null),
        kind,
        parseRetryAfter(response),
        errorBody?.requestId ?? null,
      )
    }

    return data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (isAbortError(error)) {
      if (timedOut) {
        throw new ApiError(0, 'TIMEOUT', '请求超时，请检查网络后重试。', 'timeout')
      }
      throw error
    }
    throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试。', 'network')
  } finally {
    window.clearTimeout(timer)
    signal?.removeEventListener('abort', onExternalAbort)
  }
}

export const api = {
  login(username: string, password: string): Promise<LoginResponse> {
    return request<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: { username, password },
      authenticated: false,
    })
  },
  me(signal?: AbortSignal): Promise<AuthUser> {
    return request<AuthUser>('/api/auth/me', { signal })
  },
  async changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await request<null>('/api/auth/password', {
      method: 'POST',
      body: { oldPassword, newPassword },
    })
  },
  post<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return request<T>(path, { ...options, method: 'POST', body })
  },
  get<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return request<T>(path, { ...options, method: 'GET' })
  },
  put<T>(path: string, body: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return request<T>(path, { ...options, method: 'PUT', body })
  },
  delete<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return request<T>(path, { ...options, method: 'DELETE' })
  },
}
