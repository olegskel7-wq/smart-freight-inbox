// Mirrors the backend DTOs in com.smartfreight.inbox.controller.dto (Phase 3.2).

export interface TicketSummary {
  id: number
  senderEmail: string | null
  subject: string | null
  ticketType: string | null
  extractedIdentifier: string | null
  status: string | null
  aiConfidence: number | null
  slaDeadlineAt: string | null
  slaBreached: boolean
  proactive: boolean
  createdAt: string
  updatedAt: string
}

export interface DocumentRef {
  id: number
  identifier: string
  docType: string | null
  filename: string
  contentType: string | null
  fileSize: number
  uploadedAt: string
}

export interface TicketDetail {
  id: number
  messageId: string | null
  senderEmail: string | null
  subject: string | null
  body: string | null
  attachmentText: string | null
  ticketType: string | null
  extractedIdentifier: string | null
  status: string | null
  aiConfidence: number | null
  slaDeadlineAt: string | null
  slaBreached: boolean
  proactive: boolean
  draftReply: string | null
  aiOriginalDraft: string | null
  requestedDocType: string | null
  attachedDocument: DocumentRef | null
  availableDocuments: DocumentRef[]
  retryCount: number | null
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const TICKET_STATUSES = [
  'RECEIVED', 'NEW', 'PROCESSING', 'DRAFT_READY', 'FAILED',
  'RETRY_LATER', 'IRRELEVANT', 'CLOSED', 'AUTO_SENT',
] as const
