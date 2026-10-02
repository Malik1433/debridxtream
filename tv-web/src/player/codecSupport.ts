import mpegts from 'mpegts.js'

/**
 * What this TV's media stack will actually decode through MSE.
 *
 * W3 QA (2026-09-30): the provider's 4K channels never produced a picture and raised no error
 * either, while the SAME channels play on the Fire TV. Those are HEVC, which ExoPlayer decodes in
 * hardware; in a browser mpegts.js can only hand HEVC to MSE if MSE accepts hvc1. So the question
 * "can this TV play our 4K channels at all?" has to be asked of the TV, not assumed.
 */
export interface CodecSupport { h264: boolean; hevc: boolean; aac: boolean; mp3: boolean }

const H264 = 'video/mp4; codecs="avc1.640028"'
const HEVC = 'video/mp4; codecs="hvc1.1.6.L93.B0"'
const AAC = 'audio/mp4; codecs="mp4a.40.2"'
/** MP3 inside MP4 has three spellings in the wild and a TV may take one, or none. */
const MP3 = ['audio/mp4; codecs="mp3"', 'audio/mp4; codecs="mp4a.69"', 'audio/mp4; codecs="mp4a.6B"']

export function codecSupport(): CodecSupport {
  const mse = typeof MediaSource !== 'undefined' ? MediaSource : null
  const can = (t: string) => Boolean(mse?.isTypeSupported?.(t))
  let viaLib = false
  try { viaLib = Boolean(mpegts.getFeatureList().mseH265Playback) } catch { viaLib = false }
  return { h264: can(H264), hevc: can(HEVC) && viaLib, aac: can(AAC), mp3: MP3.some(can) }
}

/** One short line for Settings, so the viewer and a QA run can both see the answer. */
export function codecLine(c: CodecSupport = codecSupport()): string {
  const v = c.hevc ? 'HD and 4K (HEVC)' : c.h264 ? 'HD only (no HEVC)' : 'no video'
  const a = [c.aac ? 'AAC' : null, c.mp3 ? 'MP3' : null].filter(Boolean).join('+') || 'none'
  return `${v} · audio ${a}`
}
