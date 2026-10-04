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

/** A - Z, as sortItems does it (`localeCompare`, ties in the original order). ES5-plain: the worker runs its source. */
export function orderByName(names: string[]): Uint32Array {
  var n = names.length
  var idx = new Uint32Array(n)
  for (var i = 0; i < n; i++) idx[i] = i
  idx.sort(function (a, b) { var c = names[a].localeCompare(names[b]); return c !== 0 ? c : a - b })
  return idx
}

export function applyOrder<T>(items: readonly T[], idx: Uint32Array): T[] {
  const out = new Array<T>(idx.length)
  for (let i = 0; i < idx.length; i++) out[i] = items[idx[i]]
  return out
}

/** Letters and digits only - the cheap first pass of title matching (data/titleMatch). ES5-plain: the worker runs its source. */
export function stripName(s: string): string { return s.toLowerCase().replace(/[^a-z0-9]/g, '') }

/** Indexes of the names that contain any of the keys - the worker's half of SIMILAR MOVIES. */
export function namesContaining(names: string[], keys: string[]): Uint32Array {
  var out: number[] = []
  for (var i = 0; i < names.length; i++) {
    for (var k = 0; k < keys.length; k++) { if (names[i].indexOf(keys[k]) !== -1) { out.push(i); break } }
  }
  return new Uint32Array(out)
}

let worker: Worker | null | undefined
let seq = 0
const waiting = new Map<number, (r: Uint32Array | null) => void>()

function getWorker(): Worker | null {
  if (worker !== undefined) return worker
  try {
    const src = [
      `var orderBy = ${orderBy.toString()};`,
      `var stripName = ${stripName.toString()};`,
      `var orderByName = ${orderByName.toString()};`,
      `var namesContaining = ${namesContaining.toString()};`,
      'var names = {};',
      'onmessage = function (e) { var d = e.data, r = null;',
      "  if (d.t === 'sort') r = orderBy(d.p, d.s);",
      "  else if (d.t === 'sortNames') r = orderByName(d.names);",
      "  else if (d.t === 'names') { var a = d.names; for (var i = 0; i < a.length; i++) a[i] = stripName(a[i]); names[d.set] = a; r = new Uint32Array([a.length]); }",
      "  else if (d.t === 'find') r = names[d.set] ? namesContaining(names[d.set], d.keys) : null;",
      '  postMessage({ id: d.id, r: r }, r ? [r.buffer] : []) }',
    ].join('\n')
    worker = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })))
    worker.onmessage = (e: MessageEvent<{ id: number; r: Uint32Array | null }>) => { waiting.get(e.data.id)?.(e.data.r); waiting.delete(e.data.id) }
    worker.onerror = () => { waiting.forEach((done) => done(null)); waiting.clear(); worker = null; namesHeld.clear() }
  } catch { worker = null }
  return worker
}

function ask(msg: Record<string, unknown>, transfer: Transferable[], timeoutMs: number): Promise<Uint32Array | null> {
  const w = getWorker()
  if (!w) return Promise.resolve(null)
  const id = ++seq
  return new Promise((resolve) => {
    const t = setTimeout(() => { waiting.delete(id); resolve(null) }, timeoutMs)
    waiting.set(id, (r) => { clearTimeout(t); resolve(r) })
    try { w.postMessage({ ...msg, id }, transfer) } catch { clearTimeout(t); waiting.delete(id); resolve(null) }
  })
}

/** The order, from the worker - or null (no worker on this TV, or it failed): the caller sorts as before. */
export function orderOffThread(primary: Float64Array, secondary: Float64Array | null, timeoutMs = 30_000): Promise<Uint32Array | null> {
  return ask({ t: 'sort', p: primary, s: secondary }, secondary ? [primary.buffer, secondary.buffer] : [primary.buffer], timeoutMs)
}

/** A - Z off the main thread (round 11: the sort chips cost 1.1-3.5 s on the Samsung). Null = sort as before. */
export function orderNamesOffThread(names: string[], timeoutMs = 60_000): Promise<Uint32Array | null> {
  return ask({ t: 'sortNames', names }, [], timeoutMs)
}

/** Which name lists the worker holds, by the list they were made from (a new library = a new list). */
const namesHeld = new Map<string, readonly unknown[]>()

/** Hand the worker a catalogue's names once, in the background; it keeps them stripped for `findNames`. */
export async function holdNames(set: string, items: ReadonlyArray<{ name: string }>): Promise<boolean> {
  if (namesHeld.get(set) === items) return true
  const done = await ask({ t: 'names', set, names: items.map((x) => x.name) }, [], 60_000)
  if (done && done[0] === items.length) namesHeld.set(set, items)
  return namesHeld.get(set) === items
}

/** Indexes into `items` whose name contains a key - null when the worker does not hold this list (caller matches as before). */
export function findNames(set: string, items: readonly unknown[], keys: string[]): Promise<Uint32Array | null> {
  if (namesHeld.get(set) !== items || !keys.length) return Promise.resolve(null)
  return ask({ t: 'find', set, keys }, [], 10_000)
}
