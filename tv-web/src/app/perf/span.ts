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

/**
 * Every key press as two named measures (round 9): `dx:key` - its handlers, focus engine and React
 * included (to the next task) - and `dx:key-paint` - to the frame after it. Always on and cheap, so
 * the QA probe reads per-press cost without the on-screen readout running (which costs frames itself).
 */
export function watchKeys(): void {
  let n = 0
  window.addEventListener('keydown', () => {
    // Bounded: a long evening of zapping must not grow the measure buffer without end.
    if (++n % 500 === 0) { try { performance.clearMeasures('dx:key'); performance.clearMeasures('dx:key-paint') } catch { /* none */ } }
    const t = performance.now()
    setTimeout(() => note('key', t), 0)
    requestAnimationFrame(() => requestAnimationFrame(() => note('key-paint', t)))
  }, true)
}
