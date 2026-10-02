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
/** v2 (W4) ADDS the library stores; an upgrade never drops one (CLAUDE.md: no destructive migrations). */
const VERSION = 2
const STORES = ['live_categories', 'live_streams', 'movie_categories', 'movies', 'show_categories', 'shows'] as const

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
  private conn(): Promise<IDBDatabase> { return (this.db ??= open()) }

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

  async replaceLibrary(kind: LibraryKind, categories: VodCategory[], items: Array<Movie | Show>): Promise<void> {
    const [catStore, itemStore] = LIB_STORES[kind]
    const db = await this.conn()
    const tx = db.transaction([catStore, itemStore], 'readwrite')
    const cats = tx.objectStore(catStore)
    const rows = tx.objectStore(itemStore)
    cats.clear(); rows.clear()
    categories.forEach((c) => cats.put(c))
    items.forEach((i) => rows.put(i))
    await done(tx)
  }

  async libraryCategories(kind: LibraryKind): Promise<VodCategory[]> { return all(await this.conn(), LIB_STORES[kind][0]) }
  async libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]> { return all<T>(await this.conn(), LIB_STORES[kind][1]) }

  async liveCategories(): Promise<LiveCategory[]> { return all(await this.conn(), 'live_categories') }
  async liveStreams(): Promise<LiveStream[]> { return all(await this.conn(), 'live_streams') }

  async clear(): Promise<void> {
    const db = await this.conn()
    const tx = db.transaction(STORES as unknown as string[], 'readwrite')
    STORES.forEach((s) => tx.objectStore(s).clear())
    await done(tx)
  }
}
