// Mirrors the backend DTOs in com.smartfreight.inbox.controller.dto (Phase 3.2).

export interface TicketSummary {
  id: number
  senderEmail: string | null
  subject: string | null
  ticketType: string | null
  urgency: string | null
  sentimentNegative: boolean
  extractedIdentifier: string | null
  status: string | null
  aiConfidence: number | null
  slaDeadlineAt: string | null
  slaBreached: boolean
  proactive: boolean
  assigneeUserId: string | null
  assigneeEmail: string | null
  createdAt: string
  updatedAt: string
}

// Summary strip on /tickets — mirrors com.smartfreight.inbox.controller.dto.TicketStatsDto.
export interface TicketStats {
  open: number
  awaitingReview: number
  slaBreached: number
  autoSentToday: number
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
  urgency: string | null
  sentimentNegative: boolean
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
  assigneeUserId: string | null
  assigneeEmail: string | null
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
  quotesSent: number
  quotesConverted: number
  quoteConversionPct: number
  quoteConversionFormatted: string
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
  // Gate B4: last IMAP login probe — null until the first probe (every ~5 min). "Running" only
  // means the adapter is registered; this says whether we can actually log in.
  loginOk: boolean | null
  lastLoginOkAt: string | null
  loginError: string | null
  // Raw SMTP config — seeds the edit form (Gate B15). The password is never returned.
  smtpHost: string | null
  smtpPort: number | null
  fromAddress: string | null
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

// Users DTO — mirrors com.smartfreight.inbox.controller.dto.UserDto (Phase 3.2). role may be null
// (the page falls back to "OPERATOR"); id is the Keycloak user UUID.
export interface User {
  id: string
  email: string
  role: string | null
}

// Tenant DTO — mirrors com.smartfreight.inbox.controller.dto.TenantSummaryDto (Phase 3.2).
// Platform admin (SUPER_ADMIN) view: one client organisation + its Keycloak operator count.
export interface Tenant {
  id: number
  name: string
  userCount: number
  createdAt: string
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

// Knowledge-base DTOs — mirror com.smartfreight.inbox.controller.dto (Phase 3.9). Full content is
// shipped (not a preview) because the page edits documents inline.
export interface KnowledgeDoc {
  id: number
  title: string
  content: string
  source: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeResponse {
  featureEnabled: boolean
  documents: KnowledgeDoc[]
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
  smartRoutingEnabled: boolean
  escalationAssignee: string
  mailboxes: MailboxRow[]
}

// Webhook DTOs — mirror com.smartfreight.inbox.controller.dto (Phase 3.5). The signing secret is
// never returned except once in CreateWebhookResponse at creation time.
export interface WebhookSubscription {
  id: number
  url: string
  eventTypes: string[]
  description: string | null
  enabled: boolean
  disabledReason: string | null
  createdAt: string
  updatedAt: string
}

export interface WebhooksResponse {
  subscriptions: WebhookSubscription[]
  availableEventTypes: string[]
  secretConfigured: boolean
}

export interface CreateWebhookResponse {
  secret: string
  snapshot: WebhooksResponse
}

export interface WebhookDelivery {
  id: number
  eventType: string
  status: string
  attempts: number
  responseStatus: number | null
  lastError: string | null
  createdAt: string
  nextAttemptAt: string
  deliveredAt: string | null
}
