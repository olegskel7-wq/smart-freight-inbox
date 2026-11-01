import { useState } from 'react'
import type { MailboxRow } from '../../types.ts'

// The add and edit mailbox forms share one shape; ports are strings while typed.
export type MailboxForm = {
  host: string
  port: string
  username: string
  password: string
  smtpHost: string
  smtpPort: string
  fromAddress: string
}
export type MailboxEdit = MailboxForm & { id: number }

export const EMPTY_MAILBOX: MailboxForm = {
  host: '', port: '993', username: '', password: '', smtpHost: '', smtpPort: '', fromAddress: '',
}

export const toEdit = (m: MailboxRow): MailboxEdit => ({
  id: m.id,
  host: m.host,
  port: String(m.port),
  username: m.username,
  password: '',
  smtpHost: m.smtpHost ?? '',
  smtpPort: m.smtpPort != null ? String(m.smtpPort) : '',
  fromAddress: m.fromAddress ?? '',
})

// Blank optionals go as null so the server applies its defaults (993 / 587, inbound-only, from = username).
export const toRequest = (f: MailboxForm) => ({
  host: f.host,
  port: f.port ? Number(f.port) : null,
  username: f.username,
  password: f.password,
  smtpHost: f.smtpHost || null,
  smtpPort: f.smtpPort ? Number(f.smtpPort) : null,
  fromAddress: f.fromAddress || null,
})

// The new-mailbox form, and the edit-in-place form for one mailbox at a time (null = closed), each with
// its last save error. Held by the page, not the card, so typed values survive the card unmounting
// while the page shows a load error.
export function useMailboxForms() {
  const [newMailbox, setNewMailbox] = useState<MailboxForm>(EMPTY_MAILBOX)
  const [editing, setEditing] = useState<MailboxEdit | null>(null)
  const [addError, setAddError] = useState<string | null>(null)
  const [editError, setEditError] = useState<string | null>(null)
  return { newMailbox, setNewMailbox, editing, setEditing, addError, setAddError, editError, setEditError }
}

export type MailboxForms = ReturnType<typeof useMailboxForms>
