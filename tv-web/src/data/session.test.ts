import { describe, expect, it } from 'vitest'
import type { CatalogueStore } from './catalogueStore'
import { Session } from './session'
import type { LiveCategory, LiveStream } from './xtreamApi'

class MemKv {
  private m = new Map<string, string>()
  get length() { return this.m.size }
  key(i: number) { return [...this.m.keys()][i] ?? null }
  getItem(k: string) { return this.m.get(k) ?? null }
  setItem(k: string, v: string) { this.m.set(k, v) }
  removeItem(k: string) { this.m.delete(k) }
}
class MemStore implements CatalogueStore {
  cats: LiveCategory[] = []; streams: LiveStream[] = []; clears = 0; reads = 0
  async replaceLive(c: LiveCategory[], s: LiveStream[]) { this.cats = c; this.streams = s }
  async liveCategories() { this.reads++; return this.cats }
  async liveStreams() { this.reads++; return this.streams }
  lib: Record<string, { cats: unknown[]; items: unknown[] }> = {}
  async replaceLibrary(k: string, c: unknown[], i: unknown[]) { this.lib[k] = { cats: c, items: i } }
  async libraryCategories(k: string) { return (this.lib[k]?.cats ?? []) as never[] }
  async libraryItems(k: string) { return (this.lib[k]?.items ?? []) as never[] }
  async clear() { this.cats = []; this.streams = []; this.lib = {}; this.clears++ }
}
const provider = (streams: unknown[]): typeof fetch => (async (url: string) => {
  const u = String(url)
  const body = u.includes('get_live_categories') ? [{ category_id: 1, category_name: 'Sport' }]
    : u.includes('get_live_streams') ? streams
    : { user_info: { auth: 1, status: 'Active', max_connections: '1', exp_date: '1900000000' }, server_info: { timezone: 'Europe/Amsterdam' } }
  return new Response(JSON.stringify(body), { status: 200 })
}) as typeof fetch

const A = { server: 'http://a.example', username: 'u', password: 'p' }
const B = { server: 'http://b.example', username: 'u', password: 'p' }

describe('Session (server-switch contract)', () => {
  it('syncs, and a provider switch purges the old catalogue and scoped keys BEFORE writing', async () => {
    const kv = new MemKv(); const store = new MemStore(); const s = new Session(kv, store)
    s.setAccount(A)
    const r = await s.sync(provider([{ stream_id: 7, name: 'DD Sports', category_id: 1, tv_archive: 1 }]))
    expect(r).toMatchObject({ categories: 1, channels: 1, account: { maxConnections: 1, timezone: 'Europe/Amsterdam' } })
    expect(store.streams[0]).toMatchObject({ id: '7', archive: true })
    kv.setItem('dx.srv.favourites', '[7]')
    expect(s.setAccount(B)).toBe(true)
    expect(s.isServerDataStale()).toBe(true)
    await s.sync(provider([{ stream_id: 1, name: 'Other', category_id: 1 }]))
    expect(store.clears).toBe(2) // the first sync claims the empty TV for A; the switch clears A for B
    expect(kv.getItem('dx.srv.favourites')).toBeNull()
    expect(store.streams.map((x) => x.id)).toEqual(['1'])
    expect(s.isServerDataStale()).toBe(false)
  })
  it('never lets an empty fetch wipe a populated catalogue', async () => {
    const kv = new MemKv(); const store = new MemStore(); const s = new Session(kv, store)
    s.setAccount(A)
    await s.sync(provider([{ stream_id: 7, name: 'x', category_id: 1 }]))
    const r = await s.sync(provider([]))
    expect(r.channels).toBe(1)
    expect(store.streams).toHaveLength(1)
  })
  it('refuses a rejected login', async () => {
    const s = new Session(new MemKv(), new MemStore())
    s.setAccount(A)
    const refused = (async () => new Response(JSON.stringify({ user_info: { auth: 0 } }))) as unknown as typeof fetch
    await expect(s.sync(refused)).rejects.toThrow('login refused')
  })

  it('syncs movies and series apart: one failing keeps the other, and an empty list keeps what we had', async () => {
    const kv = new MemKv(); const store = new MemStore(); const s = new Session(kv, store)
    s.setAccount(A)
    const lib = (movies: unknown, failSeries: boolean): typeof fetch => (async (url: string) => {
      const u = String(url)
      if (failSeries && u.includes('get_series')) return new Response('x', { status: 500 })
      const body = u.includes('get_vod_categories') || u.includes('get_series_categories') ? [{ category_id: 1, category_name: 'A' }]
        : u.includes('get_vod_streams') ? movies
        : u.includes('get_series') ? [{ series_id: 4, name: 'Dark', category_id: 1 }] : {}
      return new Response(JSON.stringify(body), { status: 200 })
    }) as typeof fetch
    const r1 = await s.syncLibrary(lib([{ stream_id: 1, name: 'Dune', category_id: 1 }], false))
    expect(r1).toMatchObject({ movies: 1, shows: 1, error: null })
    const r2 = await s.syncLibrary(lib([], true))
    expect(r2).toMatchObject({ movies: 1, shows: 1, error: 'HTTP 500' })
    expect((await s.libraryItems('movies')).length).toBe(1)
    expect((await s.libraryItems('shows')).length).toBe(1)
  })
})

