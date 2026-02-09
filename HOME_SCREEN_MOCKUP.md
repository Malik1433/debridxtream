# DebridXtream Home Screen - Visual Mockup

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  DebridXtream    [Live TV] [Movies] [Series] [Search]               (⚙️)   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  Featured                                                                     │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐ │
│  │                     │  │                     │  │                     │ │
│  │   [Featured 1]      │  │   [Featured 2]      │  │   [Featured 3]      │ │
│  │   16:9 Backdrop     │  │   16:9 Backdrop     │  │   16:9 Backdrop     │ │
│  │                     │  │                     │  │                     │ │
│  │   Title Here        │  │   Title Here        │  │   Title Here        │ │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘ │
│                                                                               │
│  Continue Watching                                                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │     │
│  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │     │
│  │          │  │          │  │          │  │          │  │          │     │
│  │ Movie 1  │  │ Movie 2  │  │ Series 1 │  │ Movie 3  │  │ Series 2 │     │
│  │ ▓▓▓▓░░░░ │  │ ▓▓░░░░░░ │  │ ▓▓▓▓▓░░░ │  │ ▓▓▓░░░░░ │  │ ▓░░░░░░░ │     │
│  │ 15:30/45 │  │ 08:20/90 │  │ 32:10/45 │  │ 12:45/98 │  │ 05:15/42 │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘     │
│                                                                               │
│  Favorites                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │     │
│  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │     │
│  │    ★     │  │    ★     │  │    ★     │  │    ★     │  │    ★     │     │
│  │          │  │          │  │          │  │          │  │          │     │
│  │ Channel1 │  │ Movie A  │  │ Series X │  │ Movie B  │  │ Channel2 │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘     │
│                                                                               │
│  Recently Watched                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │  │  Poster  │     │
│  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │  │   3:4    │     │
│  │          │  │          │  │          │  │          │  │          │     │
│  │ Movie Z  │  │ Series Y │  │ Channel3 │  │ Movie C  │  │ Series W │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘     │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Layout Breakdown

### Header (Top Bar)
```
┌──────────────────────────────────────────────────────────────┐
│ DebridXtream  [Live TV] [Movies] [Series] [Search]      (⚙️)│
└──────────────────────────────────────────────────────────────┘
```
- **Left**: App title in bold white text (32sp)
- **Center**: 4 navigation buttons with rounded corners
  - Selected button: Light blue background (#60a5fa)
  - Normal buttons: Navy background (#1e3a8a)
- **Right**: Circular settings button with gear icon

### Section 1: Featured (3 large landscape cards)
```
┌─────────────────────┐
│                     │
│   16:9 Backdrop     │
│   with gradient     │
│                     │
│ Title at bottom     │
└─────────────────────┘
Size: 480x270dp each
```
- Shows 3 featured items (mix of live channels, movies, series)
- Large landscape cards with backdrop images
- Title overlay with gradient at bottom
- Horizontal scrolling

### Section 2: Continue Watching (with progress bars)
```
┌──────────┐
│  Poster  │
│   3:4    │
│          │
│ Title    │
│ ▓▓▓░░░░░ │  ← Progress bar
│ 15:30/45 │  ← Time display
└──────────┘
Size: 220x320dp each
```
- Shows up to 20 most recent partially watched items
- 3:4 portrait poster images
- Progress bar showing watch percentage
- Time remaining/elapsed display
- Only shows if items exist

### Section 3: Favorites (with star icons)
```
┌──────────┐
│  Poster  │
│   3:4  ★ │  ← Star icon overlay
│          │
│ Title    │
└──────────┘
Size: 220x320dp each
```
- User-favorited content
- Star icon overlay in top-right
- No limit on number of favorites
- Only shows if items exist

### Section 4: Recently Watched
```
┌──────────┐
│  Poster  │
│   3:4    │
│          │
│ Title    │
└──────────┘
Size: 220x320dp each
```
- Shows up to 30 most recently watched items
- Simple poster + title layout
- No star or progress indicators
- Only shows if items exist

## Color Scheme

### Background
- Gradient from `#1a237e` (dark blue) to `#283593` (medium blue)
- Subtle light particles effect (via background drawable)

### Buttons & Cards
- Navigation buttons:
  - Normal: `#1e3a8a` (navy)
  - Selected: `#60a5fa` (light blue)
  - Focused: White border
- Cards:
  - Background: `#1e293b` (dark slate)
  - Border radius: 12dp
  - Focused: 3dp amber border

### Text
- Primary: `#FFFFFF` (white)
- Secondary: `#AAAAAA` (light gray)
- Progress bar: `#FFB300` (amber)

## Navigation Flow

### D-pad Navigation
```
        ↑
        │
[Live TV] ← → [Movies] ← → [Series] ← → [Search] ← → (⚙️)
        │
        ↓
   [Featured Row]
        ↓
   [Continue Watching Row]
        ↓
   [Favorites Row]
        ↓
   [Recently Watched Row]
```

### Focus Behavior
- Default focus: Live TV button
- Arrow keys navigate between sections
- Each section is horizontally scrollable
- Empty sections are hidden automatically

## Interactions

1. **Navigation Buttons**: Switch between content types
2. **Settings Button**: Opens settings screen
3. **Featured Cards**: Click to play/view details
4. **Continue Watching**: Resumes from saved position
5. **Favorites**: Quick access to favorite content
6. **Recently Watched**: Jump back to recent content

## Auto-Refresh
- Data refreshes when returning to home screen
- Featured content randomized on each load
- Continue watching updates from SharedPreferences
- Favorites and recently watched load latest state

---

This beautiful, modern design matches the reference image while adding the Continue Watching and Favorites sections you requested! 🎉

