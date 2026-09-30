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
  cats: LiveCategory[] = []; streams: LiveStream[] = []; clears = 0
  async replaceLive(c: LiveCategory[], s: LiveStream[]) { this.cats = c; this.streams = s }
  async liveCategories() { return this.cats }
  async liveStreams() { return this.streams }
  async clear() { this.cats = []; this.streams = []; this.clears++ }
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
})