/**
 * W3d/W4 QA (2026-10-02): every visit to Live TV, Movies or Series walked the whole table again -
 * 15,951 channels - so each screen change showed "loading" once more.
 */
describe('Session (catalogue is read once)', () => {
  const chan = (id: number) => ({ stream_id: id, name: `Ch ${id}`, category_id: 1, tv_archive: 0 })

  it('reads the store once and serves the rest of the run from memory', async () => {
    const store = new MemStore(); const s = new Session(new MemKv(), store)
    s.setAccount(A)
    await s.sync(provider([chan(1), chan(2)]))
    store.reads = 0

    await s.liveStreams(); await s.liveStreams(); await s.liveCategories(); await s.liveCategories()
    expect(store.reads).toBe(2)                      // one per table, not one per visit
    expect(await s.liveStreams()).toHaveLength(2)    // and it still answers correctly
  })

  it('serves two screens asking at the same time from one read', async () => {
    const store = new MemStore(); const s = new Session(new MemKv(), store)
    s.setAccount(A)
    await s.sync(provider([chan(1)]))
    store.reads = 0
    await Promise.all([s.liveStreams(), s.liveStreams(), s.liveStreams()])
    expect(store.reads).toBe(1)
  })

  it('forgets what it holds when a sync replaces it', async () => {
    const store = new MemStore(); const s = new Session(new MemKv(), store)
    s.setAccount(A)
    await s.sync(provider([chan(1)]))
    expect(await s.liveStreams()).toHaveLength(1)
    await s.sync(provider([chan(1), chan(2), chan(3)]))
    expect(await s.liveStreams()).toHaveLength(3)     // not the one it was holding
  })

  it('forgets what it holds when the provider changes', async () => {
    const store = new MemStore(); const s = new Session(new MemKv(), store)
    s.setAccount(A)
    await s.sync(provider([chan(1), chan(2)]))
    expect(await s.liveStreams()).toHaveLength(2)
    s.setAccount(B)
    await s.sync(provider([chan(9)]))
    expect(await s.liveStreams()).toHaveLength(1)
  })

  it('does not remember a failed read as the answer', async () => {
    const store = new MemStore(); const s = new Session(new MemKv(), store)
    let broken = true
    store.liveStreams = async () => { store.reads++; if (broken) throw new Error('idb closed'); return [chan(5)] as never }
    await expect(s.liveStreams()).rejects.toThrow('idb closed')
    broken = false
    expect(await s.liveStreams()).toHaveLength(1)
  })
})
