import { useCallback, useMemo } from 'react'
import { useAuth } from 'react-oidc-context'

/** Error carrying the HTTP status so callers can branch (e.g. 403 vs 500). */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/**
 * Thin JSON client bound to the current Keycloak access token. Every call sends
 * `Authorization: Bearer <token>`; a 401 (expired/invalid token) triggers a fresh sign-in
 * redirect. In dev `/api` is proxied to the backend by Vite; in prod it is same-origin.
 */
export function useApi() {
  const auth = useAuth()
  const token = auth.user?.access_token

  const request = useCallback(
    async <T>(path: string, init?: RequestInit): Promise<T> => {
      const headers = new Headers(init?.headers)
      if (token) headers.set('Authorization', `Bearer ${token}`)
      // FormData (file upload) must keep the browser-set multipart Content-Type + boundary.
      if (init?.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

      const res = await fetch(path, { ...init, headers })

      if (res.status === 401) {
        void auth.signinRedirect()
        throw new ApiError(401, 'Unauthorized')
      }
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new ApiError(res.status, text || res.statusText)
      }
      if (res.status === 204) return undefined as T
      const ct = res.headers.get('content-type') ?? ''
      return (ct.includes('application/json') ? res.json() : res.text()) as Promise<T>
    },
    [token, auth],
  )

  return useMemo(
    () => ({
      get: <T>(path: string) => request<T>(path),
      post: <T>(path: string, body?: unknown) =>
        request<T>(path, {
          method: 'POST',
          body: body != null ? JSON.stringify(body) : undefined,
        }),
      upload: <T>(path: string, form: FormData) =>
        request<T>(path, { method: 'POST', body: form }),
      del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
    }),
    [request],
  )
}
