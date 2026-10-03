import { useEffect, useState } from 'react'
import { FrameStats } from './frameStats'
import { lifecycleLines } from './lifecycle'

/**
 * Frame rate on the TV itself (green key; 0 on VIDAA), on every screen (W4 QA R6). The rAF loop runs
 * only while the readout is open, so measuring does not cost the frames it measures. DOM size is
 * shown because the first suspect for a slow Home is how much the rows keep alive.
 */
export function PerfHud() {
  const [line, setLine] = useState('measuring…')
  useEffect(() => {
    const stats = new FrameStats()
    // Round 6: Home 36 fps where a detail page holds 60. Long tasks (>50 ms of JavaScript) tell the
    // main thread from the GPU: slow frames with no long tasks are paint / compositing.
    const longs: Array<{ at: number; ms: number }> = []
    let obs: PerformanceObserver | null = null
    try {
      obs = new PerformanceObserver((l) => { for (const e of l.getEntries()) longs.push({ at: e.startTime, ms: e.duration }) })
      obs.observe({ entryTypes: ['longtask'] })
    } catch { obs = null }
    // Round 7: is that JavaScript the key presses (focus engine + React) or something running on its
    // own? Each key: time inside its handlers, and time to the frame after it.
    const keys: Array<{ at: number; handlerMs: number; paintMs: number }> = []
    const onKey = () => {
      const t0 = performance.now()
      const k = { at: t0, handlerMs: 0, paintMs: 0 }
      keys.push(k)
      setTimeout(() => { k.handlerMs = performance.now() - t0 }, 0)
      requestAnimationFrame(() => requestAnimationFrame(() => { k.paintMs = performance.now() - t0 }))
    }
    window.addEventListener('keydown', onKey, true)
    let last = performance.now(), raf = 0
    const loop = (t: number) => { stats.push(t - last); last = t; raf = requestAnimationFrame(loop) }
    raf = requestAnimationFrame(loop)
    const id = setInterval(() => {
      const s = stats.snapshot()
      const mem = (performance as Performance & { memory?: { usedJSHeapSize: number } }).memory
      const since = performance.now() - 5_000
      while (longs.length && longs[0].at < since) longs.shift()
      while (keys.length && keys[0].at < since) keys.shift()
      const nearKey = (t: number, ms: number) => keys.some((k) => k.at >= t - 30 && k.at <= t + ms)
      const own = longs.filter((x) => !nearKey(x.at, x.ms))
      const js = obs ? ` · JS long ${longs.length}/${Math.round(longs.reduce((a, x) => a + x.ms, 0))} ms (5 s), ${own.length} without a key` : ' · JS long n/a'
      const done = keys.filter((k) => k.paintMs > 0)
      const avg = (f: (k: (typeof keys)[number]) => number) => (done.length ? Math.round(done.reduce((a, k) => a + f(k), 0) / done.length) : 0)
      const max = (f: (k: (typeof keys)[number]) => number) => Math.round(done.reduce((a, k) => Math.max(a, f(k)), 0))
      const keyLine = done.length ? ` · keys ${done.length}: handler ${avg((k) => k.handlerMs)}/${max((k) => k.handlerMs)} ms, to paint ${avg((k) => k.paintMs)}/${max((k) => k.paintMs)} ms (avg/max)` : ''
      setLine(`${s.fps} fps · p95 ${s.p95} ms · janky ${s.jankPct}% · worst ${s.worst} ms${js} · DOM ${document.getElementsByTagName('*').length}` +
        ` · img ${document.images.length}${mem ? ` · heap ${Math.round(mem.usedJSHeapSize / 1048576)} MB` : ''}${keyLine}`)
    }, 1_000)
    return () => { cancelAnimationFrame(raf); clearInterval(id); obs?.disconnect(); window.removeEventListener('keydown', onKey, true) }
  }, [])
  // The app's own lifecycle record (R4b): read whether the TV killed it or it closed itself.
  const life = (() => { try { return lifecycleLines(localStorage).slice(-6) } catch { return [] } })()
  return <div className="perf-hud">{line}{life.map((l, i) => <div key={i} className="perf-life">{l}</div>)}</div>
}
