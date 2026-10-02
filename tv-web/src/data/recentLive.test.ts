import { describe, expect, it } from 'vitest'
import { RECENT_LIVE_MAX, recentChannels, recordChannel } from './recentLive'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
describe('recentLive', () => {
  it('keeps the newest first, once each, capped, under a server-scoped key', () => {
    const kv = new Kv()
    for (let i = 0; i < 15; i++) recordChannel(kv, { id: String(i), name: `C${i}`, icon: '' })
    recordChannel(kv, { id: '10', name: 'C10', icon: '' })
    const r = recentChannels(kv)
    expect(r.length).toBe(RECENT_LIVE_MAX)
    expect(r.slice(0, 3).map((c) => c.id)).toEqual(['10', '14', '13'])
    expect([...kv.m.keys()]).toEqual(['dx.srv.live.recent'])
  })
})
