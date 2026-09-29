import { FocusContext, setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'
import { Focusable } from './Focusable'

/** BACK at the top asks before leaving; "Stay" holds focus so a stray OK keeps the viewer in. */
export function ExitDialog({ onStay, onExit }: { onStay: () => void; onExit: () => void }) {
  const { ref, focusKey } = useFocusable({ focusKey: 'exit-dialog', isFocusBoundary: true })
  useEffect(() => { void setFocus('exit-stay') }, [])
  return (
    <FocusContext.Provider value={focusKey}>
      <div className="dialog-scrim">
        <div ref={ref} className="dialog">
          <h2>Leave DX Play?</h2>
          <div className="buttons">
            <Focusable focusKey="exit-stay" className="button" onEnter={onStay}>Stay</Focusable>
            <Focusable focusKey="exit-leave" className="button" onEnter={onExit}>Leave</Focusable>
          </div>
        </div>
      </div>
    </FocusContext.Provider>
  )
}
