import { describe, expect, it } from 'vitest'
import { canPlayAudio, codecLine, mseAudioTypes, probeCodecs } from './mseTypes'

/** The Samsung of W3 QA: AAC only. */
const samsung = (t: string) => t === 'audio/mp4; codecs="mp4a.40.2"' || t.startsWith('video/mp4; codecs="avc1')

describe('codecSupport', () => {
  it('asks MSE for MP3 the way mpegts.js feeds it (raw audio/mpeg)', () => {
    expect(mseAudioTypes('mp3')).toEqual(['audio/mpeg'])
    expect(mseAudioTypes('ac-3')).toEqual(['audio/mp4; codecs="ac-3"'])
  })

  it('says which audio a TV cannot take', () => {
    expect(canPlayAudio('mp4a.40.2', samsung)).toBe(true)
    expect(canPlayAudio('mp3', samsung)).toBe(false)
    expect(canPlayAudio('ec-3', samsung)).toBe(false)
    expect(canPlayAudio(undefined, samsung)).toBe(true)
  })

  it('prints every audio the TV takes', () => {
    expect(codecLine(probeCodecs(samsung, false))).toBe('HD only (no HEVC) · audio AAC')
    expect(codecLine({ h264: true, hevc: false, aac: true, mp3: true, ac3: true, eac3: false })).toBe('HD only (no HEVC) · audio AAC+MP3+AC-3')
  })
})
