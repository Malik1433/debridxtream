/**
 * What this TV's media stack will actually decode through MSE - the pure half, testable without
 * mpegts.js (which needs a browser to load). codecSupport.ts asks the real TV.
 *
 * W3 QA (2026-10-01): channels whose audio is not AAC produce no picture AND no error under
 * mpegts.js on the Samsung (`avc1.640028 / mp3 did not play`). mpegts.js has to hand every track to
 * MSE, and MSE takes only what the TV says it takes - so ask the TV, never assume.
 */
export interface CodecSupport { h264: boolean; hevc: boolean; aac: boolean; mp3: boolean; ac3: boolean; eac3: boolean }

export type CanPlay = (mime: string) => boolean

/**
 * The MSE type mpegts.js will ask for, per audio codec it reports in MEDIA_INFO. MP3 is the odd one:
 * outside Firefox mpegts.js feeds it as raw `audio/mpeg`, not inside MP4 (mp4-remuxer.js).
 */
export function mseAudioTypes(codec: string): string[] {
  return codec.toLowerCase() === 'mp3' ? ['audio/mpeg'] : [`audio/mp4; codecs="${codec}"`]
}

export function mseCanPlay(): CanPlay {
  const mse = typeof MediaSource !== 'undefined' ? MediaSource : null
  return (t) => { try { return Boolean(mse?.isTypeSupported?.(t)) } catch { return false } }
}

/** Can mpegts.js get this audio track into this TV's MSE? No codec reported yet = no reason to doubt. */
export function canPlayAudio(codec: string | undefined, can: CanPlay = mseCanPlay()): boolean {
  if (!codec) return true
  return mseAudioTypes(codec).some(can)
}

const H264 = 'video/mp4; codecs="avc1.640028"'
const HEVC = 'video/mp4; codecs="hvc1.1.6.L93.B0"'

export function probeCodecs(can: CanPlay, hevcViaLib: boolean): CodecSupport {
  return {
    h264: can(H264),
    hevc: can(HEVC) && hevcViaLib,
    aac: canPlayAudio('mp4a.40.2', can),
    mp3: canPlayAudio('mp3', can),
    ac3: canPlayAudio('ac-3', can),
    eac3: canPlayAudio('ec-3', can),
  }
}

/**
 * One short line for Settings, so the viewer and a QA run can both see the answer - and it must
 * match what the app DOES (W4 QA N1: the line promised AC-3 while the player moved AC-3 channels to
 * AVPlay). [learnedBad] = codecs this TV proved mpegts.js cannot play (learnedAudio.ts);
 * [fallbackName] = the player that carries them instead, if this TV has one.
 */
export function codecLine(c: CodecSupport, learnedBad: Set<string> = new Set(), fallbackName: string | null = null): string {
  const v = c.hevc ? 'HD and 4K (HEVC)' : c.h264 ? 'HD only (no HEVC)' : 'no video'
  const all: Array<[boolean, string, string]> = [[c.aac, 'AAC', 'mp4a.40.2'], [c.mp3, 'MP3', 'mp3'], [c.ac3, 'AC-3', 'ac-3'], [c.eac3, 'E-AC-3', 'ec-3']]
  const direct = all.filter(([ok, , codec]) => ok && !learnedBad.has(codec)).map(([, name]) => name)
  const viaFallback = all.filter(([, , codec]) => learnedBad.has(codec)).map(([, name]) => name)
  const a = (direct.join('+') || 'none') + (viaFallback.length && fallbackName ? ` · ${viaFallback.join('+')} via ${fallbackName}` : '')
  return `${v} · audio ${a}`
}
