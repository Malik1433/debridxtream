/**
 * The Android app's own icons (res/drawable/ic_*.xml vector paths), so the TV app draws the same
 * glyphs - owner rule: tv-web is a copy of Android. Generated from the XML; colour is currentColor.
 */
export type IconName = 'search' | 'home' | 'live_tv' | 'movie' | 'series' | 'settings' | 'play' | 'hero_info' | 'hero_plus' | 'favorite' | 'favorite_border' | 'check_circle' | 'rewind' | 'forward' | 'skip_next' | 'skip_previous' | 'pause' | 'live_star' | 'live_list' | 'live_guide' | 'live_audio' | 'back' | 'chevron_left' | 'player_episodes' | 'player_subtitles' | 'aspect_ratio' | 'audio'

const ICONS: Record<IconName, { vb: string; paths: Array<{ d: string; stroke: boolean; w: number }> }> = {
 "search": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "home": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "live_tv": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6L3,6c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2L23,8c0,-1.11 -0.9,-2 -2,-2zM21,20L3,20L3,8h18v12zM9,10v8l7,-4z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "movie": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "series": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M4,6L2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6zM20,2L8,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2zM20,16L8,16L8,4h12v12z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "settings": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "play": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M8,5v14l11,-7z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "hero_info": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20",
    "stroke": true,
    "w": 2.0
   },
   {
    "d": "M12,16L12,12",
    "stroke": true,
    "w": 2.0
   },
   {
    "d": "M12,8L12.01,8",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "hero_plus": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M12,5L12,19M5,12L19,12",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "favorite": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "favorite_border": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "check_circle": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "rewind": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M11,18L2.5,12 11,6v12zM20,18l-8.5,-6 8.5,-6v12z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "forward": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "skip_next": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "skip_previous": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M6,6h2v12H6zM9.5,12l8.5,6L18,6z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "pause": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "live_star": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M12,2 L15.09,8.26 L22,9.27 L17,14.14 L18.18,21.02 L12,17.77 L5.82,21.02 L7,14.14 L2,9.27 L8.91,8.26 Z",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "live_list": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M8,6 L21,6 M8,12 L21,12 M8,18 L21,18 M3,6 L3.01,6 M3,12 L3.01,12 M3,18 L3.01,18",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "live_guide": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M5,4 L19,4 A2,2 0 0 1 21,6 L21,18 A2,2 0 0 1 19,20 L5,20 A2,2 0 0 1 3,18 L3,6 A2,2 0 0 1 5,4 Z M3,9 L21,9 M9,9 L9,20",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "live_audio": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M11,5 L6,9 L2,9 L2,15 L6,15 L11,19 L11,5 Z M15.54,8.46 A5,5 0 0 1 15.54,15.53",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "back": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "chevron_left": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M15,18L9,12L15,6",
    "stroke": true,
    "w": 2.0
   }
  ]
 },
 "player_episodes": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M8,5h13v2h-13zM8,11h13v2h-13zM8,17h13v2h-13zM3,5h2v2h-2zM3,11h2v2h-2zM3,17h2v2h-2z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "player_subtitles": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM4,12h4v2H4V12zM14,14H10v-2h4V14zM20,14h-4v-2h4V14zM8,8H4v2h4V8zM14,10H10V8h4V10zM20,10h-4V8h4V10z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "aspect_ratio": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "L21,3L3,3C1.9,3 1,3.9 1,5L1,19C1,20.1 1.9,21 3,21L21,21C22.1,21 23,20.1 23,19L23,5C23,3.9 22.1,3 21,3ZM21,19.02L3,19.02L3,4.98L21,4.98L21,19.02ZM7,15L10,15L10,13L7,13L7,10L5,10L5,15C5,16.1 5.9,17 7,17L17,17L17,15L7,15ZM17,7L14,7L14,9L17,9L17,12L19,12L19,7C19,5.9 18.1,5 17,5L7,5L7,7L17,7Z",
    "stroke": false,
    "w": 2
   }
  ]
 },
 "audio": {
  "vb": "0 0 24 24",
  "paths": [
   {
    "d": "M3,10h2v4H3zM7,7h2v10H7zM11,4h2v16h-2zM15,7h2v10h-2zM19,10h2v4h-2z",
    "stroke": false,
    "w": 2
   }
  ]
 }
}

export function Icon({ name, size = 34, className }: { name: IconName; size?: number; className?: string }) {
  const ic = ICONS[name]
  return (
    <svg className={className} width={size} height={size} viewBox={ic.vb} aria-hidden="true">
      {ic.paths.map((p, i) => p.stroke
        ? <path key={i} d={p.d} fill="none" stroke="currentColor" strokeWidth={p.w} strokeLinecap="round" strokeLinejoin="round" />
        : <path key={i} d={p.d} fill="currentColor" />)}
    </svg>
  )
}
