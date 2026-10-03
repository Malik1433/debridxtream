import { describe, expect, it } from 'vitest'
import { matchTitles, titleKey } from './titleMatch'

const items = [
  { id: '1', name: 'EN - Dune: Part Two (2024) 4K' },
  { id: '2', name: '|FR| Dune Part Two' },
  { id: '3', name: 'The Batman (2022)' },
  { id: '4', name: 'Batman Begins' },
]
describe('matchTitles', () => {
  it('finds the first provider item per wanted title, like the full index did', () => {
    const full = new Map<string, (typeof items)[number]>()
    for (const x of items) { const k = titleKey(x.name); if (k && !full.has(k)) full.set(k, x) }
    const got = matchTitles(items, ['Dune: Part Two', 'The Batman', 'Nope'])
    expect(got.get('duneparttwo')?.id).toBe(full.get('duneparttwo')?.id)
    expect(got.get('thebatman')?.id).toBe('3')
    expect(got.size).toBe(2)
  })
  it('does nothing without titles', () => {
    expect(matchTitles(items, []).size).toBe(0)
  })
})
