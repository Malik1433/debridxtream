import { describe, expect, it } from 'vitest'
import { ALL_ID, FAV_ID, RECENT_ID, itemsOf, libraryRows, startRow } from './libraryModel'

const it_ = (id: string, categoryId: string, added: number) => ({ id, categoryId, added })
describe('libraryModel', () => {
  const items = [it_('1', 'a', 10), it_('2', 'b', 30), it_('3', 'a', 20)]
  it('puts Favourites, Recently added and All first and drops empty categories', () => {
    const rows = libraryRows([{ id: 'a', name: 'Action' }, { id: 'z', name: 'Empty' }], items, ['3'])
    expect(rows.map((r) => r.id)).toEqual([FAV_ID, RECENT_ID, ALL_ID, 'a'])
    expect(startRow(rows)).toBe(0)
    expect(startRow(libraryRows([], items, []))).toBe(1)
  })
  it('orders recently added newest first and favourites as added', () => {
    expect(itemsOf(RECENT_ID, items, []).map((i) => i.id)).toEqual(['2', '3', '1'])
    expect(itemsOf(FAV_ID, items, ['3', 'x', '1']).map((i) => i.id)).toEqual(['3', '1'])
    expect(itemsOf('a', items, []).map((i) => i.id)).toEqual(['1', '3'])
  })
})
