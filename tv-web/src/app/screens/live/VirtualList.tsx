import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useState, type ReactNode } from 'react'

interface RowProps { focusKey: string; top: number; height: number; onEnter: () => void; onFocus: () => void; children: (focused: boolean) => ReactNode }

function Row({ focusKey, top, height, onEnter, onFocus, children }: RowProps) {
  const { ref, focused } = useFocusable({ focusKey, onEnterPress: onEnter, onFocus: () => onFocus() })
  return <div ref={ref} className={`vrow${focused ? ' focused' : ''}`} style={{ top, height }}>{children(focused)}</div>
}

/**
 * A remote-driven list of any length. Only the rows around the focused one are in the DOM - 16,000
 * channels as DOM nodes stall a TV - and the focused row stays in view by moving the rows, not by
 * scrolling (TVs scroll badly).
 */
export function VirtualList({ focusKey, count, rowHeight, visibleRows, render, onEnter, onFocusIndex, startIndex = 0 }: {
  focusKey: string
  count: number
  rowHeight: number
  visibleRows: number
  render: (index: number, focused: boolean) => ReactNode
  onEnter: (index: number) => void
  onFocusIndex?: (index: number) => void
  /** The row to have in view on mount (remount with a new `key` to jump). */
  startIndex?: number
}) {
  const [focus, setFocusIndex] = useState(() => Math.max(0, Math.min(startIndex, count - 1)))
  // Coming into the list lands on ITS row (the selected category, the channel we left), not on
  // whichever row happens to be nearest the key press - passing through must not change the choice.
  const container = useFocusable({ focusKey, saveLastFocusedChild: true, trackChildren: true, preferredChildFocusKey: `${focusKey}-${focus}` })
  const first = Math.max(0, focus - visibleRows)
  const last = Math.min(count - 1, focus + visibleRows)
  const offset = Math.max(0, Math.min(focus - Math.floor(visibleRows / 3), count - visibleRows)) * rowHeight
  const rows: ReactNode[] = []
  for (let i = first; i <= last; i++) {
    rows.push(
      <Row key={i} focusKey={`${focusKey}-${i}`} top={i * rowHeight - offset} height={rowHeight}
        onEnter={() => onEnter(i)} onFocus={() => { setFocusIndex(i); onFocusIndex?.(i) }}>
        {(focused) => render(i, focused)}
      </Row>,
    )
  }
  return (
    <FocusContext.Provider value={container.focusKey}>
      <div ref={container.ref} className="vlist" style={{ height: visibleRows * rowHeight }}>{rows}</div>
    </FocusContext.Provider>
  )
}
