export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, options: RequestInit = {}, user?: string): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (user) {
    headers.set('X-ChatBI-User', user)
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
  if (!response.ok) {
    const error = data as { code?: string; message?: string } | null
    throw new ApiError(response.status, error?.code ?? 'HTTP_ERROR',
      error?.message ?? `请求失败（${response.status}）`)
  }
  return data as T
}

export const api = {
  post<T>(path: string, body: unknown, user: string): Promise<T> {
    return request<T>(path, { method: 'POST', body: JSON.stringify(body) }, user)
  },
  get<T>(path: string, user?: string): Promise<T> {
    return request<T>(path, {}, user)
  },
  delete<T>(path: string, user: string): Promise<T> {
    return request<T>(path, { method: 'DELETE' }, user)
  },
}
