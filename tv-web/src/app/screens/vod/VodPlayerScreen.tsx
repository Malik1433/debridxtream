import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { appKey } from '../../../keys'
import { setScreenSaver, type Platform } from '../../../platform'
import { avplayAvailable } from '../../../player/AvplayAdapter'
import { AvplayVod } from '../../../player/vod/AvplayVod'
import { HtmlVod } from '../../../player/vod/HtmlVod'
import { clockOf, seekStep, type VodPlayer } from '../../../player/vod/vodTypes'
import { pushBackHandler } from '../../backStack'
import type { AppController } from '../../controller'
import { episodeRequest, nextEpisode, showNextPrompt, type PlayRequest } from './playRequest'
import { TrackMenu, menuRows } from './TrackMenu'

const OSD_MS = 4_000
const SAVE_EVERY_MS = 10_000
const SEEK_SETTLE_MS = 700

/**
 * Full-screen movie / episode player. The remote belongs to it while it is open (spatial nav paused):
 * OK play/pause · ◀▶ seek (hold to go faster) · ▲ audio & subtitles · BACK saves and leaves ·
 * Play/Pause/Stop/FF/Rew keys do what they say. Progress is saved every 10 s and on the way out.
 */
export function VodPlayerScreen({ platform, controller, request, onClose }: {
  platform: Platform; controller: AppController; request: PlayRequest; onClose: () => void
}) {
  const [req, setReq] = useState(request)
  const videoRef = useRef<HTMLVideoElement>(null)
  const playerRef = useRef<VodPlayer | null>(null)
  const [, tick] = useState(0)
  const [status, setStatus] = useState<'loading' | 'playing' | 'paused' | 'buffering' | 'error'>('loading')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [subtitle, setSubtitle] = useState('')
  const [osdUntil, setOsdUntil] = useState(() => Date.now() + OSD_MS)
  const [menu, setMenu] = useState<number | null>(null)
  const [nextDismissed, setNextDismissed] = useState(false)
  const seek = useRef<{ target: number; repeats: number; last: number; timer: ReturnType<typeof setTimeout> | null }>({ target: -1, repeats: 0, last: 0, timer: null })

  const save = useCallback(() => {
    const p = playerRef.current
    if (!p) return
    const pos = p.positionMs(), dur = p.durationMs()
    if (pos <= 0) return
    controller.recordProgress({
      kind: req.kind, id: req.id, title: req.title, poster: req.poster, ext: req.ext, progressMs: pos, durationMs: dur,
      seriesId: req.seriesId, seriesName: req.seriesName, season: req.season, episode: req.episode,
    })
  }, [controller, req])

  // One player per screen; a new request (next episode) reopens it.
  useEffect(() => {
    const plane = document.getElementById('av-plane')
    const player: VodPlayer = platform === 'tizen' && plane && avplayAvailable() ? new AvplayVod(plane) : new HtmlVod(videoRef.current!)
    playerRef.current = player
    let subTimer: ReturnType<typeof setTimeout> | null = null
    player.onEvent((e) => {
      if (e.type === 'playing') setStatus('playing')
      else if (e.type === 'buffering') setStatus('buffering')
      else if (e.type === 'error') { setStatus('error'); setError(e.message) }
      else if (e.type === 'notice') { setNotice(e.message); setTimeout(() => setNotice(''), 6_000) }
      else if (e.type === 'subtitle') {
        setSubtitle(e.text)
        if (subTimer) clearTimeout(subTimer)
        if (e.text) subTimer = setTimeout(() => setSubtitle(''), e.durationMs + 200)
      } else if (e.type === 'ended') { save(); setStatus('paused') }
    })
    const url = req.kind === 'movie' ? controller.movieUrl(req.id, req.ext) : controller.episodeUrl(req.id, req.ext)
    if (url) player.open(url, req.startMs)
    else { setStatus('error'); setError('no account') }
    setStatus('loading'); setError(''); setNextDismissed(false)
    const saver = setInterval(save, SAVE_EVERY_MS)
    const ui = setInterval(() => tick((n) => n + 1), 500)
    return () => { save(); clearInterval(saver); clearInterval(ui); if (subTimer) clearTimeout(subTimer); player.destroy(); playerRef.current = null }
  }, [req, platform, controller, save])

  // The remote, the screensaver and the rest of the app are ours while this is open.
  useEffect(() => {
    pauseNav()
    setScreenSaver(platform, false)
    document.body.classList.add('vod-open')
    return () => { resumeNav(); setScreenSaver(platform, true); document.body.classList.remove('vod-open') }
  }, [platform])

  const playNext = useCallback(() => {
    const n = nextEpisode(req)
    if (!n || !req.seriesId) return false
    save()
    const saved = controller.watchEntry('episode', n.id)
    setReq(episodeRequest(n, { id: req.seriesId, name: req.seriesName ?? '', poster: req.poster }, req.queue ?? [], saved && !saved.watched ? saved.progressMs : 0))
    return true
  }, [req, controller, save])

  const p = playerRef.current
  const pos = seek.current.target >= 0 ? seek.current.target : p?.positionMs() ?? 0
  const dur = p?.durationMs() ?? 0
  const next = nextEpisode(req)
  const prompt = next && !nextDismissed && showNextPrompt(p?.positionMs() ?? 0, dur)
  const rows = p ? menuRows(p.tracks(), p.selected('audio'), p.selected('text')) : []

  // The episode ended with a next one waiting: go on (the prompt said it would).
  useEffect(() => {
    if (status === 'paused' && next && !nextDismissed && dur > 0 && (p?.positionMs() ?? 0) >= dur - 1500) playNext()
  })

  useEffect(() => pushBackHandler(() => {
    if (menu !== null) { setMenu(null); return true }
    if (prompt) { setNextDismissed(true); return true }
    onClose()
    return true
  }), [menu, prompt, onClose])

  useEffect(() => {
    const doSeek = (dir: number) => {
      const pl = playerRef.current
      if (!pl) return
      const s = seek.current
      const now = Date.now()
      s.repeats = now - s.last < SEEK_SETTLE_MS ? s.repeats + 1 : 0
      s.last = now
      const base = s.target >= 0 ? s.target : pl.positionMs()
      const d = pl.durationMs()
      s.target = Math.max(0, Math.min(d > 0 ? d - 2000 : Number.MAX_SAFE_INTEGER, base + dir * seekStep(s.repeats)))
      if (s.timer) clearTimeout(s.timer)
      s.timer = setTimeout(() => { pl.seekTo(s.target); s.target = -1; s.timer = null }, SEEK_SETTLE_MS)
      setOsdUntil(now + OSD_MS)
      tick((n) => n + 1)
    }
    const toggle = () => { const pl = playerRef.current; if (!pl) return; if (pl.playing()) { pl.pause(); setStatus('paused') } else { pl.play() } }
    const onKey = (e: KeyboardEvent) => {
      const k = appKey(e.keyCode, platform)
      if (!k || k === 'back') return
      e.preventDefault()
      setOsdUntil(Date.now() + OSD_MS)
      const pl = playerRef.current
      if (menu !== null) {
        if (k === 'up') setMenu(Math.max(0, menu - 1))
        else if (k === 'down') setMenu(Math.min(rows.length - 1, menu + 1))
        else if (k === 'enter' && rows[menu] && pl) { pl.selectTrack(rows[menu].kind, rows[menu].index); tick((n) => n + 1) }
        return
      }
      if (prompt && k === 'enter') { playNext(); return }
      switch (k) {
        case 'enter': case 'play_pause': toggle(); break
        case 'play': pl?.play(); break
        case 'pause': pl?.pause(); setStatus('paused'); break
        case 'left': case 'rew': doSeek(-1); break
        case 'right': case 'ff': doSeek(1); break
        case 'up': setMenu(0); break
        case 'stop': onClose(); break
        case 'ch_down': if (next) playNext(); break
        default: break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, menu, rows, prompt, next, playNext, onClose])

  const osd = Date.now() < osdUntil || status !== 'playing' || menu !== null
  const stage = document.getElementById('stage') ?? document.body
  return createPortal(
    <div className="vod-player">
      <video ref={videoRef} playsInline />
      {(status === 'loading' || status === 'buffering') && <div className="vod-center"><div className="spinner" /></div>}
      {status === 'error' && <div className="vod-center vod-error">This video could not be played ({error}).<br />Press BACK to return.</div>}
      {subtitle && <div className="vod-sub">{subtitle.split('\n').map((l, i) => <div key={i}>{l}</div>)}</div>}
      {notice && <div className="pill vod-notice">{notice}</div>}
      {prompt && next && (
        <div className="next-prompt">
          <div className="np-t">Next: S{next.season} · E{next.number} {next.title}</div>
          <div className="np-s">in {Math.max(0, Math.ceil((dur - (p?.positionMs() ?? 0)) / 1000))} s · OK play now · BACK cancel</div>
        </div>
      )}
      {osd && (
        <div className="vod-osd">
          <div className="vo-title">{req.seriesName ? `${req.seriesName} · ` : ''}{req.title}<span>{req.subtitle}</span></div>
          <div className="vo-bar"><div style={{ width: `${dur > 0 ? Math.min(100, (pos / dur) * 100) : 0}%` }} /></div>
          <div className="vo-times"><span>{status === 'paused' ? '❚❚ ' : ''}{clockOf(pos)}</span><span>{dur > 0 ? `-${clockOf(dur - pos)}` : ''}</span></div>
          <div className="vo-help">OK play/pause · ◀▶ seek · ▲ audio & subtitles{next ? ' · CH− next episode' : ''} · BACK exit</div>
        </div>
      )}
      {menu !== null && <TrackMenu rows={rows} focus={menu} />}
    </div>,
    stage,
  )
}
