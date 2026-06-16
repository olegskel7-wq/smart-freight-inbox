import { useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { useAuth } from 'react-oidc-context'

const RECONNECT_MS = 5000
// A single pipeline run touches a ticket several times (PROCESSING → DRAFT_READY …); coalesce the
// resulting burst of events into one re-fetch.
const DEBOUNCE_MS = 400

/**
 * Subscribes to this tenant's live ticket topic over STOMP and invokes `onEvent` (debounced) on each
 * push (Phase 3.2). Returns whether the stream is currently connected, for a UI indicator.
 *
 * The browser WebSocket handshake cannot carry an Authorization header, so the Keycloak access token
 * rides on the STOMP CONNECT frame instead; the backend validates it and only lets the client join
 * `/topic/tickets/{tenantId}` for its own tenant. The token is read fresh in `beforeConnect` from a
 * ref, so a silent token renewal does not churn the connection — only a tenant change reconnects.
 */
export function useTicketStream(onEvent: () => void): boolean {
  const auth = useAuth()
  const token = auth.user?.access_token
  const tenantId = (auth.user?.profile as { tenant_id?: number | string } | undefined)?.tenant_id

  const [connected, setConnected] = useState(false)

  // Keep the latest token/callback without re-subscribing each time they change.
  const tokenRef = useRef(token)
  const onEventRef = useRef(onEvent)
  useEffect(() => {
    tokenRef.current = token
  }, [token])
  useEffect(() => {
    onEventRef.current = onEvent
  }, [onEvent])

  useEffect(() => {
    if (tenantId == null) return

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    let debounce: ReturnType<typeof setTimeout> | undefined

    const client = new Client({
      brokerURL: `${proto}://${window.location.host}/ws`,
      reconnectDelay: RECONNECT_MS,
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${tokenRef.current ?? ''}` }
      },
      onConnect: () => {
        setConnected(true)
        client.subscribe(`/topic/tickets/${tenantId}`, () => {
          clearTimeout(debounce)
          debounce = setTimeout(() => onEventRef.current(), DEBOUNCE_MS)
        })
      },
      onWebSocketClose: () => setConnected(false),
      onDisconnect: () => setConnected(false),
    })

    client.activate()
    return () => {
      clearTimeout(debounce)
      void client.deactivate()
      setConnected(false)
    }
  }, [tenantId])

  return connected
}
