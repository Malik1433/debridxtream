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
