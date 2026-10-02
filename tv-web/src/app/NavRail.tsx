import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useState } from 'react'
import { Icon, type IconName } from './icons'
import type { Screen } from './router'

/** The Android order (HomeSidebarManager): Search, Home, Live TV, Movies, Series - Settings apart at the bottom. */
const ITEMS: Array<{ key: Screen; label: string; icon: IconName }> = [
  { key: 'search', label: 'Search', icon: 'search' },
  { key: 'home', label: 'Home', icon: 'home' },
  { key: 'live', label: 'Live TV', icon: 'live_tv' },
  { key: 'movies', label: 'Movies', icon: 'movie' },
  { key: 'series', label: 'Series', icon: 'series' },
]

function RailItem({ item, active, onEnter, onFocusChange }: {
  item: { key: Screen; label: string; icon: IconName }; active: boolean; onEnter: () => void; onFocusChange: (f: string | null) => void
}) {
  const { ref, focused } = useFocusable({
    focusKey: `nav-${item.key}`, onEnterPress: onEnter,
    onFocus: () => onFocusChange(item.key), onBlur: () => onFocusChange(null),
  })
  return (
    <div ref={ref} className={`rail-item${focused ? ' focused' : ''}${active ? ' active' : ''}`}>
      {active && <span className="rail-indicator" />}
      <Icon name={item.icon} size={34} />
    </div>
  )
}

/**
 * The Android TV navigation rail (view_home_sidebar / item_sidebar_nav): a floating 120 px glass rail
 * of icons, a cyan bar on the screen you are on, a blue→cyan capsule on the focused icon, and its
 * name in a flyout beside the rail while the rail has focus.
 */
export function NavRail({ screen, onOpen }: { screen: Screen; onOpen: (s: Screen) => void }) {
  const rail = useFocusable({ focusKey: 'sidebar', saveLastFocusedChild: true, trackChildren: true, preferredChildFocusKey: `nav-${screen}` })
  const [focusedKey, setFocusedKey] = useState<string | null>(null)
  const all = [...ITEMS, { key: 'settings' as Screen, label: 'Settings', icon: 'settings' as IconName }]
  const fi = all.findIndex((i) => i.key === focusedKey)
  // Flyout top: header (64 + 36 gap) then 76 px per item; Settings sits at the bottom.
  const flyTop = fi < 0 ? 0 : focusedKey === 'settings' ? 1028 - 26 - 68 : 26 + 64 + 36 + fi * 76
  return (
    <FocusContext.Provider value={rail.focusKey}>
      <div ref={rail.ref} className="rail">
        <div className="rail-brand">DX</div>
        <div className="rail-items">
          {ITEMS.map((it) => <RailItem key={it.key} item={it} active={screen === it.key} onEnter={() => onOpen(it.key)} onFocusChange={setFocusedKey} />)}
        </div>
        <div className="rail-divider" />
        <RailItem item={all[all.length - 1]} active={screen === 'settings'} onEnter={() => onOpen('settings')} onFocusChange={setFocusedKey} />
      </div>
      {fi >= 0 && <div className="rail-flyout" style={{ top: flyTop }}>{all[fi].label}</div>}
    </FocusContext.Provider>
  )
}
