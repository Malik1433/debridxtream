import type { LiveCategory, LiveStream } from './xtreamApi'

/** Where one provider's catalogue lives on the TV. IndexedDB: 17,000+ channels are too big for localStorage. */
export interface CatalogueStore {
  replaceLive(categories: LiveCategory[], streams: LiveStream[]): Promise<void>
  liveCategories(): Promise<LiveCategory[]>
  liveStreams(): Promise<LiveStream[]>
  clear(): Promise<void>
}

const DB = 'dxplay'
const VERSION = 1
const STORES = ['live_categories', 'live_streams'] as const

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

  async liveCategories(): Promise<LiveCategory[]> { return all(await this.conn(), 'live_categories') }
  async liveStreams(): Promise<LiveStream[]> { return all(await this.conn(), 'live_streams') }

  async clear(): Promise<void> {
    const db = await this.conn()
    const tx = db.transaction(STORES as unknown as string[], 'readwrite')
    STORES.forEach((s) => tx.objectStore(s).clear())
    await done(tx)
  }
}
