/**
 * Sidebar Component
 * Left navigation panel showing country flags and channel counts
 * Optimized for Android TV remote navigation with D-pad support
 */

import React, { useState, useRef, useEffect } from 'react';
import { Country } from '../data/channels';

interface SidebarProps {
  countries: Country[];
  selectedCountry?: string;
  onSelectCountry?: (countryId: string) => void;
}

export function Sidebar({
  countries,
  selectedCountry,
  onSelectCountry,
}: SidebarProps) {
  const [focusedIndex, setFocusedIndex] = useState<number>(0);
  const containerRef = useRef<HTMLDivElement>(null);

  // TV Remote D-pad navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setFocusedIndex((prev) => {
          const newIndex = prev < countries.length - 1 ? prev + 1 : prev;
          const element = document.getElementById(`country-${countries[newIndex].id}`);
          element?.focus();
          return newIndex;
        });
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setFocusedIndex((prev) => {
          const newIndex = prev > 0 ? prev - 1 : 0;
          const element = document.getElementById(`country-${countries[newIndex].id}`);
          element?.focus();
          return newIndex;
        });
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [countries]);

  return (
    <div
      ref={containerRef}
      className="w-full h-full bg-gradient-to-b from-slate-900 via-slate-900 to-slate-950 border-r border-slate-800 flex flex-col overflow-hidden"
    >
      {/* Countries List - Full Height */}
      <div className="flex-1 overflow-y-auto px-4 py-6 space-y-4">
        {countries.map((country, index) => (
          <button
            id={`country-${country.id}`}
            key={country.id}
            onClick={() => {
              onSelectCountry?.(country.id);
              setFocusedIndex(index);
            }}
            className={`
              w-full p-6 rounded-xl transition-all duration-200
              flex flex-col items-center gap-3
              focus:outline-none
              ${
                selectedCountry === country.id
                  ? 'bg-gradient-to-b from-blue-600/50 to-blue-700/30 ring-2 ring-blue-500 shadow-lg shadow-blue-500/50'
                  : 'bg-slate-800/50 hover:bg-slate-800'
              }
              ${focusedIndex === index ? 'ring-4 ring-yellow-400 shadow-lg shadow-yellow-400/50' : ''}
            `}
          >
            <div className="text-6xl">{country.flag}</div>
            <div className="text-center">
              <p className="text-base text-white font-bold">{country.name}</p>
              <p className="text-sm text-slate-300">{country.channels} channels</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
