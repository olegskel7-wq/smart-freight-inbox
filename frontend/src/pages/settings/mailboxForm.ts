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
