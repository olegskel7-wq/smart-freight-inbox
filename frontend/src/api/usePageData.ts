import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage } from '../util/errors.ts'

type Load<T> = () => Promise<T>

/**
 * A page's data, fetched when the page mounts and again whenever `load` changes (its useCallback
 * deps — e.g. a filter in the URL). The last data stays on screen while a new load is in flight.
 * State is set only from the promise callbacks, never synchronously inside the effect
 * (react-hooks/set-state-in-effect).
 *
 * `error` and `loading` belong to the current `load`, so a new `load` starts out loading and with
 * no error without an extra set-state. `setError` reports a page-level error of the page's own (a
 * failed action) in the same slot; `reload` re-runs the load from a handler or a live update,
 * clearing the error first, and never throws.
 */
export function usePageData<T>(load: Load<T>, failMessage: string) {
  const [data, setData] = useState<T | null>(null)
  // Each records the load it belongs to; one recorded for an older load reads as "none".
  const [failure, setFailure] = useState<{ load: Load<T>; message: string } | null>(null)
  const [settled, setSettled] = useState<Load<T> | null>(null)
  // The newest load started; an older one that answers after it is dropped, so a quick filter
  // change can't land the previous filter's data on screen.
  const latest = useRef(load)

  const run = useCallback(
    (current: Load<T>) => {
      const settle = (apply: () => void) => {
        if (latest.current !== current) return
        apply()
        setSettled(() => current)
      }
      // A load that throws before returning its promise still ends up as the page error.
      return new Promise<T>((resolve) => resolve(current())).then(
        (value) => settle(() => setData(value)),
        (e: unknown) => settle(() => setFailure({ load: current, message: errorMessage(e, failMessage) })),
      )
    },
    [failMessage],
  )

  useEffect(() => {
    latest.current = load
    void run(load)
  }, [run, load])

  const reload = useCallback(async () => {
    setFailure(null)
    setSettled(null)
    await run(load)
  }, [run, load])

  const setError = useCallback(
    (message: string | null) => setFailure(message == null ? null : { load, message }),
    [load],
  )

  return {
    data,
    setData,
    error: failure?.load === load ? failure.message : null,
    setError,
    loading: settled !== load,
    reload,
  }
}
