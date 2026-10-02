import { FocusContext, setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'
import { pushBackHandler } from '../../backStack'
import { Focusable } from '../../Focusable'

/** Android SeriesDetailActions.showSeasonPopup: a small list of seasons; OK picks, BACK closes. */
export function SeasonPicker({ seasons, current, onPick, onClose }: { seasons: number[]; current: number; onPick: (s: number) => void; onClose: () => void }) {
  const box = useFocusable({ focusKey: 'season-picker', isFocusBoundary: true, trackChildren: true })
  useEffect(() => { void setFocus(`season-opt-${current}`) }, [current])
  useEffect(() => pushBackHandler(() => { onClose(); return true }), [onClose])
  return (
    <FocusContext.Provider value={box.focusKey}>
      <div ref={box.ref} className="season-picker">
        {seasons.map((s) => (
          <Focusable key={s} focusKey={`season-opt-${s}`} className={`season-opt${s === current ? ' on' : ''}`} onEnter={() => onPick(s)}>
            Season {s}
          </Focusable>
        ))}
      </div>
    </FocusContext.Provider>
  )
}
