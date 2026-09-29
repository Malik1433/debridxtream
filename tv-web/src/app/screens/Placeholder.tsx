import { Focusable } from '../Focusable'

/**
 * A section that arrives in a later phase (docs/reports/WEB_TV_APP_DESIGN.md §8). It still holds one
 * focusable, because a screen the remote can move into must never leave focus nowhere.
 */
export function Placeholder({ title, phase, onHome }: { title: string; phase: string; onHome: () => void }) {
  return (
    <>
      <h1>{title}</h1>
      <p>Coming in {phase}.</p>
      <div className="buttons">
        <Focusable focusKey={`back-${title}`} className="button" onEnter={onHome}>Back to Home</Focusable>
      </div>
    </>
  )
}
