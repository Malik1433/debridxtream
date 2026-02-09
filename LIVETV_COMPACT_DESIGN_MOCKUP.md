# Live TV Design Improvement: Compact Row Layout

This mockup proposes a more efficient, data-rich layout for the Live TV channel list.

## 1. Structure Overview (3-Column)

| **Sidebar (Categories)** | **Channel List (Compact Rows)** | **Preview Panel (Player + Info)** |
| :--- | :--- | :--- |
| **Width:** 260dp | **Width:** Flexible (Weight 1) | **Width:** Flexible (Weight 1) |
| Vertical List | **New: Compact Vertical List** | Video Player |
| | Shows 6-8 items/screen | Program Info |

---

## 2. The New "Compact Row" Channel Item

Instead of large cards, we use slim, information-dense rows.

**Dimensions:**
*   **Height:** 80dp (vs current 188dp)
*   **Background:** Dark transparent (`#1AFFFFFF`), Highlight on Focus (`#CCFFFFFF` or Brand Color)

**Layout (Left to Right):**

```text
[  LOGO  ]   **Channel Name**                        [HD] [4K]
 (60x60)     Now: Current Program Title               (Badges)
             [============------] (Progress)
```

**Visual Details:**

1.  **Logo Container (Left):**
    *   Size: 60dp x 45dp
    *   Scale: `fitCenter` (Ensures logo is fully visible, never cropped)
    *   Background: Dark gray/black for contrast

2.  **Info Section (Center - Expanded):**
    *   **Row 1:** `Channel Name` (Bold, White, 16sp)
    *   **Row 2:** `Current Program` (Light Gray, 14sp). *e.g., "The Big Bang Theory - S05E12"*
    *   **Row 3:** `Progress Bar` (Slim, Green/Blue). Shows elapsed time of current show.

3.  **Status Section (Right):**
    *   **Badges:** Small, pill-shaped tags for `HD`, `4K`, `FHD`.
    *   **Favorite Icon:** Heart icon (visible if favorite).

---

## 3. Interaction Design

*   **Focus State:**
    *   Row background turns **White/Light Gray**.
    *   Text turns **Black/Dark Blue**.
    *   A subtle "Glow" or "Elevation" is applied.
    *   This provides immediate, clear feedback on TV remote navigation.

*   **Selection:**
    *   **Click:** Plays channel in Preview Panel.
    *   **Double Click / Enter:** Opens Fullscreen Player.
    *   **Long Press:** Toggles Favorite.

---

## 4. Comparison

| Feature | Current Design | **New Compact Design** |
| :--- | :--- | :--- |
| **Items per Screen** | ~2.5 items | **~7-8 items** |
| **Logo Visibility** | Cropped (`centerCrop`) | **Full (`fitCenter`)** |
| **Information** | Name + (Hidden EPG) | **Name + Current Show + Progress** |
| **Scrolling** | Frequent scrolling needed | **Less scrolling to scan channels** |
| **Aesthetic** | "Gallery" style | **"EPG/Guide" style (Standard)** |

---

## 5. Preview Panel Enhancements

*   **Gradient Overlay:** A black-to-transparent gradient at the bottom of the video/image area to make text readable.
*   **Metadata:** Display "Next Program" info below the main description.

---

## ASCII Visualization

```text
+----------------+---------------------------------------------------+-----------------------------+
| CATEGORIES     |  CHANNELS (Focused Item Example)                  |  PREVIEW                    |
|                |                                                   |                             |
|  All           | +-----------------------------------------------+ | +-------------------------+ |
|  Favorites     | | [ CNN ]  **CNN International**           [HD] | | |                         | |
|  > USA         | |          Newsroom with Jim Acosta             | | |      [ VIDEO ]          | |
|  UK            | |          [==========-------]                  | | |                         | |
|  Sports        | +-----------------------------------------------+ | +-------------------------+ |
|  Movies        |                                                   |                             |
|                | +-----------------------------------------------+ |  **CNN International**      |
|                | | [ NBC ]  NBC Sports                    [FHD]| |                             |
|                | |          Premier League Live                  | |  Newsroom with Jim Acosta   |
|                | |          [===--------------]                  | |  10:00 - 11:00              |
|                | +-----------------------------------------------+ |                             |
|                |                                                   |  Next:                      |
|                | +-----------------------------------------------+ |  World Sport                |
|                | | [ HBO ]  HBO East                      [4K] | |                             |
|                | |          Game of Thrones                      | |                             |
|                | |          [=================-]                 | |                             |
|                | +-----------------------------------------------+ |                             |
+----------------+---------------------------------------------------+-----------------------------+
```
