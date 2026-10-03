import { describe, expect, it } from 'vitest'
import { resumeLastLive, setResumeLastLive } from './livePrefs'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
describe('livePrefs', () => {
  it('is off until turned on, and is not server-scoped', () => {
    const kv = new Kv()
    expect(resumeLastLive(kv)).toBe(false)
    setResumeLastLive(kv, true)
    expect(resumeLastLive(kv)).toBe(true)
    setResumeLastLive(kv, false)
    expect(resumeLastLive(kv)).toBe(false)
    expect([...kv.m.keys()].some((k) => k.startsWith('dx.srv.'))).toBe(false)
  })
})
