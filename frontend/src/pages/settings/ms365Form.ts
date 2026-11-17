import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../../api/useApi.ts'
import type { MailboxRow, Ms365Status } from '../../types.ts'

// Form shape and data hook for Microsoft 365 mailboxes (V38) — the components are in Ms365Mailboxes.tsx.

export type Ms365Form = { directoryId: string; username: string; sendReplies: boolean; fromAddress: string }

export const toMs365Form = (m: MailboxRow): Ms365Form => ({
  directoryId: m.msDirectoryId ?? '',
  username: m.username,
  sendReplies: m.smtpHost != null,
  fromAddress: m.fromAddress ?? '',
})

export const toMs365Request = (f: Ms365Form) => ({
  directoryId: f.directoryId,
  username: f.username,
  sendReplies: f.sendReplies,
  fromAddress: f.sendReplies && f.fromAddress ? f.fromAddress : null,
})

/** The server's Microsoft 365 status: whether the Entra app is configured, and this tenant's organisations. */
export function useMs365() {
  const api = useApi()
  const [status, setStatus] = useState<Ms365Status | null>(null)
  const reload = useCallback(() => {
    api.get<Ms365Status>('/api/settings/ms365').then(setStatus).catch(() => setStatus(null))
  }, [api])
  useEffect(reload, [reload])
  return { status, reload }
}
