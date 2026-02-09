# IPTV App - Design System & Theme Implementation Guide

## 📐 Design System Overview

This design system defines all colors, typography, spacing, and components used in the IPTV web app. Use this guide to implement the same theme in your Kotlin Android app.

---

## 🎨 Color System

### Primary Colors
```
Primary (Dark): #1C2846 (hsl(222.2 47.4% 11.2%))
Primary Foreground: #FAFBFE (hsl(210 40% 98%))
```

### Secondary Colors
```
Secondary: #2D3F52 (hsl(217.2 32.6% 17.5%))
Secondary Foreground: #FAFBFE (hsl(210 40% 98%))
```

### Background Colors
```
Background: #0E131E (hsl(220 13% 7%))
Foreground: #FAFBFE (hsl(210 40% 98%))
Card: #1A2332 (hsl(217.2 32.6% 17.5%))
Border: #1E2D3D (hsl(217.2 32.6% 17.5%))
```

### Accent Colors (Focus/Interaction)
```
Accent (Yellow - Focus): #CACA00 (hsl(47.9 100% 50.4%))
Accent Foreground: #26310B (hsl(26 83.3% 14.1%))

Blue (Hover): #3B82F6 (Blue-500)
Blue (Selected): #2563EB (Blue-600)
```

### State Colors
```
Success (Green): #10B981
Error/Destructive (Red): #EF4444
Warning (Yellow): #F59E0B
Info (Blue): #3B82F6
Muted (Slate): #64748B
```

### Quality Badge Colors
| Quality | Background | Text Color |
|---------|-----------|-----------|
| 4K | #7F1D1D (Red-900 @ 80%) | #FCA5A5 (Red-200) |
| HD | #1E3A8A (Blue-900 @ 80%) | #93C5FD (Blue-300) |
| EPG | #334155 (Slate-700 @ 80%) | #E2E8F0 (Slate-200) |
| $ (Premium) | #78350F (Yellow-900 @ 80%) | #FEF08A (Yellow-200) |

---

## 📱 Typography System

### Font Family
- **Primary Font**: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', sans-serif
- **Monospace Font**: 'Fira Code', monospace

### Font Sizes & Weights

| Component | Size | Weight | Usage |
|-----------|------|--------|-------|
| Page Title | 36sp | Bold (700) | Main page headers |
| Section Title | 28sp | Bold (700) | Channel card names |
| Subtitle | 22sp | Semibold (600) | Secondary titles |
| Body Text | 16sp | Normal (400) | Default text |
| Small Text | 14sp | Normal (400) | Captions, labels |
| Extra Small | 12sp | Normal (400) | Hint text |
| Badge Text | 14sp | Bold (700) | Quality badges |

### Line Heights
- Titles: 1.2
- Body: 1.5
- Captions: 1.4

---

## 📐 Spacing System (Base Unit: 4dp)

| Value | Pixels | Usage |
|-------|--------|-------|
| xs | 4dp | Tight spacing |
| sm | 8dp | Small spacing |
| md | 12dp | Medium spacing |
| lg | 16dp | Default spacing |
| xl | 24dp | Large spacing |
| 2xl | 32dp | Extra large spacing |
| 3xl | 48dp | Huge spacing |

### Common Combinations
- **Card Padding**: 24dp (6 * 4dp)
- **Section Padding**: 32dp (8 * 4dp)
- **Gap Between Items**: 16dp (4 * 4dp)
- **Small Gap**: 12dp (3 * 4dp)

---

## 🎭 Component Sizes

### Cards & Containers
| Component | Width | Height | Notes |
|-----------|-------|--------|-------|
| Channel Card | Full | 144dp | Large TV-friendly |
| Country Card | ~180dp | 200dp | Sidebar button |
| Settings Item | Full | Auto | Flexible height |
| Badge/Chip | Auto | 32dp | Quality badges |
| Button (Large) | 64dp | 64dp | Play/Control buttons |
| Button (Medium) | 56dp | 56dp | Secondary buttons |

### Icon Sizes
| Type | Size | Stroke Width | Notes |
|------|------|-------------|-------|
| Large Icon (emoji) | 56-64sp | N/A | Main icons |
| Medium Icon | 28-32sp | 1.5 | Lucide icons |
| Small Icon | 20-24sp | 1.5 | Inline icons |
| Tiny Icon | 16-18sp | 1.5 | Badges |

---

## 🎨 Shadows & Elevation

### Elevation System
```
Elevation 0: No shadow
Elevation 1: 0 1px 2px rgba(0,0,0,0.05)
Elevation 2: 0 4px 6px rgba(0,0,0,0.1)
Elevation 3: 0 10px 15px rgba(0,0,0,0.3)
Elevation 4: 0 20px 25px rgba(0,0,0,0.4)

Focus Glow: 0 0 40px rgba(202, 202, 0, 0.5) (Yellow)
Blue Glow: 0 0 30px rgba(59, 130, 246, 0.3) (Blue)
```

---

## 🎪 Border & Radius

### Border Radius
```
xs: 4dp      // Small corners
sm: 8dp      // Medium-small
md: 12dp     // Medium
lg: 16dp     // Large (default for cards)
xl: 20dp     // Extra large
full: 999dp  // Circular
```

