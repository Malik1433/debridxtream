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
interface LibraryBlob { id: LibraryKind; categories: VodCategory[]; items: Array<Movie | Show> }

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
    tx.objectStore('library').put({ id: kind, categories, items } satisfies LibraryBlob)
    // The v2 copy is superseded: clearing a store is one request, not one per row.
    tx.objectStore(catStore).clear(); tx.objectStore(itemStore).clear()
    await done(tx)
  }

  private async blob(kind: LibraryKind): Promise<LibraryBlob | null> {
    const db = await this.conn()
    return new Promise((resolve, reject) => {
      const req = db.transaction('library', 'readonly').objectStore('library').get(kind)
      req.onsuccess = () => resolve((req.result as LibraryBlob | undefined) ?? null)
      req.onerror = () => reject(req.error)
    })
  }

  async libraryCategories(kind: LibraryKind): Promise<VodCategory[]> {
    const b = await this.blob(kind)
    return b ? b.categories : all(await this.conn(), LIB_STORES[kind][0])
  }

  async libraryItems<T extends Movie | Show>(kind: LibraryKind): Promise<T[]> {
    const b = await this.blob(kind)
    return b ? (b.items as T[]) : all<T>(await this.conn(), LIB_STORES[kind][1])
  }

  async liveCategories(): Promise<LiveCategory[]> { return all(await this.conn(), 'live_categories') }
  async liveStreams(): Promise<LiveStream[]> { return all(await this.conn(), 'live_streams') }

  async clear(): Promise<void> {
    const db = await this.conn()
    const tx = db.transaction(STORES as unknown as string[], 'readwrite')
    STORES.forEach((s) => tx.objectStore(s).clear())
    await done(tx)
  }
}
