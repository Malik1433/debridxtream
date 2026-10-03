import type { Programme } from '../../../data/epg'
import { Icon, type IconName } from '../../icons'
import { clock, progress } from './liveModel'

export const OSD_BUTTONS: Array<{ key: 'channels' | 'guide' | 'cc' | 'audio'; icon: IconName | null; label: string }> = [
  { key: 'channels', icon: 'live_list', label: 'Channels' },
  { key: 'guide', icon: 'live_guide', label: 'TV Guide' },
  { key: 'cc', icon: null, label: 'Off' },
  { key: 'audio', icon: 'live_audio', label: 'Default' },
]

/**
 * Android's live fullscreen overlay (view_live_player_osd): channel bug top-left (logo, number, name,
 * LIVE), clock top-right, and at the bottom NOW PLAYING, the programme title, start ▬ end and the
 * minutes left, the ON AIR NOW card, and the Channels · TV Guide · CC · Audio buttons.
 */
export function FullscreenOsd({ number, name, logo, quality, programmes, now, focus, onAirNext }: {
  number: number; name: string; logo: string; quality: string; programmes: Programme[]; now: number; focus: number
  onAirNext: Programme[]
}) {
  const [cur] = programmes
  const left = cur ? Math.max(0, Math.round((cur.end - now) / 60_000)) : 0
  const d = new Date(now)
  return (
    <div className="lo-root">
      <div className="lo-scrim-top" /><div className="lo-scrim-bottom" />
      <div className="lo-bug">
        <div className="lo-logo">{logo ? <img src={logo} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} /> : <span>{name.slice(0, 2).toUpperCase()}</span>}</div>
        <div>
          <div className="lo-bug-name"><small>{number}</small>{name}</div>
          <div className="lo-bug-badges"><span className="lo-live"><i />LIVE</span>{quality && <span className="lo-quality">{quality}</span>}</div>
        </div>
      </div>
      <div className="lo-clock">{`${((d.getHours() + 11) % 12) + 1}:${String(d.getMinutes()).padStart(2, '0')} ${d.getHours() < 12 ? 'AM' : 'PM'}`}</div>
      <div className="lo-bottom">
        <div className="lo-info">
          <div className="lo-now-label">NOW PLAYING</div>
          <div className="lo-title">{cur?.title ?? name}</div>
          {cur ? (
            <div className="lo-time">
              <span>{clock(cur.start)}</span>
              <div className="lo-progress"><div style={{ width: `${Math.round(progress(cur.start, cur.end, now) * 100)}%` }} /></div>
              <span>{clock(cur.end)}</span>
              <b>{left} min left</b>
            </div>
          ) : <div className="lo-time"><span>No programme guide for this channel</span></div>}
          <div className="lo-buttons">
            {OSD_BUTTONS.map((b, i) => (
              <div key={b.key} className={`lo-btn${i === focus ? ' focused' : ''}`}>
                {b.icon ? <Icon name={b.icon} size={26} /> : <small>CC</small>}
                <span>{b.label}</span>
              </div>
            ))}
          </div>
        </div>
        {onAirNext.length > 0 && (
          <div className="lo-onair">
            <div className="lo-onair-head"><span>UP NEXT</span><span>{clock(now)}</span></div>
            {onAirNext.slice(0, 3).map((p, i) => <div key={i} className="lo-onair-row"><b>{clock(p.start)}</b>{p.title}</div>)}
          </div>
        )}
      </div>
    </div>
  )
}
