import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useState } from 'react'

function Chip({ index, label, count, active, onFocus, onEnter }: { index: number; label: string; count: number; active: boolean; onFocus: () => void; onEnter: () => void }) {
  const { ref, focused } = useFocusable({ focusKey: `live-cats-${index}`, onFocus: () => onFocus(), onEnterPress: onEnter })
  return (
    <div ref={ref} className={`live-chip${active ? ' active' : ''}${focused ? ' focused' : ''}`}>
      <span className="chip-dot" />
      <span className="chip-label">{label}</span>
      <span className="chip-count">{count}</span>
    </div>
  )
}

/**
 * Android's horizontal category chips (item_livev2_category_chip in rv_category_chips). The strip
 * slides so the focused chip stays in view; picking follows focus with the debounce (W4 QA N4).
 */
export function ChipRow({ rows, active, onEnter }: {
  rows: Array<{ name: string; count: number }>; active: number; onEnter: (i: number) => void
}) {
  const box = useFocusable({ focusKey: 'live-cats', saveLastFocusedChild: true, trackChildren: true, preferredChildFocusKey: `live-cats-${active}` })
  const [shift, setShift] = useState(0)
  return (
    <FocusContext.Provider value={box.focusKey}>
      <div ref={box.ref} className="live-chips">
        <div className="live-chips-strip" style={{ transform: `translateX(${-shift}px)` }}>
          {rows.map((r, i) => (
            <Chip key={i} index={i} label={r.name} count={r.count} active={i === active} onEnter={() => onEnter(i)}
              onFocus={() => {
                // Keep the focused chip inside the visible strip: measure it against the strip.
                const el = (box.ref.current as HTMLElement | null)?.querySelectorAll<HTMLElement>('.live-chip')[i]
                const w = (box.ref.current as HTMLElement | null)?.clientWidth ?? 0
                if (el) setShift((s) => (el.offsetLeft - s < 0 ? el.offsetLeft : el.offsetLeft + el.offsetWidth - s > w ? el.offsetLeft + el.offsetWidth - w : s))
              }} />
          ))}
        </div>
      </div>
    </FocusContext.Provider>
  )
}
