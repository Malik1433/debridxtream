import { describe, expect, it } from 'vitest'
import { learnBad, learnedBad } from './learnedAudio'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
describe('learnedAudio', () => {
  it('remembers a codec the TV failed on, once, and never AAC', () => {
    const kv = new Kv()
    expect(learnBad(kv, 'MP3')).toBe(true)
    expect(learnBad(kv, 'mp3')).toBe(false)
    expect(learnBad(kv, 'mp4a.40.2')).toBe(false)
    expect(learnBad(kv, undefined)).toBe(false)
    expect([...learnedBad(kv)]).toEqual(['mp3'])
  })
})
