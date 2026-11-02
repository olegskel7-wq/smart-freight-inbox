import { useCallback, useState } from 'react'
import { useApi } from '../../api/useApi.ts'
import { usePageData } from '../../api/usePageData.ts'
import type { Settings } from '../../types.ts'

export type Api = ReturnType<typeof useApi>

export type Msg = { kind: 'success' | 'danger' | 'warning'; text: string } | null

// The editable fields of every section. Each save returns the refreshed snapshot, which re-seeds ALL
// of them — an unsaved edit in another card is dropped, as the server's values are the truth.
export type SettingsForm = Pick<Settings,
  | 'ollamaUrlOverride' | 'draftEnabled'
  | 'autoSendEnabled' | 'autoSendThreshold' | 'autoSendTypes'
  | 'statusUpdateEnabled' | 'statusUpdateMilestones'
  | 'quoteFollowUpEnabled' | 'quoteFollowUpDelayHours'
  | 'smartRoutingEnabled' | 'escalationAssignee'
  | 'extractionPrompt' | 'draftingPrompt'>

const formFrom = (s: Settings): SettingsForm => ({
  ollamaUrlOverride: s.ollamaUrlOverride,
  draftEnabled: s.draftEnabled,
  autoSendEnabled: s.autoSendEnabled,
  autoSendThreshold: s.autoSendThreshold,
  autoSendTypes: s.autoSendTypes,
  statusUpdateEnabled: s.statusUpdateEnabled,
  statusUpdateMilestones: s.statusUpdateMilestones,
  quoteFollowUpEnabled: s.quoteFollowUpEnabled,
  quoteFollowUpDelayHours: s.quoteFollowUpDelayHours,
  smartRoutingEnabled: s.smartRoutingEnabled,
  escalationAssignee: s.escalationAssignee,
  extractionPrompt: s.extractionPrompt,
  draftingPrompt: s.draftingPrompt,
})

/** What a successful save reports: a fixed text, or a message built from the refreshed snapshot. */
export type Outcome = string | ((s: Settings) => NonNullable<Msg>)

/**
 * Runs a save call, re-seeds from its refreshed snapshot and reports the outcome in the page alert.
 * With `setFormError` a failure is shown inside that form instead (the page alert is off-screen while
 * a mailbox table row is being edited). Resolves to whether the save succeeded.
 */
export type SaveSettings = (call: (api: Api) => Promise<Settings>, outcome: Outcome,
                            setFormError?: (text: string | null) => void) => Promise<boolean>

export type DeleteAndReload = (call: (api: Api) => Promise<unknown>, ok: string) => Promise<void>

export type FormPatch = Partial<SettingsForm> | ((prev: SettingsForm) => Partial<SettingsForm>)

/** The props every editable section card takes. */
export type SectionProps = {
  settings: Settings
  form: SettingsForm
  onChange: (patch: FormPatch) => void
  save: SaveSettings
}

const errorText = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback)

// Loads the /api/settings snapshot and owns the page-level state: the snapshot, the editable form
// seeded from it, the load error and the page alert.
export function useSettings() {
  const api = useApi()

  const [form, setForm] = useState<SettingsForm | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  // Every snapshot that arrives, loaded or returned by a save, re-seeds the form.
  const load = useCallback(
    () => api.get<Settings>('/api/settings').then((s) => {
      setForm(formFrom(s))
      return s
    }),
    [api],
  )
  const { data, setData, error, reload } = usePageData(load, 'Failed to load settings')

  const applyData = (s: Settings) => {
    setData(s)
    setForm(formFrom(s))
  }

  const patchForm = useCallback(
    (patch: FormPatch) =>
      setForm((prev) => (prev ? { ...prev, ...(typeof patch === 'function' ? patch(prev) : patch) } : prev)),
    [],
  )

  const save: SaveSettings = async (call, outcome, setFormError) => {
    setMsg(null)
    setFormError?.(null)
    try {
      const s = await call(api)
      applyData(s)
      setMsg(typeof outcome === 'string' ? { kind: 'success', text: outcome } : outcome(s))
      return true
    } catch (e) {
      const text = errorText(e, 'Save failed')
      if (setFormError) setFormError(text)
      else setMsg({ kind: 'danger', text })
      return false
    }
  }

  // A delete returns no snapshot, so the page reloads it before reporting.
  const deleteAndReload: DeleteAndReload = async (call, ok) => {
    setMsg(null)
    try {
      await call(api)
      await reload()
      setMsg({ kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: errorText(e, 'Delete failed') })
    }
  }

  return {
    data, form, error, msg,
    dismissMsg: () => setMsg(null),
    patchForm, save, deleteAndReload,
  }
}

/** The usual outcome of a feature card's save: says so when the saved feature is still off. */
export const featureSaved = (what: string, enabled: boolean): NonNullable<Msg> =>
  ({ kind: 'success', text: `${what} saved${enabled ? '' : ' (feature is off)'}.` })
