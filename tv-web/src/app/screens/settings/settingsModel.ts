import type { IconName } from '../../icons'

/**
 * Android SettingsCategoryAdapter.metaFor / descFor, in rail order. Home Screen and Addons are not
 * here: Home's rows (app language, layout, row pickers) and the Stremio add-ons have no tv-web
 * counterpart yet, and Android itself hides Addons on an IPTV-only device.
 */
export type CategoryKey = 'playback' | 'live' | 'data' | 'about' | 'account'
export interface Category { key: CategoryKey; title: string; sub: string; desc: string; accent: string; icon: IconName }

export const CATEGORIES: Category[] = [
  { key: 'playback', title: 'Playback', sub: 'AUDIO · LANGUAGE', desc: 'How audio is chosen when something plays', accent: '#FF3366', icon: 'play' },
  { key: 'live', title: 'Live TV', sub: 'LAYOUT · TV GUIDE', desc: 'Live TV layout and the TV guide', accent: '#00FF88', icon: 'live_tv' },
  { key: 'data', title: 'Data & Storage', sub: 'REFRESH · CACHE', desc: 'Refresh the catalog and free up storage', accent: '#A78BFA', icon: 'settings' },
  { key: 'about', title: 'About', sub: 'VERSION · BUILD', desc: 'App version and build information', accent: '#64748B', icon: 'person' },
  { key: 'account', title: 'Account', sub: 'SIGN OUT', desc: 'The provider account this box is signed in to', accent: '#FF3355', icon: 'logout' },
]

/** Android SettingItem: Toggle, Action (optionally destructive) and Info - same shapes, same rows. */
export type Row =
  | { kind: 'toggle'; key: string; title: string; desc: string; on: boolean; icon: IconName; onPress: () => void }
  | { kind: 'action'; key: string; title: string; desc: string; icon: IconName; destructive?: boolean; onPress: () => void }
  | { kind: 'info'; key: string; title: string; value: string; icon: IconName }

export const isFocusableRow = (r: Row) => r.kind !== 'info'
