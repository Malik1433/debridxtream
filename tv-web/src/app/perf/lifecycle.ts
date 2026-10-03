/**
 * W4 QA R4b: "coming back from the YouTube app closes DX Play". Killed by the TV (memory) and closed
 * by us need different fixes, and a retail Samsung shows no logs - so the app keeps its own short
 * record of what happened to it, in storage that survives being killed, shown under the frame meter.
 * A "boot" right after "hidden" with no "unload" or "exit" between them means the TV killed it.
 */
const KEY = 'dx.lifecycle'
export const LIFECYCLE_MAX = 20
type Kv = Pick<Storage, 'getItem' | 'setItem'>

export function lifecycleLines(kv: Kv): string[] {
  try { const v = JSON.parse(kv.getItem(KEY) ?? '[]'); return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [] } catch { return [] }
}

export function recordLifecycle(kv: Kv, event: string, at: Date = new Date()): void {
  const t = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}:${String(at.getSeconds()).padStart(2, '0')}`
  try { kv.setItem(KEY, JSON.stringify([...lifecycleLines(kv), `${t} ${event}`].slice(-LIFECYCLE_MAX))) } catch { /* storage full: no record */ }
}

/** Boot, and every hide / show / unload from now on. */
export function watchLifecycle(kv: Kv): void {
  recordLifecycle(kv, 'boot')
  document.addEventListener('visibilitychange', () => recordLifecycle(kv, document.hidden ? 'hidden' : 'visible'))
  window.addEventListener('pagehide', () => recordLifecycle(kv, 'unload'))
}
