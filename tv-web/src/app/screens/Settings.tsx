import { deviceId, type Platform } from '../../platform'
import { playerKindFor } from '../../player/PlayerAdapter'
import { Focusable } from '../Focusable'

declare const __APP_VERSION__: string

export function Settings({ platform, onHome }: { platform: Platform; onHome: () => void }) {
  const id = deviceId(platform)
  return (
    <>
      <h1>Settings</h1>
      <div className="info">
        <div><b>Version</b>{__APP_VERSION__}</div>
        <div><b>TV platform</b>{platform}</div>
        <div><b>Player</b>{playerKindFor(platform)}</div>
        <div><b>Device</b>{id ? `${id.slice(0, 6)}…` : 'not available'}</div>
      </div>
      <div className="buttons" style={{ marginTop: 40 }}>
        <Focusable focusKey="settings-home" className="button" onEnter={onHome}>Back to Home</Focusable>
      </div>
    </>
  )
}
