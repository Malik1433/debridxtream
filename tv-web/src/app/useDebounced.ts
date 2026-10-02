import { useCallback, useEffect, useRef } from 'react'

/**
 * Focus-driven browsing with a debounce - the 10-foot standard (Netflix, YouTube TV, Leanback;
 * W4 QA N4): the content follows the focused category, but only once focus RESTS on it, so a fast
 * ▲▼ through twenty categories builds none of them. `flush` applies the pending one at once
 * (OK pressed). A newer call replaces an older one - the older work never runs.
 */
export const CATEGORY_DEBOUNCE_MS = 250

export function useDebounced<A>(fn: (a: A) => void, ms = CATEGORY_DEBOUNCE_MS): { call: (a: A) => void; flush: () => void } {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pending = useRef<{ a: A } | null>(null)
  const fnRef = useRef(fn)
  fnRef.current = fn
  const flush = useCallback(() => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null }
    const p = pending.current
    pending.current = null
    if (p) fnRef.current(p.a)
  }, [])
  const call = useCallback((a: A) => {
    pending.current = { a }
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(flush, ms)
  }, [flush, ms])
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])
  return { call, flush }
}
