import { describe, expect, it } from 'vitest'
import { RECENT_SEARCH_MAX, recentSearches, recordSearch } from './recentSearches'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
describe('recentSearches', () => {
  it('keeps the newest first, once each, capped, under a server-scoped key', () => {
    const kv = new Kv()
    for (let i = 0; i < 12; i++) recordSearch(kv, `Q${i}`)
    recordSearch(kv, ' q5 ')
    recordSearch(kv, '   ')
    const r = recentSearches(kv)
    expect(r.length).toBe(RECENT_SEARCH_MAX)
    expect(r.slice(0, 3)).toEqual(['q5', 'Q11', 'Q10'])
    expect(r.filter((x) => x.toLowerCase() === 'q5').length).toBe(1)
    expect([...kv.m.keys()]).toEqual(['dx.srv.search.recent'])
  })
  it('survives junk in storage', () => {
    const kv = new Kv(); kv.setItem('dx.srv.search.recent', '{bad')
    expect(recentSearches(kv)).toEqual([])
  })
})
