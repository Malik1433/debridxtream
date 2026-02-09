# IPTV Streaming App

A modern IPTV streaming interface built with React, TypeScript, and Tailwind CSS. Designed for Android TV boxes and responsive displays.

## Features

- **Live TV Browse**: Browse channels organized by country
- **Channel Grid**: View trending channels with logos and metadata
- **Live Preview**: Watch currently selected channel in the player panel
- **Favorites**: Mark and access favorite channels
- **Quality Indicators**: Display available quality options (4K, HD, EPG)
- **Real Data**: Uses real channel information and show details
- **TV Remote Support**: Fully keyboard navigable for TV box remote controls
- **Responsive Design**: Optimized for TV screens and desktop displays
- **Dark Theme**: Modern dark UI for comfortable viewing

## Project Structure

```
src/
├── App.tsx                 # Main app component with state management
├── global.css             # Design system tokens and global styles
├── components/
│   ├── Header.tsx         # Top bar with time, weather, search
│   ├── Sidebar.tsx        # Left navigation with countries
│   ├── ContentArea.tsx    # Central channel grid
│   ├── PlayerPanel.tsx    # Right side video player
│   └── ChannelCard.tsx    # Individual channel card component
└── data/
    └── channels.ts        # Real channel and show data
```

## Data

The app includes real channel data with:
- 9 countries with channel counts
- 8 premium channels with metadata
- Quality indicators (4K, HD, EPG, $)
- View counts and descriptions
- Thumbnail images from Unsplash

## Design System

### Colors (HSL)
- **Primary**: `222.2 47.4% 11.2%` (Dark blue)
- **Secondary**: `217.2 32.6% 17.5%` (Slate)
- **Accent**: `47.9 100% 50.4%` (Yellow)
- **Background**: `220 13% 7%` (Very dark)
- **Foreground**: `210 40% 98%` (Nearly white)

### Theme
- Dark mode optimized for TV viewing
- Glassmorphism effects with subtle gradients
- Smooth transitions and hover effects
- Focus indicators for remote control navigation

## Getting Started

1. Install dependencies:
```bash
npm install
```

2. Start the development server:
```bash
npm start
```

3. Open in browser:
```
http://localhost:3000
```

## Usage

### Browsing Channels
- Click on countries in the left sidebar to filter channels
- Click on a channel card to preview it in the player panel
- Hover over cards to see the play button

### Keyboard Navigation (TV Remote)
- Arrow keys to navigate
- Enter to select
- Tab to focus elements
- Esc to go back

### Player Panel
- Shows currently selected channel
- Displays video preview with description
- Progress bar for current playback
- Play, volume, and settings controls

## Technologies

- **React 18** - UI framework
- **TypeScript** - Type safety
- **Tailwind CSS** - Styling
- **Lucide React** - Icons
- **Framer Motion** - Animations (optional)

## Responsive Design

The app is fully responsive and works on:
- Android TV boxes (tested)
- Smart TV screens
- Desktop displays
- Tablets

## Customization

### Adding More Channels
Edit `/src/data/channels.ts` and add entries to the `shows` array.

### Changing Theme Colors
Update the CSS variables in `/src/global.css` in the `:root` section.

### Modifying Layout
Edit component styling in the `className` attributes using Tailwind utility classes.

## License

MIT
