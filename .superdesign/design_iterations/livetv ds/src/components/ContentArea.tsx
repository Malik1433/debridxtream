/**
 * Content Area Component
 * Main central section displaying the list of TV channels
 * Optimized for Android TV remote navigation with D-pad and Enter key support
 */

import React, { useState, useEffect } from 'react';
import { ChannelCard } from './ChannelCard';
import { Show } from '../data/channels';

interface ContentAreaProps {
  shows: Show[];
  selectedShow?: Show;
  onSelectShow?: (show: Show) => void;
}

export function ContentArea({ shows, selectedShow, onSelectShow }: ContentAreaProps) {
  const [focusedIndex, setFocusedIndex] = useState<number>(0);
  const containerRef = React.useRef<HTMLDivElement>(null);

  // TV Remote D-pad navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setFocusedIndex((prev) => {
          const newIndex = prev < shows.length - 1 ? prev + 1 : prev;
          const element = document.getElementById(`channel-${shows[newIndex].id}`);
          element?.focus();
          return newIndex;
        });
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setFocusedIndex((prev) => {
          const newIndex = prev > 0 ? prev - 1 : 0;
          const element = document.getElementById(`channel-${shows[newIndex].id}`);
          element?.focus();
          return newIndex;
        });
      } else if (e.key === 'Enter') {
        e.preventDefault();
        onSelectShow?.(shows[focusedIndex]);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [shows, focusedIndex, onSelectShow]);

  return (
    <div className="flex-1 overflow-hidden bg-gradient-to-b from-slate-900/50 via-slate-900/30 to-slate-950 flex flex-col">
      {/* Header Section */}
      <div className="px-6 py-6 border-b border-slate-800/50">
        <h2 className="text-2xl font-bold text-white">Live Channels</h2>
        <p className="text-slate-400 text-sm mt-2">Use UP/DOWN arrows to select • Press ENTER to play</p>
      </div>

      {/* Channels List - Scrollable */}
      <div
        ref={containerRef}
        className="flex-1 overflow-y-auto px-5 py-5 space-y-4"
      >
        {shows.map((show, index) => (
          <div
            id={`channel-${show.id}`}
            key={show.id}
            onClick={() => {
              onSelectShow?.(show);
              setFocusedIndex(index);
            }}
            className="focus:outline-none"
            tabIndex={0}
          >
            <ChannelCard
              show={show}
              onSelect={onSelectShow}
              isFocused={focusedIndex === index}
            />
          </div>
        ))}
      </div>
    </div>
  );
}
