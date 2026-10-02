import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useState, type ReactNode } from 'react'

function Cell({ focusKey, left, top, width, height, onEnter, onFocus, children }: {
  focusKey: string; left: number; top: number; width: number; height: number; onEnter: () => void; onFocus: () => void; children: (f: boolean) => ReactNode
}) {
  const { ref, focused } = useFocusable({ focusKey, onEnterPress: onEnter, onFocus: () => onFocus() })
  return <div ref={ref} className={`vcell${focused ? ' focused' : ''}`} style={{ left, top, width, height }}>{children(focused)}</div>
}

/**
 * A poster grid of any length, for 50,000 films: only the rows around the focused one exist in the
 * DOM, and the grid moves under a fixed window instead of scrolling (TVs scroll badly) - the same
 * idea as VirtualList, in two dimensions.
 */
export function VirtualGrid({ focusKey, count, columns, cellWidth, cellHeight, visibleRows, startIndex = 0, render, onEnter, onFocusIndex }: {
  focusKey: string
  count: number
  columns: number
  cellWidth: number
  cellHeight: number
  visibleRows: number
  startIndex?: number
  render: (index: number, focused: boolean) => ReactNode
  onEnter: (index: number) => void
  onFocusIndex?: (index: number) => void
}) {
  const [focus, setFocus] = useState(() => Math.max(0, Math.min(startIndex, count - 1)))
  const container = useFocusable({ focusKey, saveLastFocusedChild: true, trackChildren: true, preferredChildFocusKey: `${focusKey}-${focus}` })
  const rows = Math.ceil(count / columns)
  const row = Math.floor(focus / columns)
  const topRow = Math.max(0, Math.min(row - 1, rows - visibleRows))
  const firstRow = Math.max(0, row - visibleRows)
  const lastRow = Math.min(rows - 1, row + visibleRows)
  const cells: ReactNode[] = []
  for (let r = firstRow; r <= lastRow; r++) {
    for (let c = 0; c < columns; c++) {
      const i = r * columns + c
      if (i >= count) break
      cells.push(
        <Cell key={i} focusKey={`${focusKey}-${i}`} left={c * cellWidth} top={(r - topRow) * cellHeight} width={cellWidth} height={cellHeight}
          onEnter={() => onEnter(i)} onFocus={() => { setFocus(i); onFocusIndex?.(i) }}>
          {(f) => render(i, f)}
        </Cell>,
      )
    }
  }
  return (
    <FocusContext.Provider value={container.focusKey}>
      <div ref={container.ref} className="vgrid" style={{ width: columns * cellWidth, height: visibleRows * cellHeight }}>{cells}</div>
    </FocusContext.Provider>
  )
}
