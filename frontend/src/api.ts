export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
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

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
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

function onUnauthorized(path: string): void {
  if (path === '/api/auth/login') return
  clearSession()
  window.dispatchEvent(new CustomEvent('chatbi:unauthorized'))
}

async function request<T>(path: string, options: RequestInit = {}, authenticated = true): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  const token = getToken()
  if (authenticated && token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(path, { ...options, headers })
  const text = await response.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }
  if (response.status === 401) {
    onUnauthorized(path)
  }
  if (!response.ok) {
    const error = data as { code?: string; message?: string } | null
    throw new ApiError(response.status, error?.code ?? 'HTTP_ERROR',
      error?.message ?? `请求失败（${response.status}）`)
  }
  return data as T
}

export const api = {
  login(username: string, password: string): Promise<LoginResponse> {
    return request<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }, false)
  },
  me(): Promise<AuthUser> {
    return request<AuthUser>('/api/auth/me')
  },
  post<T>(path: string, body: unknown): Promise<T> {
    return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
  },
  get<T>(path: string): Promise<T> {
    return request<T>(path, {})
  },
  delete<T>(path: string): Promise<T> {
    return request<T>(path, { method: 'DELETE' })
  },
}
