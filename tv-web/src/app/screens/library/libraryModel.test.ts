import { describe, expect, it } from 'vitest'
import { ALL_ID, FAV_ID, RECENT_ID, itemsOf, libraryRows, sortItems, startRow } from './libraryModel'

const it_ = (id: string, categoryId: string, added: number, name = id, rating = 0, year = '') => ({ id, categoryId, added, name, rating, year })
describe('libraryModel (Android VodFragment order)', () => {
  const items = [it_('1', 'a', 10, 'Zed', 3, '2001'), it_('2', 'b', 30, 'Alpha', 4.5, '1999'), it_('3', 'a', 20, 'Mid (2024)', 1)]
  it('puts All, Recently Added and Favorites first, and opens on All', () => {
    const rows = libraryRows([{ id: 'a', name: 'Action' }, { id: 'z', name: 'Empty' }], items, ['3'], 'All Series')
    expect(rows.map((r) => r.id)).toEqual([ALL_ID, RECENT_ID, FAV_ID, 'a'])
    expect(rows[0].name).toBe('All Series')
    expect(startRow(rows)).toBe(0)
  })
  it('lists recently added newest first and favourites as added', () => {
    expect(itemsOf(RECENT_ID, items, []).map((i) => i.id)).toEqual(['2', '3', '1'])
    expect(itemsOf(FAV_ID, items, ['3', 'x', '1']).map((i) => i.id)).toEqual(['3', '1'])
  })
  it('sorts like the four chips', () => {
    expect(sortItems(items, 'rated').map((i) => i.id)).toEqual(['2', '1', '3'])
    expect(sortItems(items, 'az').map((i) => i.id)).toEqual(['2', '3', '1'])
    expect(sortItems(items, 'newest').map((i) => i.id)).toEqual(['3', '1', '2'])
  })
})
