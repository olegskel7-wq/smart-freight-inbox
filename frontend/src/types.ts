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

// Documents DTOs — mirror com.smartfreight.inbox.controller.dto.DocumentsResponse (Phase 3.2).
export interface DocTypeOption {
  value: string
  label: string
}

export interface DocumentsResponse {
  featureEnabled: boolean
  documents: DocumentRef[]
  docTypes: DocTypeOption[]
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

// Analytics DTOs — mirror com.smartfreight.inbox.controller.dto (Phase 3.2).

export interface TypeCount {
  type: string
  count: number
  pct: number
}

export interface StatusCount {
  status: string
  count: number
  pct: number
}

export interface AnalyticsSummary {
  periodDays: number
  totalReceived: number
  relevant: number
  irrelevant: number
  irrelevantPct: number
  manualSends: number
  autoSends: number
  totalSends: number
  autoSendPct: number
  avgResponseFormatted: string
  slaBreached: number
  slaBreachedPct: number
  aiDraftsSent: number
  aiDraftsAccepted: number
  aiDraftsEdited: number
  aiAcceptancePct: number
  aiAcceptanceFormatted: string
  byType: TypeCount[]
  untyped: number
  untypedPct: number
  byStatus: StatusCount[]
  volumeLabels: string[]
  volumeRelevant: number[]
  volumeJunk: number[]
  confidenceBins: number[]
  confidenceCount: number
}

export interface AnalyticsResponse {
  featureEnabled: boolean
  queryEnabled: boolean
  days: number
  allowedPeriods: number[]
  summary: AnalyticsSummary | null
}

export interface InboxAnswer {
  question: string
  answer: string
  metric: string
  periodDays: number
  matched: boolean
}

// Settings DTOs — mirror com.smartfreight.inbox.controller.dto.SettingsResponse (Phase 3.2).

export interface MailboxRow {
  id: number
  host: string
  port: number
  username: string
  sendsAs: string | null
  enabled: boolean
  running: boolean
}

// Rates DTOs — mirror com.smartfreight.inbox.controller.dto (Phase 3.2). Monetary fields are
// strings (exact BigDecimal text); sellPrice and expired are computed server-side.
export interface Rate {
  id: number
  carrier: string
  origin: string
  destination: string
  rate: string
  currency: string
  marginPercent: string
  sellPrice: string
  validUntil: string | null
  expired: boolean
}

export interface RatesResponse {
  featureEnabled: boolean
  rates: Rate[]
}

export interface RateUploadResponse {
  inserted: number
  updated: number
  total: number
  errors: string[]
  snapshot: RatesResponse
}

// Training-examples DTOs — mirror com.smartfreight.inbox.controller.dto (Phase 3.2). The text
// fields are previews abbreviated server-side; ticketType is the enum name or null (Unclassified).
export interface TrainingExample {
  id: number
  ticketType: string | null
  enquiryPreview: string
  aiDraftPreview: string
  operatorFinalPreview: string
  createdAt: string
}

export interface TrainingExamplesResponse {
  featureEnabled: boolean
  examples: TrainingExample[]
}

export interface Settings {
  cipherConfigured: boolean
  platformOllamaUrl: string
  ollamaUrlOverride: string
  smtpHost: string
  smtpPort: number
  smtpUser: string
  draftEnabled: boolean
  extractionPrompt: string
  draftingPrompt: string
  autoSendEnabled: boolean
  autoSendThreshold: string
  autoSendTypes: string[]
  allTicketTypes: string[]
  statusUpdateEnabled: boolean
  statusUpdateMilestones: string
  quoteFollowUpEnabled: boolean
  quoteFollowUpDelayHours: string
  mailboxes: MailboxRow[]
}
