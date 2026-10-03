import { describe, expect, it } from 'vitest'
import { LIFECYCLE_MAX, lifecycleLines, recordLifecycle } from './lifecycle'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
describe('lifecycle record', () => {
  it('keeps the last events with their time, oldest first', () => {
    const kv = new Kv()
    for (let i = 0; i < LIFECYCLE_MAX + 5; i++) recordLifecycle(kv, `e${i}`, new Date(2026, 9, 3, 11, 53, i))
    const l = lifecycleLines(kv)
    expect(l.length).toBe(LIFECYCLE_MAX)
    expect(l[l.length - 1]).toBe(`11:53:${LIFECYCLE_MAX + 4} e${LIFECYCLE_MAX + 4}`)
  })
  it('survives junk', () => {
    const kv = new Kv(); kv.setItem('dx.lifecycle', '{')
    expect(lifecycleLines(kv)).toEqual([])
  })
})
