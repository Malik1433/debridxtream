import { describe, expect, it } from 'vitest'
import { Parental, SESSION_UNLOCK_MS, isAdultCategoryName } from './parental'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } removeItem(k: string) { this.m.delete(k) } }

describe('parental', () => {
  it('matches the Android detector', () => {
    for (const n of ['XXX', '|ADULT|', '[18+]', 'Adultos', '★ PORN ★', '\u{1F51E} Night', '18 plus']) expect(isAdultCategoryName(n)).toBe(true)
    for (const n of ['Sport 18', 'SEX AND THE CITY', 'Madulta', 'Kids', '']) expect(isAdultCategoryName(n)).toBe(false)
  })

  it('hides adult content from the first launch; a PIN must be chosen before it can be shown', () => {
    let t = 0
    const kv = new Kv()
    const p = new Parental(kv, () => t)
    const cats = [{ id: '1', name: 'News' }, { id: '2', name: 'XXX' }]
    expect(p.hidesAdult()).toBe(true) // fresh install: hidden, no PIN yet
    expect(p.hasPin()).toBe(false)
    expect(p.unlock('1234')).toBe(false) // nothing opens it without a PIN
    expect(p.filterCategories(cats).map((c) => c.id)).toEqual(['1'])
    expect(p.filterItems([{ categoryId: '1' }, { categoryId: '2' }], cats)).toEqual([{ categoryId: '1' }])
    expect(p.setPin('12a4')).toBe(false)
    expect(p.setPin('1234')).toBe(true)
    expect(kv.m.get('dx.parental')).not.toContain('1234')
    expect(p.unlock('0000')).toBe(false)
    expect(p.unlock('1234')).toBe(true)
    expect(p.filterCategories(cats).length).toBe(2)
    t += SESSION_UNLOCK_MS
    expect(p.hidesAdult()).toBe(true)
    expect(p.disable('9999')).toBe(false)
    expect(p.disable('1234')).toBe(true)
    expect(p.hidesAdult()).toBe(false)
    expect(p.hasPin()).toBe(true)
    p.turnOn()
    expect(p.hidesAdult()).toBe(true)
  })
})
