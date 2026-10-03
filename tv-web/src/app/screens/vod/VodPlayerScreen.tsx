import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { appKey } from '../../../keys'
import { setScreenSaver, type Platform } from '../../../platform'
import { avplayAvailable } from '../../../player/AvplayAdapter'
import { AvplayVod } from '../../../player/vod/AvplayVod'
import { HtmlVod } from '../../../player/vod/HtmlVod'
import { clockOf, seekStep, type VodPlayer } from '../../../player/vod/vodTypes'
import { VOD_BUFFER_TIMEOUT_MS, VOD_MAX_RETRIES, VodRecovery } from '../../../policy/vodRecovery'
import type { Episode } from '../../../data/vodApi'
import { pushBackHandler } from '../../backStack'
import { recordLifecycle } from '../../perf/lifecycle'
import type { AppController } from '../../controller'
import { episodeRequest, nextEpisode, prevEpisode, showNextPrompt, type PlayRequest } from './playRequest'
import { PlayerChrome, controlsFor } from './PlayerChrome'
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
  const [menuKind, setMenuKind] = useState<'audio' | 'subs' | 'episodes'>('audio')
  const [seekFocused, setSeekFocused] = useState(false)
  const [ctlFocus, setCtlFocus] = useState(-1)
  const [fill, setFill] = useState(false)
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
    // W4 QA R5: a dropped connection reconnects and resumes, as Android's player does.
    const url = req.kind === 'movie' ? controller.movieUrl(req.id, req.ext) : controller.episodeUrl(req.id, req.ext)
    const recovery = new VodRecovery()
    recovery.progress(req.startMs)
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let stallTimer: ReturnType<typeof setTimeout> | null = null
    const clearStall = () => { if (stallTimer) { clearTimeout(stallTimer); stallTimer = null } }
    const fail = (message: string) => {
      clearStall()
      const r = url ? recovery.onFailure(message) : null
      // W4 QA R5: why a film drops by itself is unknown - keep when, where and what, past a restart.
      recordLifecycle(localStorage, `vod: ${message} at ${clockOf(player.positionMs())} - ${r ? `reconnect ${r.attempt}/${VOD_MAX_RETRIES}` : 'gave up'}`)
      if (!r || !url) { setStatus('error'); setError(message); return }
      setStatus('buffering')
      setNotice(`Connection lost — reconnecting (${r.attempt}/${VOD_MAX_RETRIES})…`)
      if (retryTimer) clearTimeout(retryTimer)
      retryTimer = setTimeout(() => { retryTimer = null; armStall(); player.open(url, r.resumeMs) }, r.delayMs)
    }
    const armStall = () => { clearStall(); stallTimer = setTimeout(() => fail('buffer timeout'), VOD_BUFFER_TIMEOUT_MS) }
    player.onEvent((e) => {
      if (e.type === 'playing') { clearStall(); recovery.recovered(); setStatus('playing') }
      else if (e.type === 'buffering') { armStall(); setStatus('buffering') }
      else if (e.type === 'error') fail(e.message)
      else if (e.type === 'notice') { setNotice(e.message); setTimeout(() => setNotice(''), 6_000) }
      else if (e.type === 'subtitle') {
        setSubtitle(e.text)
        if (subTimer) clearTimeout(subTimer)
        if (e.text) subTimer = setTimeout(() => setSubtitle(''), e.durationMs + 200)
      } else if (e.type === 'ended') { clearStall(); save(); setStatus('paused') }
      else if (e.type === 'ready') clearStall()
    })
    if (url) { armStall(); player.open(url, req.startMs) }
    else { setStatus('error'); setError('no account') }
    setStatus('loading'); setError(''); setNextDismissed(false)
    const saver = setInterval(save, SAVE_EVERY_MS)
    const ui = setInterval(() => { if (player.playing()) recovery.progress(player.positionMs()); tick((n) => n + 1) }, 500)
    return () => {
      save(); clearInterval(saver); clearInterval(ui); clearStall()
      if (subTimer) clearTimeout(subTimer)
      if (retryTimer) clearTimeout(retryTimer)
      player.destroy(); playerRef.current = null
    }
  }, [req, platform, controller, save])

  // The remote, the screensaver and the rest of the app are ours while this is open.
  useEffect(() => {
    pauseNav()
    setScreenSaver(platform, false)
    document.body.classList.add('vod-open')
    return () => { resumeNav(); setScreenSaver(platform, true); document.body.classList.remove('vod-open') }
  }, [platform])

  const playEpisode = useCallback((n: Episode | null) => {
    if (!n || !req.seriesId) return false
    save()
    const saved = controller.watchEntry('episode', n.id)
    setReq(episodeRequest(n, { id: req.seriesId, name: req.seriesName ?? '', poster: req.poster }, req.queue ?? [], saved && !saved.watched ? saved.progressMs : 0))
    return true
  }, [req, controller, save])
  const playNext = useCallback(() => playEpisode(nextEpisode(req)), [playEpisode, req])

  const p = playerRef.current
  const pos = seek.current.target >= 0 ? seek.current.target : p?.positionMs() ?? 0
  const dur = p?.durationMs() ?? 0
  const next = nextEpisode(req)
  const prompt = next && !nextDismissed && showNextPrompt(p?.positionMs() ?? 0, dur)
  const isEpisode = req.kind === 'episode'
  const ctls = controlsFor(isEpisode)
  const playIdx = ctls.findIndex((c) => c.key === 'play')
  const trackRows = p ? menuRows(p.tracks(), p.selected('audio'), p.selected('text')) : []
  const seasonEps = (req.queue ?? []).filter((e) => e.season === req.season)
  const rows = menuKind === 'episodes'
    ? seasonEps.map((e) => ({ kind: 'audio' as const, index: null, label: `E${e.number} · ${e.title}`, on: e.id === req.id }))
    : trackRows.filter((r) => (menuKind === 'audio' ? r.kind === 'audio' : r.kind === 'text'))

  // The episode ended with a next one waiting: go on (the prompt said it would).
  useEffect(() => {
    if (status === 'paused' && next && !nextDismissed && dur > 0 && (p?.positionMs() ?? 0) >= dur - 1500) playNext()
  })

  const osdVisible = Date.now() < osdUntil || status !== 'playing' || menu !== null
  useEffect(() => pushBackHandler(() => {
    if (menu !== null) { setMenu(null); return true }
    if (prompt) { setNextDismissed(true); return true }
    if (osdVisible && status === 'playing') { setOsdUntil(0); return true }
    onClose()
    return true
  }), [menu, prompt, onClose, osdVisible, status])

  useEffect(() => {
    const doSeek = (dir: number, stepMs?: number) => {
      const pl = playerRef.current
      if (!pl) return
      const s = seek.current
      const now = Date.now()
      s.repeats = now - s.last < SEEK_SETTLE_MS ? s.repeats + 1 : 0
      s.last = now
      const base = s.target >= 0 ? s.target : pl.positionMs()
      const d = pl.durationMs()
      s.target = Math.max(0, Math.min(d > 0 ? d - 2000 : Number.MAX_SAFE_INTEGER, base + dir * (stepMs ?? seekStep(s.repeats))))
      if (s.timer) clearTimeout(s.timer)
      s.timer = setTimeout(() => { pl.seekTo(s.target); s.target = -1; s.timer = null }, SEEK_SETTLE_MS)
      tick((n) => n + 1)
    }
    const toggle = () => { const pl = playerRef.current; if (!pl) return; if (pl.playing()) { pl.pause(); setStatus('paused') } else { pl.play() } }
    const activate = (i: number) => {
      const c = ctls[i]
      if (!c) return
      switch (c.key) {
        case 'rew': doSeek(-1, 10_000); break
        case 'ffwd': doSeek(1, 10_000); break
        case 'play': toggle(); break
        case 'prev': playEpisode(prevEpisode(req)); break
        case 'next': playNext(); break
        case 'episodes': setMenuKind('episodes'); setMenu(Math.max(0, seasonEps.findIndex((e) => e.id === req.id))); break
        case 'audio': setMenuKind('audio'); setMenu(0); break
        case 'subs': setMenuKind('subs'); setMenu(0); break
        case 'aspect': { const f = !fill; setFill(f); playerRef.current?.setFill?.(f); break }
      }
    }
    const onKey = (e: KeyboardEvent) => {
      const k = appKey(e.keyCode, platform)
      if (!k || k === 'back') return
      e.preventDefault()
      const wasVisible = Date.now() < osdUntil || status !== 'playing' || menu !== null
      setOsdUntil(Date.now() + OSD_MS)
      const pl = playerRef.current
      if (menu !== null) {
        if (k === 'up') setMenu(Math.max(0, menu - 1))
        else if (k === 'down') setMenu(Math.min(rows.length - 1, menu + 1))
        else if (k === 'enter') {
          if (menuKind === 'episodes') { const ep = seasonEps[menu]; setMenu(null); if (ep && ep.id !== req.id) playEpisode(ep) }
          else if (rows[menu] && pl) { const r = rows[menu]; pl.selectTrack(r.kind, r.index); tick((n) => n + 1) }
        }
        return
      }
      if (prompt && k === 'enter') { playNext(); return }
      // Media keys act the same whether the overlay shows or not.
      if (k === 'play_pause') return toggle()
      if (k === 'play') return pl?.play()
      if (k === 'pause') { pl?.pause(); setStatus('paused'); return }
      if (k === 'stop') return onClose()
      if (k === 'rew') return doSeek(-1)
      if (k === 'ff') return doSeek(1)
      if (k === 'ch_down') { playNext(); return }
      if (k === 'ch_up') { playEpisode(prevEpisode(req)); return }
      if (!wasVisible) {
        // Hidden overlay: ◀▶ seek straight away (Android VodSeekOverlay); anything else wakes it on Play.
        if (k === 'left' || k === 'right') { setSeekFocused(true); doSeek(k === 'left' ? -1 : 1); return }
        setSeekFocused(false); setCtlFocus(playIdx)
        return
      }
      if (seekFocused) {
        if (k === 'left' || k === 'right') doSeek(k === 'left' ? -1 : 1)
        else if (k === 'down') { setSeekFocused(false); setCtlFocus((f) => (f < 0 ? playIdx : f)) }
        else if (k === 'enter') toggle()
        return
      }
      const f = ctlFocus < 0 ? playIdx : ctlFocus
      if (k === 'left') setCtlFocus(Math.max(0, f - 1))
      else if (k === 'right') setCtlFocus(Math.min(ctls.length - 1, f + 1))
      else if (k === 'up') setSeekFocused(true)
      else if (k === 'enter') activate(f)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, menu, menuKind, rows, prompt, playNext, playEpisode, onClose, osdUntil, status, seekFocused, ctlFocus, ctls, playIdx, req, seasonEps, fill])

  const stage = document.getElementById('stage') ?? document.body
  const statusText = status === 'error' ? 'UNAVAILABLE' : status === 'paused' ? 'PAUSED' : status === 'buffering' || status === 'loading' ? 'BUFFERING' : 'NOW PLAYING'
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
      {osdVisible && (
        <div className="vp-overlay">
          <PlayerChrome title={req.seriesName ?? req.title} pill={isEpisode ? `S${req.season} · E${req.episode}` : ''}
            subtitle={isEpisode ? req.title : req.subtitle} poster={req.poster} posterTitle={req.seriesName ?? req.title}
            posterSub={isEpisode ? `S${req.season} · E${req.episode}` : req.subtitle} pos={pos} dur={dur}
            playing={status === 'playing' || status === 'buffering'} seekFocused={seekFocused} ctls={ctls}
            focus={ctlFocus < 0 ? playIdx : ctlFocus} status={statusText} />
        </div>
      )}
      {menu !== null && <TrackMenu rows={rows} focus={menu} title={menuKind === 'episodes' ? `SEASON ${req.season}` : menuKind === 'audio' ? 'AUDIO' : 'SUBTITLES'} />}
    </div>,
    stage,
  )
}
