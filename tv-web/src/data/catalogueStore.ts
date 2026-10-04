import { note } from '../app/perf/span'
import type { Movie, Show, VodCategory } from './vodApi'
import type { LiveCategory, LiveStream } from './xtreamApi'

/** Where one provider's catalogue lives on the TV. IndexedDB: 17,000+ channels are too big for localStorage. */
export interface CatalogueStore {
  replaceLive(categories: LiveCategory[], streams: LiveStream[]): Promise<void>
  liveCategories(): Promise<LiveCategory[]>
  liveStreams(): Promise<LiveStream[]>
  /** Movies or series: same rule as Live - one transaction, all or nothing. */
  replaceLibrary(kind: LibraryKind, categories: VodCategory[], items: Array<Movie | Show>): Promise<void>
  libraryCategories(kind: LibraryKind): Promise<VodCategory[]>
  libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]>
  /** Every store, for the server-switch purge. */
  clear(): Promise<void>
}

export type LibraryKind = 'movies' | 'shows'
const LIB_STORES: Record<LibraryKind, [string, string]> = { movies: ['movie_categories', 'movies'], shows: ['show_categories', 'shows'] }

const DB = 'dxplay'
/**
 * v2 (W4) added the per-record library stores; v3 adds `library`, where each half lives as ONE
 * record. An upgrade never drops a store (CLAUDE.md: no destructive migrations) - the v2 stores
 * are still read as a fallback until the first v3 write replaces them.
 */
const VERSION = 3
const STORES = ['live_categories', 'live_streams', 'movie_categories', 'movies', 'show_categories', 'shows', 'library'] as const
/** The v3 record as first written (items as objects) - still read. */
interface LibraryBlobV1 { id: LibraryKind; categories: VodCategory[]; items: Array<Movie | Show> }
/**
 * The record as written since W4 QA round 8: the items as ONE JSON string. On the Samsung,
 * `read:lib-movies` was 6.25 s - the IndexedDB read of 69,500 films, structured-clone-decoded on the
 * main thread. A single string comes out of IndexedDB as a copy, and V8's JSON.parse builds the
 * objects faster than structured clone does (~1.7x in a node measurement of the same shape).
 */
interface LibraryBlobV2 { id: LibraryKind; categories: VodCategory[]; itemsJson: string }
type LibraryBlob = LibraryBlobV1 | LibraryBlobV2
type LibraryRecord = LibraryBlob | LibraryHeadV3
/** The categories alone, so reading them never decodes the items (they used to: two full reads per open). */
interface LibraryCats { id: `${LibraryKind}-cats`; categories: VodCategory[] }

/**
 * Since W4 QA round 10: the items in chunks of 5,000, each its own record and its own JSON string.
 * Round 10 caught cold starts blocking the Samsung's main thread for 3-5 s at a stretch - one
 * JSON.parse of all 69,500 films - while everything else waited behind it (`load:lib-cats-movies`
 * 5.7 s cold, 18 ms warm: the categories were ready, their callback could not run). Each chunk is read
 * by its own request and parsed in its own task, so keys, paint and the categories get a turn between
 * them. Same total work, no multi-second block.
 */
export const CHUNK = 5_000
interface LibraryHeadV3 { id: LibraryKind; categories: VodCategory[]; chunks: number; count: number }
interface LibraryChunk { id: string; json: string }
const chunkKey = (kind: LibraryKind, i: number) => `${kind}#${i}`

export function encodeChunks(kind: LibraryKind, categories: VodCategory[], items: Array<Movie | Show>): { head: LibraryHeadV3; chunks: LibraryChunk[] } {
  const chunks: LibraryChunk[] = []
  for (let i = 0; i * CHUNK < items.length; i++) chunks.push({ id: chunkKey(kind, i), json: JSON.stringify(items.slice(i * CHUNK, (i + 1) * CHUNK)) })
  return { head: { id: kind, categories, chunks: chunks.length, count: items.length }, chunks }
}

