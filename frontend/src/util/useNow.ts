import { useEffect, useState } from 'react'

/** Current time in ms, re-rendering once a minute so relative SLA text ("Due in 1h 20m") stays true. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}
