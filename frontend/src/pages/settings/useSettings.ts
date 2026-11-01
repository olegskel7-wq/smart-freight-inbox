import { useCallback, useEffect, useState } from 'react'
import { useApi } from '../../api/useApi.ts'
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

export type SaveSettings = (call: (api: Api) => Promise<Settings>, outcome: Outcome) => Promise<boolean>

/** Like SaveSettings, but a failure goes to `setError` (shown inside a form) instead of the page alert. */
export type SaveInForm = (call: (api: Api) => Promise<Settings>, ok: string,
                          setError: (text: string | null) => void) => Promise<boolean>

export type DeleteAndReload = (call: (api: Api) => Promise<unknown>, ok: string) => Promise<void>

/** The props every editable section card takes. */
export type SectionProps = {
  settings: Settings
  form: SettingsForm
  onChange: (patch: Partial<SettingsForm>) => void
  save: SaveSettings
}

const errorText = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback)

// Loads the /api/settings snapshot and owns the page-level state: the snapshot, the editable form
// seeded from it, the load error and the page alert. The save helpers resolve to whether the call
// succeeded, so a form can reset or close only then.
export function useSettings() {
  const api = useApi()

  const [data, setData] = useState<Settings | null>(null)
  const [form, setForm] = useState<SettingsForm | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<Msg>(null)

  const applyData = useCallback((s: Settings) => {
    setData(s)
    setForm(formFrom(s))
  }, [])

  const load = useCallback(async () => {
    setError(null)
    try {
      applyData(await api.get<Settings>('/api/settings'))
    } catch (e) {
      setError(errorText(e, 'Failed to load settings'))
    }
  }, [api, applyData])

  useEffect(() => {
    void load()
  }, [load])

  const patchForm = useCallback(
    (patch: Partial<SettingsForm>) => setForm((prev) => (prev ? { ...prev, ...patch } : prev)),
    [],
  )

  const save: SaveSettings = async (call, outcome) => {
    setMsg(null)
    try {
      const s = await call(api)
      applyData(s)
      setMsg(typeof outcome === 'string' ? { kind: 'success', text: outcome } : outcome(s))
      return true
    } catch (e) {
      setMsg({ kind: 'danger', text: errorText(e, 'Save failed') })
      return false
    }
  }

  const saveInForm: SaveInForm = async (call, ok, setFormError) => {
    setMsg(null)
    setFormError(null)
    try {
      applyData(await call(api))
      setMsg({ kind: 'success', text: ok })
      return true
    } catch (e) {
      setFormError(errorText(e, 'Save failed'))
      return false
    }
  }

  // A delete returns no snapshot, so the page reloads it before reporting.
  const deleteAndReload: DeleteAndReload = async (call, ok) => {
    setMsg(null)
    try {
      await call(api)
      await load()
      setMsg({ kind: 'success', text: ok })
    } catch (e) {
      setMsg({ kind: 'danger', text: errorText(e, 'Delete failed') })
    }
  }

  return {
    data, form, error, msg,
    dismissMsg: () => setMsg(null),
    patchForm, save, saveInForm, deleteAndReload,
  }
}

/** The usual outcome of a feature card's save: says so when the saved feature is still off. */
export const featureSaved = (what: string, enabled: (s: Settings) => boolean) =>
  (s: Settings): NonNullable<Msg> => ({ kind: 'success', text: `${what} saved${enabled(s) ? '' : ' (feature is off)'}.` })
