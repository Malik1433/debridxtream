/**
 * Search Page Component
 * Search and filter channels/shows with TV-optimized interface
 * Matches the Live TV design language with focus-based navigation
 */

import React, { useState, useMemo } from 'react';
import { Search, Filter, X, Star } from 'lucide-react';
import { Show } from '../data/channels';

interface SearchPageProps {
  shows: Show[];
  onBack: () => void;
  onSelectShow?: (show: Show) => void;
}

export function SearchPage({ shows, onBack, onSelectShow }: SearchPageProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFilter, setSelectedFilter] = useState<'all' | '4K' | 'HD' | 'favorite'>('all');
  const [focusedIndex, setFocusedIndex] = useState(0);
  const [isSearchActive, setIsSearchActive] = useState(true);

  const filters = [
    { id: 'all', label: 'All Channels', icon: '📺' },
    { id: '4K', label: '4K Only', icon: '🎬' },
    { id: 'HD', label: 'HD Only', icon: '📹' },
    { id: 'favorite', label: 'Favorites', icon: '⭐' },
  ] as const;

  // Filter and search results
  const filteredShows = useMemo(() => {
    return shows.filter((show) => {
      const matchesSearch =
        show.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        show.channel.toLowerCase().includes(searchQuery.toLowerCase());

      let matchesFilter = true;
      if (selectedFilter === '4K') {
        matchesFilter = show.quality.includes('4K');
      } else if (selectedFilter === 'HD') {
        matchesFilter = show.quality.includes('HD');
      } else if (selectedFilter === 'favorite') {
        matchesFilter = show.favorite === true;
      }

      return matchesSearch && matchesFilter;
    });
  }, [searchQuery, selectedFilter, shows]);

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' || e.key === 'Backspace') {
        e.preventDefault();
        onBack();
      }

      if (isSearchActive) {
        // Search input mode
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          setIsSearchActive(false);
          setFocusedIndex(0);
        }
      } else {
        // Results navigation mode
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          setFocusedIndex((prev) => (prev < filteredShows.length - 1 ? prev + 1 : prev));
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          if (focusedIndex === 0) {
            setIsSearchActive(true);
          } else {
            setFocusedIndex((prev) => Math.max(0, prev - 1));
          }
        } else if (e.key === 'Enter') {
          e.preventDefault();
          if (filteredShows[focusedIndex]) {
            onSelectShow?.(filteredShows[focusedIndex]);
          }
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isSearchActive, focusedIndex, filteredShows, onBack, onSelectShow]);

  return (
    <div className="w-full h-full bg-gradient-to-br from-slate-900 via-slate-900/90 to-slate-950 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="px-8 py-6 border-b border-slate-800 bg-slate-900/60 backdrop-blur space-y-4">
        <div className="flex items-center gap-4">
          <Search size={36} className="text-blue-400" strokeWidth={1.5} />
          <h1 className="text-4xl font-bold text-white tracking-tight">Search Channels</h1>
        </div>

        {/* Search Input */}
        <div
          className={`
            relative rounded-xl overflow-hidden transition-all duration-200
            bg-slate-800/60 border-2 border-slate-700/60
            flex items-center px-6 py-4
            ${isSearchActive ? 'ring-4 ring-yellow-400 shadow-lg shadow-yellow-400/50 border-yellow-400' : 'hover:border-blue-400/60'}
          `}
        >
          <Search size={24} className="text-slate-400 mr-4 flex-shrink-0" strokeWidth={1.5} />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => {
              setSearchQuery(e.target.value);
              setFocusedIndex(0);
            }}
            onFocus={() => setIsSearchActive(true)}
            placeholder="Type channel name..."
            className="flex-1 bg-transparent text-white text-xl focus:outline-none placeholder-slate-500"
          />
          {searchQuery && (
            <button
              onClick={() => {
                setSearchQuery('');
                setFocusedIndex(0);
              }}
              className="ml-4 p-2 hover:bg-slate-700 rounded-lg transition-colors"
            >
              <X size={24} className="text-slate-400" strokeWidth={1.5} />
            </button>
          )}
        </div>

        {/* Filters */}
        <div className="flex gap-3 overflow-x-auto pb-2">
          {filters.map((filter) => (
            <button
              key={filter.id}
              onClick={() => {
                setSelectedFilter(filter.id);
                setFocusedIndex(0);
              }}
              className={`
                px-6 py-3 rounded-lg font-bold text-lg whitespace-nowrap transition-all flex items-center gap-2
                ${
                  selectedFilter === filter.id
                    ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/50 ring-2 ring-blue-400'
                    : 'bg-slate-800/60 text-slate-200 hover:bg-slate-800 hover:text-white'
                }
              `}
            >
              <span className="text-xl">{filter.icon}</span>
              {filter.label}
            </button>
          ))}
        </div>
      </div>

      {/* Results */}
      <div className="flex-1 overflow-y-auto px-8 py-6">
        {filteredShows.length === 0 ? (
          <div className="h-full flex items-center justify-center">
            <div className="text-center">
              <Search size={64} className="text-slate-600 mx-auto mb-4" strokeWidth={1.5} />
              <p className="text-slate-400 text-2xl font-medium">
                {searchQuery ? 'No channels found' : 'Start searching for channels'}
              </p>
              <p className="text-slate-500 text-lg mt-2">
                {searchQuery ? `Try a different search term` : 'Type in the search box above'}
              </p>
            </div>
          </div>
        ) : (
          <div className="space-y-4 max-w-5xl">
            <p className="text-slate-400 text-lg mb-6">
              {filteredShows.length} channel{filteredShows.length !== 1 ? 's' : ''} found
            </p>

            {filteredShows.map((show, index) => (
              <div
                key={show.id}
                onClick={() => onSelectShow?.(show)}
                className={`
                  relative group cursor-pointer rounded-2xl overflow-hidden transition-all duration-200
                  bg-slate-800/50 backdrop-blur-sm border-2 border-slate-700/60
                  p-6 flex items-center justify-between h-36
                  ${
                    focusedIndex === index && !isSearchActive
                      ? 'ring-4 ring-yellow-400 shadow-2xl shadow-yellow-400/50 bg-slate-800/80 border-yellow-400'
                      : 'hover:bg-slate-800/70 hover:ring-2 hover:ring-blue-400/60'
                  }
                `}
              >
                {/* Left Section - Icon/Logo + Channel Name */}
                <div className="flex-1 flex items-center gap-6 min-w-0">
                  {/* Icon - Larger for TV */}
                  <div className="text-6xl flex-shrink-0">📺</div>

                  {/* Channel Info */}
                  <div className="flex-1 min-w-0">
                    <h3 className="text-white font-bold text-2xl truncate">{show.name}</h3>
                    <p className="text-slate-400 text-lg truncate mt-1">{show.channel}</p>
                    <p className="text-slate-500 text-base truncate mt-1">{show.views} Views</p>
                  </div>
                </div>

                {/* Right Section - Quality Badges + Star */}
                <div className="flex items-center gap-4 flex-shrink-0 ml-4">
                  {/* Quality Badges */}
                  <div className="flex gap-3 items-center">
                    {show.quality.map((q) => (
                      <span
                        key={q}
                        className={`
                          px-4 py-2 rounded-lg text-base font-bold whitespace-nowrap
                          ${
                            q === '4K'
                              ? 'bg-red-900/80 text-red-100'
                              : q === 'HD'
                                ? 'bg-blue-900/80 text-blue-100'
                                : q === 'EPG'
                                  ? 'bg-slate-700/80 text-slate-100'
                                  : 'bg-yellow-900/80 text-yellow-100'
                          }
                        `}
                      >
                        {q}
                      </span>
                    ))}
                  </div>

                  {/* Favorite Star */}
                  {show.favorite && (
                    <Star
                      size={28}
                      className="fill-yellow-400 text-yellow-400 flex-shrink-0 drop-shadow-lg"
                      strokeWidth={2}
                    />
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Footer Info */}
      <div className="px-8 py-4 bg-slate-900/60 border-t border-slate-800 text-center text-sm text-slate-400">
        <p>Use UP/DOWN arrows to navigate • ENTER to select • ESC to go back</p>
      </div>
    </div>
  );
}
