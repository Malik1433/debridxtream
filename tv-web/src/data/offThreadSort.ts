/**
 * Sorting the whole catalogue OFF the main thread (W4 QA round 10: `memo:lib-sorted-movies-__all-recent`
 * blocked the Samsung for 1.1-1.3 s every time Movies opened, three rounds running). Only numbers
 * cross to the worker - one Float64Array of keys per order, transferred, not copied - and an index
 * order comes back the same way; the main thread then picks the items in that order.
 */

/**
 * Descending by `primary`, then by `secondary`, ties in the original order - exactly what the stable
 * `sort` in libraryModel gives. ES5-plain and self-contained on purpose: its source text is what the
 * worker runs.
 */
export function orderBy(primary: Float64Array, secondary: Float64Array | null): Uint32Array {
  var n = primary.length
  var idx = new Uint32Array(n)
  for (var i = 0; i < n; i++) idx[i] = i
  idx.sort(function (a, b) {
    var d = primary[b] - primary[a]
    if (d !== 0) return d
    if (secondary) { d = secondary[b] - secondary[a]; if (d !== 0) return d }
    return a - b
  })
  return idx
}

export function applyOrder<T>(items: readonly T[], idx: Uint32Array): T[] {
  const out = new Array<T>(idx.length)
  for (let i = 0; i < idx.length; i++) out[i] = items[idx[i]]
  return out
}

let worker: Worker | null | undefined
let seq = 0
const waiting = new Map<number, (r: Uint32Array | null) => void>()

function getWorker(): Worker | null {
  if (worker !== undefined) return worker
  try {
    const src = `var orderBy = ${orderBy.toString()};\nonmessage = function (e) { var r = orderBy(e.data.p, e.data.s); postMessage({ id: e.data.id, r: r }, [r.buffer]) }`
    worker = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })))
    worker.onmessage = (e: MessageEvent<{ id: number; r: Uint32Array }>) => { waiting.get(e.data.id)?.(e.data.r); waiting.delete(e.data.id) }
    worker.onerror = () => { waiting.forEach((done) => done(null)); waiting.clear(); worker = null }
  } catch { worker = null }
  return worker
}

/** The order, from the worker - or null (no worker on this TV, or it failed): the caller sorts as before. */
export function orderOffThread(primary: Float64Array, secondary: Float64Array | null, timeoutMs = 30_000): Promise<Uint32Array | null> {
  const w = getWorker()
  if (!w) return Promise.resolve(null)
  const id = ++seq
  return new Promise((resolve) => {
    const t = setTimeout(() => { waiting.delete(id); resolve(null) }, timeoutMs)
    waiting.set(id, (r) => { clearTimeout(t); resolve(r) })
    const transfer = secondary ? [primary.buffer, secondary.buffer] : [primary.buffer]
    try { w.postMessage({ id, p: primary, s: secondary }, transfer) } catch { clearTimeout(t); waiting.delete(id); resolve(null) }
  })
}