### Border Width
```
Thin: 1dp    // Default borders
Medium: 2dp  // Card borders
Thick: 4dp   // Focus ring
```

### Border Colors
```
Default: rgba(30, 41, 59, 1) // Slate-700
Hover: rgba(59, 130, 246, 0.6) // Blue-400 @ 60%
Focus: rgba(202, 202, 0, 1) // Yellow-400
```

---

## 🎬 Animation & Transitions

### Transition Timings
```
Fast: 150ms ease-in-out     // Hover effects
Base: 300ms ease-in-out     // Normal transitions
Slow: 500ms ease-in-out     // Page transitions
```

### Easing Functions
```
ease-in-out: cubic-bezier(0.4, 0, 0.2, 1)
ease-out: cubic-bezier(0, 0, 0.2, 1)
ease-in: cubic-bezier(0.4, 0, 1, 1)
```

### Common Animations
```
Hover: Scale 1.05 + Shadow increase
Focus Ring: Glow effect + Border color change
Fade In: Opacity 0 → 1 (300ms)
Slide Up: Transform translateY(10px) → 0 (300ms)
```

---

## 📐 Layout System

### 3-Panel Layout (Live TV)
```
┌─────────────┬─────────────┬──────────────┐
│   Sidebar   │   Content   │    Player    │
│   (25%)     │   (35%)     │    (40%)     │
└─────────────┴─────────────┴──────────────┘
```

### Responsive Breakpoints
```
Phone: < 640px  (Single column)
Tablet: 640px - 1024px (2 columns)
Desktop: > 1024px (3 columns)
TV: Full width (3 panels)
```

---

## 🎮 Focus & Navigation

### Focus States
```
Unfocused: Subtle border + light background
Hovered: Bright border + background lift
Focused: Yellow ring (4dp) + glow effect
Selected: Blue glow + solid background
Pressed: Brightness decrease
Disabled: 50% opacity + no interaction
```

### Focus Colors
```
Primary Focus: Yellow (#CACA00)
Secondary Focus: Blue (#3B82F6)
Ring Thickness: 4dp
Ring Spread: 0px
```

### Keyboard Navigation
```
Arrow Keys: Navigate items
Enter/Select: Activate focused item
Escape: Back/Cancel
Tab: Move to next focusable element
Shift+Tab: Move to previous focusable element
```

---

## 🎨 Gradient System

### Gradient Backgrounds
```
Dark Gradient: linear-gradient(135deg, #0E131E 0%, #1A2332 100%)
Card Gradient: linear-gradient(135deg, rgba(255,255,255,0.05) 0%, rgba(255,255,255,0.02) 100%)
Text Gradient: Blue (#3B82F6) to Yellow (#CACA00)
```

### Overlay Gradients
```
Top to Bottom: rgba(0,0,0,0) → rgba(0,0,0,0.8)
Bottom Overlay: rgba(0,0,0,0.8) → rgba(0,0,0,0)
```

---

## 📊 Data Visualization

### Card Layout
```
┌─────────────────────────────────┐
│ ICON (56-64sp) │ NAME (28sp)    │
│                │ VIEWS (18sp)   │
│                │                │
│                ├─ BADGE BADGE BADGE ─ STAR │
└─────────────────────────────────┘
```

### Information Hierarchy
1. **Primary**: Channel Name (28sp, Bold)
2. **Secondary**: Views, Channel (18sp, Normal)
3. **Tertiary**: Quality badges (14sp, Bold)
4. **Icon**: Favorite star (28sp)

---

## ✅ Implementation Checklist

When implementing this design system in Kotlin:

- [ ] Define all colors in `colors.xml` or as sealed class
- [ ] Create typography styles in theme
- [ ] Set up spacing constants (4dp base unit)
- [ ] Configure border radius values
- [ ] Define shadow elevations
- [ ] Create reusable component sizes
- [ ] Implement focus state styling
- [ ] Set up animation transitions
- [ ] Test on actual TV hardware
- [ ] Verify color contrast (WCAG AA)
- [ ] Check text readability at 10 feet
- [ ] Test remote control navigation
- [ ] Optimize for 16:9 aspect ratio
- [ ] Verify all icons are 1.5dp stroke width

---

## 🔗 Related Files

- `/ANDROID_TV_REFERENCE.md` - Complete Kotlin implementation guide
- `/src/global.css` - CSS variable definitions
- `/src/components/*.tsx` - React component examples

---

## 📝 Notes for Developers

1. **Color Values**: All colors are in HSL format for consistency. Convert to RGB/Hex as needed for your platform.
2. **Font Sizes**: Sizes are in sp (scaled pixels) for Android - will scale with user's font size preference.
3. **Spacing**: Use 4dp as base unit for consistency. All spacing should be multiples of 4.
4. **Focus Indicators**: Yellow (#CACA00) is primary focus color - very distinct on dark backgrounds.
5. **TV Optimization**: All sizes account for 10-foot viewing distance. Never use sizes below 48dp for touch targets.
6. **Dark Theme**: This is a dark theme optimized for evening viewing. Background should never be white.
7. **Accessibility**: Ensure 4.5:1 contrast ratio for text. Yellow on dark background meets AAA standard.

