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
    let last = performance.now(), raf = 0
    const loop = (t: number) => { stats.push(t - last); last = t; raf = requestAnimationFrame(loop) }
    raf = requestAnimationFrame(loop)
    const id = setInterval(() => {
      const s = stats.snapshot()
      const mem = (performance as Performance & { memory?: { usedJSHeapSize: number } }).memory
      setLine(`${s.fps} fps · p95 ${s.p95} ms · janky ${s.jankPct}% · worst ${s.worst} ms · DOM ${document.getElementsByTagName('*').length}` +
        ` · img ${document.images.length}${mem ? ` · heap ${Math.round(mem.usedJSHeapSize / 1048576)} MB` : ''}`)
    }, 1_000)
    return () => { cancelAnimationFrame(raf); clearInterval(id) }
  }, [])
  // The app's own lifecycle record (R4b): read whether the TV killed it or it closed itself.
  const life = (() => { try { return lifecycleLines(localStorage).slice(-6) } catch { return [] } })()
  return <div className="perf-hud">{line}{life.map((l, i) => <div key={i} className="perf-life">{l}</div>)}</div>
}