/** Kept for the tests of the round-8 format, which is still read. */
export function encodeLibrary(kind: LibraryKind, categories: VodCategory[], items: Array<Movie | Show>): LibraryBlobV2 {
  return { id: kind, categories, itemsJson: JSON.stringify(items) }
}
export function decodeLibrary<T extends Movie | Show>(b: LibraryBlob): { categories: VodCategory[]; items: T[] } {
  return { categories: b.categories, items: ('itemsJson' in b ? JSON.parse(b.itemsJson) : b.items) as T[] }
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION)
    req.onupgradeneeded = () => {
      for (const s of STORES) if (!req.result.objectStoreNames.contains(s)) req.result.createObjectStore(s, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

function all<T>(db: IDBDatabase, store: string): Promise<T[]> {
  return new Promise((resolve, reject) => {
    const req = db.transaction(store, 'readonly').objectStore(store).getAll()
    req.onsuccess = () => resolve(req.result as T[])
    req.onerror = () => reject(req.error)
  })
}

export class IdbCatalogueStore implements CatalogueStore {
  private db: Promise<IDBDatabase> | null = null
  private conn(): Promise<IDBDatabase> {
    if (!this.db) {
      // W4 QA round 13: the cold first call is measured in its parts - opening the database, each
      // request's wait for its answer, and the parsing in between - before anything is changed again.
      const t = performance.now()
      this.db = open()
      this.db.then(() => note('idb:open', t), () => undefined)
    }
    return this.db
  }

  /** One transaction: a failed write leaves the previous catalogue, never half of the new one. */
  async replaceLive(categories: LiveCategory[], streams: LiveStream[]): Promise<void> {
    const db = await this.conn()
    const tx = db.transaction(STORES as unknown as string[], 'readwrite')
    const cats = tx.objectStore('live_categories')
    const chans = tx.objectStore('live_streams')
    cats.clear(); chans.clear()
    categories.forEach((c) => cats.put(c))
    streams.forEach((s) => chans.put(s))
    await done(tx)
  }

  /**
   * One put of one record. W4 QA P3: 69,536 films + 7,443 series as ~77,000 puts in one transaction
   * ended in "Transaction timed out due to inactivity" on the Samsung - the TV could not finish the
   * write in time and the update was lost. The screens only ever read a whole half at once, so one
   * structured-clone record is both the fast write and the fast read, and still all or nothing.
   */
  async replaceLibrary(kind: LibraryKind, categories: VodCategory[], items: Array<Movie | Show>): Promise<void> {
    const [catStore, itemStore] = LIB_STORES[kind]
    const db = await this.conn()
    const tx = db.transaction(['library', catStore, itemStore], 'readwrite')
    const lib = tx.objectStore('library')
    const { head, chunks } = encodeChunks(kind, categories, items)
    // A previous, longer write's extra chunks must not survive: they would never be read, only kept.
    lib.delete(IDBKeyRange.bound(`${kind}#`, `${kind}#\uffff`))
    lib.put(head)
    chunks.forEach((c) => lib.put(c))
    lib.put({ id: `${kind}-cats`, categories } satisfies LibraryCats)
    // The v2 copy is superseded: clearing a store is one request, not one per row.
    tx.objectStore(catStore).clear(); tx.objectStore(itemStore).clear()
    await done(tx)
    this.libs.delete(kind)
  }

  private get<T>(store: string, key: string): Promise<T | null> {
    return this.conn().then((db) => new Promise<T | null>((resolve, reject) => {
      const t = performance.now()
      const req = db.transaction(store, 'readonly').objectStore(store).get(key)
      req.onsuccess = () => { note(`idb:get-${key}`, t); resolve((req.result as T | undefined) ?? null) }
      req.onerror = () => reject(req.error)
    }))
  }

  /** One decode per half, shared by whoever asks first (categories and items used to read it twice). */
  private libs = new Map<LibraryKind, Promise<{ categories: VodCategory[]; items: Array<Movie | Show> } | null>>()
  private library(kind: LibraryKind) {
    let p = this.libs.get(kind)
    if (!p) {
      p = this.get<LibraryRecord>('library', kind).then((b) => (!b ? null : 'chunks' in b ? this.readChunks(kind, b) : decodeLibrary(b)))
      p.catch(() => this.libs.delete(kind))
      this.libs.set(kind, p)
    }
    return p
  }

  /**
   * All chunks asked for at once, in one transaction - but each answer arrives as its own event and is
   * parsed there, so no single task holds the TV for the whole library (round 10). Read one by one in
   * separate transactions the load got 2-4x slower in total; this keeps the short tasks without that.
   */
  private async readChunks(kind: LibraryKind, head: LibraryHeadV3): Promise<{ categories: VodCategory[]; items: Array<Movie | Show> }> {
    const db = await this.conn()
    const parts: Array<Array<Movie | Show>> = new Array(head.chunks)
    await new Promise<void>((resolve, reject) => {
      const store = db.transaction('library', 'readonly').objectStore('library')
      const t = performance.now()
      let left = head.chunks
      if (!left) { resolve(); return }
      for (let i = 0; i < head.chunks; i++) {
        const req = store.get(chunkKey(kind, i))
        req.onsuccess = () => {
          const c = req.result as LibraryChunk | undefined
          if (!c) { reject(new Error(`library ${kind}: chunk ${i} of ${head.chunks} is missing`)); return }
          if (left === head.chunks) note(`idb:chunk-first-${kind}`, t)
          const p = performance.now()
          try { parts[i] = JSON.parse(c.json) } catch (e) { reject(e); return }
          note(`idb:parse-${kind}`, p)
          if (--left === 0) { note(`idb:chunks-${kind}`, t); resolve() }
        }
        req.onerror = () => reject(req.error)
      }
    })
    const items: Array<Movie | Show> = []
    for (const part of parts) for (let j = 0; j < part.length; j++) items.push(part[j])
    return { categories: head.categories, items }
  }

  async libraryCategories(kind: LibraryKind): Promise<VodCategory[]> {
    const own = await this.get<LibraryCats>('library', `${kind}-cats`)
    if (own) return own.categories
    const b = await this.library(kind)
    return b ? b.categories : all(await this.conn(), LIB_STORES[kind][0])
  }

  async libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]> {
    const b = await this.library(kind)
    // Handed over once: the session keeps its own sorted copy, so this one is not held twice.
    this.libs.delete(kind)
    return b ? (b.items as T[]) : all<T>(await this.conn(), LIB_STORES[kind][1])
  }

  async liveCategories(): Promise<LiveCategory[]> { return all(await this.conn(), 'live_categories') }
  async liveStreams(): Promise<LiveStream[]> { return all(await this.conn(), 'live_streams') }

  async clear(): Promise<void> {
    const db = await this.conn()
    const tx = db.transaction(STORES as unknown as string[], 'readwrite')
    STORES.forEach((s) => tx.objectStore(s).clear())
    await done(tx)
    this.libs.clear()
  }
}
