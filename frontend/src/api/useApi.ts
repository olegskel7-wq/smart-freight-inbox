import { useCallback, useEffect, useMemo, useRef } from 'react'
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

/** The backend answers errors as {error, message} (GlobalExceptionHandler) — show the message, not raw JSON. */
async function errorText(res: Response): Promise<string> {
  const text = await res.text().catch(() => '')
  try {
    const message = (JSON.parse(text) as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  } catch {
    // Not JSON (e.g. a proxy error page) — fall through to the raw text.
  }
  return text || res.statusText
}

/**
 * Thin JSON client bound to the current Keycloak access token. Every call sends
 * `Authorization: Bearer <token>`; a 401 (expired/invalid token) triggers a fresh sign-in
 * redirect. In dev `/api` is proxied to the backend by Vite; in prod it is same-origin.
 *
 * The client object is stable for the page's lifetime: the auth context (and its token) is read
 * through a ref at call time. A silent token renewal hands out a new context value every few
 * minutes; were the client rebuilt with it, every page would re-run its load and re-seed what the
 * operator is typing (the reply draft, the settings form).
 */
export function useApi() {
  const auth = useAuth()
  const authRef = useRef(auth)
  useEffect(() => {
    authRef.current = auth
  }, [auth])

  // Sends with the current token; a 401 starts a fresh sign-in, any other failure throws the
  // server's message.
  const send = useCallback(async (path: string, init?: RequestInit): Promise<Response> => {
    const headers = new Headers(init?.headers)
    const token = authRef.current.user?.access_token
    if (token) headers.set('Authorization', `Bearer ${token}`)

    const res = await fetch(path, { ...init, headers })

    if (res.status === 401) {
      void authRef.current.signinRedirect()
      throw new ApiError(401, 'Unauthorized')
    }
    if (!res.ok) throw new ApiError(res.status, await errorText(res))
    return res
  }, [])

  const request = useCallback(
    async <T>(path: string, init?: RequestInit): Promise<T> => {
      const headers = new Headers(init?.headers)
      // FormData (file upload) must keep the browser-set multipart Content-Type + boundary.
      if (init?.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

      const res = await send(path, { ...init, headers })
      if (res.status === 204) return undefined as T
      const ct = res.headers.get('content-type') ?? ''
      return (ct.includes('application/json') ? res.json() : res.text()) as Promise<T>
    },
    [send],
  )

  return useMemo(
    () => ({
      get: <T>(path: string) => request<T>(path),
      post: <T>(path: string, body?: unknown) =>
        request<T>(path, {
          method: 'POST',
          body: body != null ? JSON.stringify(body) : undefined,
        }),
      put: <T>(path: string, body?: unknown) =>
        request<T>(path, {
          method: 'PUT',
          body: body != null ? JSON.stringify(body) : undefined,
        }),
      upload: <T>(path: string, form: FormData) =>
        request<T>(path, { method: 'POST', body: form }),
      del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
      // Binary fetch for file downloads — a plain <a href> can't carry the bearer token, so we pull
      // the bytes as a blob and let the caller trigger the save.
      download: async (path: string): Promise<{ blob: Blob; filename: string }> => {
        const res = await send(path)
        const match = /filename="?([^"]+)"?/.exec(res.headers.get('content-disposition') ?? '')
        return { blob: await res.blob(), filename: match ? match[1] : 'document' }
      },
    }),
    [request, send],
  )
}
