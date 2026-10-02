import { Icon, type IconName } from '../../icons'
import { clockOf } from '../../../player/vod/vodTypes'

export type CtlKey = 'rew' | 'prev' | 'play' | 'next' | 'ffwd' | 'episodes' | 'audio' | 'subs' | 'aspect'
export interface Ctl { key: CtlKey; icon: IconName; label: string }

/** Android custom_player_control_view, left to right. PREV/NEXT/EPISODES only for an episode. */
export function controlsFor(isEpisode: boolean): Ctl[] {
  return [
    { key: 'rew', icon: 'rewind', label: '−10s' },
    ...(isEpisode ? [{ key: 'prev' as const, icon: 'skip_previous' as const, label: 'PREV EP' }] : []),
    { key: 'play', icon: 'play', label: '' },
    ...(isEpisode ? [{ key: 'next' as const, icon: 'skip_next' as const, label: 'NEXT EP' }] : []),
    { key: 'ffwd', icon: 'forward', label: '+10s' },
    ...(isEpisode ? [{ key: 'episodes' as const, icon: 'player_episodes' as const, label: 'EPISODES' }] : []),
    { key: 'audio', icon: 'audio', label: 'AUDIO' },
    { key: 'subs', icon: 'player_subtitles', label: 'SUBS' },
    { key: 'aspect', icon: 'aspect_ratio', label: 'ASPECT' },
  ]
}

function useClockText(): string {
  const d = new Date()
  const h = d.getHours(), m = d.getMinutes()
  return `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
}

/** The top bar and bottom bar of the Android player overlay. */
export function PlayerChrome({ title, pill, subtitle, poster, posterTitle, posterSub, pos, dur, playing, seekFocused, ctls, focus, status }: {
  title: string; pill: string; subtitle: string; poster: string; posterTitle: string; posterSub: string
  pos: number; dur: number; playing: boolean; seekFocused: boolean; ctls: Ctl[]; focus: number; status: string
}) {
  const clock = useClockText()
  return (
    <>
      <div className="vp-top">
        <div className="vp-back"><Icon name="chevron_left" size={18} /> Back</div>
        <div className="vp-titles">
          <div className="vp-title">{title}{pill && <span className="vp-pill">{pill}</span>}</div>
          {subtitle && <div className="vp-sub">{subtitle}</div>}
        </div>
        <div className="vp-clock">{clock}</div>
      </div>
      <div className="vp-bottom">
        <div className="vp-poster">
          {poster && <img src={poster} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
          <div className="vp-poster-text"><b>{posterTitle}</b>{posterSub && <small>{posterSub}</small>}</div>
        </div>
        <div className="vp-controls">
          <div className="vp-status"><span>{status}</span><span className="vp-remaining">{dur > 0 ? `−${clockOf(Math.max(0, dur - pos))}` : ''}</span></div>
          <div className={`vp-seek${seekFocused ? ' focused' : ''}`}>
            <div className="vp-seek-fill" style={{ width: `${dur > 0 ? Math.min(100, (pos / dur) * 100) : 0}%` }} />
            <div className="vp-seek-knob" style={{ left: `${dur > 0 ? Math.min(100, (pos / dur) * 100) : 0}%` }} />
          </div>
          <div className="vp-times"><span>{clockOf(pos)}</span><span>{dur > 0 ? clockOf(dur) : ''}</span></div>
          <div className="vp-card">
            {ctls.map((c, i) => (
              <div key={c.key} className={`vp-ctl${c.key === 'play' ? ' big' : ''}${!seekFocused && i === focus ? ' focused' : ''}`}>
                <span className="vp-ctl-btn"><Icon name={c.key === 'play' ? (playing ? 'pause' : 'play') : c.icon} size={c.key === 'play' ? 40 : 30} /></span>
                {c.label && <small>{c.label}</small>}
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  )
}
