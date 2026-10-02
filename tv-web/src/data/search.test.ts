import { describe, expect, it } from 'vitest'
import { searchByName } from './search'

describe('searchByName', () => {
  const items = ['The Batman', 'Batman Begins', 'Amélie', 'Sport 1', 'News'].map((name) => ({ name }))
  it('needs every word, ignores case and accents, and ranks prefix matches first', () => {
    expect(searchByName(items, 'batman').map((i) => i.name)).toEqual(['Batman Begins', 'The Batman'])
    expect(searchByName(items, 'AMELIE').map((i) => i.name)).toEqual(['Amélie'])
    expect(searchByName(items, 'begins batman').map((i) => i.name)).toEqual(['Batman Begins'])
    expect(searchByName(items, 'b')).toEqual([])
  })
})
