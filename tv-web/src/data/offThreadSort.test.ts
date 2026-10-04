import { describe, expect, it } from 'vitest'
import { numericSortKeys, sortItems, type SortMode } from '../app/screens/library/libraryModel'
import { applyOrder, orderBy, orderOffThread } from './offThreadSort'

const items = Array.from({ length: 3000 }, (_, i) => ({
  id: String(i), categoryId: '1', name: i % 7 ? `Film ${i} (${1990 + (i % 30)})` : `Film ${i}`,
  added: 1_700_000_000 + ((i * 7919) % 400), rating: (i % 11) / 2, year: i % 5 ? '' : String(2000 + (i % 20)),
}))

describe('off-thread sort', () => {
  for (const mode of ['recent', 'rated', 'newest'] as SortMode[]) {
    it(`gives exactly sortItems' order for ${mode}, ties included`, () => {
      const k = numericSortKeys(items, mode)!
      expect(applyOrder(items, orderBy(k.primary, k.secondary)).map((x) => x.id)).toEqual(sortItems(items, mode).map((x) => x.id))
    })
  }
  it('leaves A - Z to the main thread', () => {
    expect(numericSortKeys(items, 'az')).toBeNull()
  })
  it('answers null where there is no worker, so the caller sorts as before', async () => {
    const k = numericSortKeys(items, 'recent')!
    expect(await orderOffThread(k.primary, k.secondary)).toBeNull()
  })
})

describe('worker title scan', () => {
  it('strips names and finds the ones holding a key, in catalogue order', async () => {
    const { stripName, namesContaining } = await import('./offThreadSort')
    const names = ['EN - Dune: Part Two (2024)', 'The Batman', 'Dune', 'Nope'].map(stripName)
    expect(names[0]).toBe('enduneparttwo2024')
    expect(Array.from(namesContaining(names, ['duneparttwo', 'batman']))).toEqual([0, 1])
  })
  it('falls back to the same matcher when the worker does not hold the list', async () => {
    const { matchTitles, matchTitlesOffThread } = await import('./titleMatch')
    const items = [{ name: 'The Batman (2022)' }, { name: 'Dune' }]
    expect(await matchTitlesOffThread('movies', items, ['The Batman'])).toEqual(matchTitles(items, ['The Batman']))
  })
})

describe('A - Z off the main thread', () => {
  it('gives exactly sortItems order for az, ties included', async () => {
    const { orderByName } = await import('./offThreadSort')
    const named = items.map((x, i) => ({ ...x, name: i % 9 ? `Title ${(i * 31) % 500}` : 'Ägypten' }))
    expect(applyOrder(named, orderByName(named.map((x) => x.name))).map((x) => x.id)).toEqual(sortItems(named, 'az').map((x) => x.id))
  })
})
