import { describe, expect, it } from 'vitest'
import { clockOf, parseAvplayTracks, playableAudioInstead, seekStep } from './vodTypes'

describe('vodTypes', () => {
  const tracks = parseAvplayTracks([
    { type: 'VIDEO', index: 0, extra_info: '{}' },
    { type: 'AUDIO', index: 1, extra_info: '{"language":"eng","fourCC":"DTS","channels":"6"}' },
    { type: 'AUDIO', index: 2, extra_info: { language: 'ger', fourCC: 'AC3', channels: 2 } },
    { type: 'TEXT', index: 3, extra_info: '{"track_lang":"ara","subtitle_type":"SRT"}' },
    { type: 'AUDIO', index: 4, extra_info: 'not json' },
  ])
  it('reads AVPlay track info in both shapes, never throwing', () => {
    expect(tracks.map((t) => `${t.kind}:${t.index}:${t.label}`)).toEqual([
      'audio:1:English · DTS · 5.1', 'audio:2:German · AC3', 'text:3:Arabic · SRT', 'audio:4:Track 3'])
    expect(parseAvplayTracks(null)).toEqual([])
  })

  it('moves off a DTS track to one the TV can decode, and only then', () => {
    expect(playableAudioInstead(tracks, 1)).toBe(2)
    expect(playableAudioInstead(tracks, 2)).toBeNull()
    expect(playableAudioInstead(tracks.filter((t) => t.index === 1), 1)).toBeNull()
  })

  it('formats time and speeds up a held seek', () => {
    expect(clockOf(249_000)).toBe('4:09')
    expect(clockOf(3_725_000)).toBe('1:02:05')
    expect([0, 2, 3, 7, 8].map(seekStep)).toEqual([10_000, 10_000, 30_000, 30_000, 60_000])
  })
})
