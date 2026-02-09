import './global.css';
import React, { useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { ContentArea } from './components/ContentArea';
import { PlayerPanel } from './components/PlayerPanel';
import { SettingsPage } from './components/SettingsPage';
import { SearchPage } from './components/SearchPage';
import { countries, shows, Show } from './data/channels';
import { Settings, Search } from 'lucide-react';

type PageType = 'home' | 'settings' | 'search';

export default function App() {
  const [currentPage, setCurrentPage] = useState<PageType>('home');
  const [selectedCountry, setSelectedCountry] = useState<string>('usa');
  const [selectedShow, setSelectedShow] = useState<Show | undefined>(shows[0]);
  const [focusMode, setFocusMode] = useState<'sidebar' | 'content' | 'player'>('content');

  // Configure tailwind for dark theme
  React.useEffect(() => {
    if (typeof window !== 'undefined' && window.tailwind) {
      window.tailwind.config = {
        ...window.tailwind.config,
        theme: {
          extend: {
            colors: {
              primary: 'hsl(var(--primary))',
              'primary-foreground': 'hsl(var(--primary-foreground))',
              secondary: 'hsl(var(--secondary))',
              'secondary-foreground': 'hsl(var(--secondary-foreground))',
              accent: 'hsl(var(--accent))',
              'accent-foreground': 'hsl(var(--accent-foreground))',
              destructive: 'hsl(var(--destructive))',
              'destructive-foreground': 'hsl(var(--destructive-foreground))',
              muted: 'hsl(var(--muted))',
              'muted-foreground': 'hsl(var(--muted-foreground))',
              input: 'hsl(var(--input))',
              ring: 'hsl(var(--ring))',
              background: 'hsl(var(--background))',
              foreground: 'hsl(var(--foreground))',
              card: 'hsl(var(--card))',
              'card-foreground': 'hsl(var(--card-foreground))',
              border: 'hsl(var(--border))',
            },
            backgroundColor: {
              glass: 'rgba(255, 255, 255, 0.05)',
            },
            borderColor: {
              glass: 'rgba(255, 255, 255, 0.1)',
            },
          },
        },
      };
    }
  }, []);

  // TV Remote Navigation
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Page-specific navigation will be handled by individual pages
      // This handles global navigation
      if (currentPage === 'home') {
        // Dpad left/right navigation between panels
        if (e.key === 'ArrowLeft') {
          e.preventDefault();
          if (focusMode === 'content') setFocusMode('sidebar');
          else if (focusMode === 'player') setFocusMode('content');
        } else if (e.key === 'ArrowRight') {
          e.preventDefault();
          if (focusMode === 'sidebar') setFocusMode('content');
          else if (focusMode === 'content') setFocusMode('player');
        }
        
        // Quick access buttons
        if (e.key === 'F1' || (e.ctrlKey && e.key === 's')) {
          e.preventDefault();
          setCurrentPage('search');
        } else if (e.key === 'F2' || (e.ctrlKey && e.key === 'Shift' && e.key === 'S')) {
          e.preventDefault();
          setCurrentPage('settings');
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [focusMode, currentPage]);

  const handleCountrySelect = (countryId: string) => {
    setSelectedCountry(countryId);
  };

  // Render different pages
  if (currentPage === 'settings') {
    return (
      <SettingsPage onBack={() => setCurrentPage('home')} />
    );
  }

  if (currentPage === 'search') {
    return (
      <SearchPage
        shows={shows}
        onBack={() => setCurrentPage('home')}
        onSelectShow={(show) => {
          setSelectedShow(show);
          setCurrentPage('home');
        }}
      />
    );
  }

  // Main Live TV Page
  return (
    <div className="w-full h-screen bg-slate-950 flex flex-col overflow-hidden">
      {/* Quick Access Menu Bar - Top Right */}
      <div className="absolute top-4 right-4 z-50 flex gap-3">
        <button
          onClick={() => setCurrentPage('search')}
          className="p-4 rounded-xl bg-slate-800/60 hover:bg-slate-700 border border-slate-700 transition-all hover:ring-2 hover:ring-blue-400/60 flex items-center gap-2"
          title="Search (F1)"
        >
          <Search size={24} className="text-blue-400" strokeWidth={1.5} />
          <span className="text-slate-300 text-sm">Search</span>
        </button>
        <button
          onClick={() => setCurrentPage('settings')}
          className="p-4 rounded-xl bg-slate-800/60 hover:bg-slate-700 border border-slate-700 transition-all hover:ring-2 hover:ring-blue-400/60 flex items-center gap-2"
          title="Settings (F2)"
        >
          <Settings size={24} className="text-blue-400" strokeWidth={1.5} />
          <span className="text-slate-300 text-sm">Settings</span>
        </button>
      </div>

      {/* Main Content - Full Height, No Header for TV */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar - Left Panel */}
        <div
          className={`w-1/4 overflow-hidden transition-all duration-200 ${
            focusMode === 'sidebar' ? 'ring-4 ring-blue-500 ring-inset' : ''
          }`}
        >
          <Sidebar
            countries={countries}
            selectedCountry={selectedCountry}
            onSelectCountry={handleCountrySelect}
          />
        </div>

        {/* Content and Player - Right Split Layout */}
        <div className="flex flex-1 overflow-hidden gap-0">
          {/* Content Area - Channel List */}
          <div
            className={`w-2/5 overflow-hidden transition-all duration-200 ${
              focusMode === 'content' ? 'ring-4 ring-blue-500 ring-inset' : ''
            }`}
          >
            <ContentArea
              shows={shows}
              selectedShow={selectedShow}
              onSelectShow={setSelectedShow}
            />
          </div>

          {/* Player Panel - Video Preview */}
          <div
            className={`w-3/5 overflow-hidden transition-all duration-200 ${
              focusMode === 'player' ? 'ring-4 ring-blue-500 ring-inset' : ''
            }`}
          >
            <PlayerPanel show={selectedShow} />
          </div>
        </div>
      </div>

      {/* Footer Info - Bottom Left */}
      <div className="absolute bottom-4 left-4 text-xs text-slate-500 space-y-1">
        <p>Arrow keys: Navigate | Left/Right: Switch panels</p>
        <p>F1: Search | F2: Settings</p>
      </div>
    </div>
  );
}
