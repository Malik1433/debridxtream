/**
 * Named timings the TV can report (W4 QA round 7). Opening Movies blocked the TV for 11.4 s across 97
 * long tasks, and long tasks carry no names; these do. Each is a `performance.measure` named
 * `dx:<what>` - readable by the QA probe (`performance.getEntriesByType('measure')`) or any inspector -
 * and costs two clock reads.
 */
/** `memo:lib-sorted-movies-__all-recent` stays; a trailing `:<count/ids>` or `|mode` goes. */
export const label = (name: string) => name.replace(/^([^:|]+:[^:|]*).*$/, '$1')

function note(name: string, start: number): void {
  try { performance.measure(`dx:${label(name)}`, { start, duration: performance.now() - start } as PerformanceMeasureOptions) } catch { /* older engine: no named measures */ }
}

export function span<T>(name: string, fn: () => T): T {
  const t = performance.now()
  try { return fn() } finally { note(name, t) }
}

export function spanAsync<T>(name: string, p: () => Promise<T>): Promise<T> {
  const t = performance.now()
  return p().finally(() => note(name, t))
}
