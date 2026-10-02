import mpegts from 'mpegts.js'
import { codecLine, mseCanPlay, probeCodecs, type CodecSupport } from './mseTypes'

/** This TV's answer (see mseTypes.ts); HEVC also needs mpegts.js's own H.265 path. */
export function codecSupport(): CodecSupport {
  let viaLib = false
  try { viaLib = Boolean(mpegts.getFeatureList().mseH265Playback) } catch { viaLib = false }
  return probeCodecs(mseCanPlay(), viaLib)
}

export function thisTvCodecLine(): string { return codecLine(codecSupport()) }
