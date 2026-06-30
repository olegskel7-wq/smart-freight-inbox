import { useEffect, useState } from 'react'
import { useApi } from './useApi.ts'
import type { User } from '../types.ts'

/**
 * Loads the tenant's operator list for assignee pickers (Phase 3.3 / 3.10). Best-effort: the
 * `/api/users` endpoint 403s for non-admins, so any failure falls back to an empty list rather than
 * surfacing an error. Pass `enabled=false` to skip the fetch entirely (e.g. a non-admin who can't read it).
 */
export function useOperators(enabled = true): User[] {
  const api = useApi()
  const [operators, setOperators] = useState<User[]>([])

  useEffect(() => {
    if (!enabled) return
    void api.get<User[]>('/api/users').then(setOperators).catch(() => setOperators([]))
  }, [api, enabled])

  return operators
}
