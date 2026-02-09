/**
 * Player Panel Component
 * Right side panel showing the currently selected show with video preview
 * Optimized for Android TV with large controls and clear information display
 */

import React from 'react';
import { Play, Volume2, Settings } from 'lucide-react';
import { Show } from '../data/channels';

interface PlayerPanelProps {
  show?: Show;
}

export function PlayerPanel({ show }: PlayerPanelProps) {
  if (!show) {
    return (
      <div className="flex-1 bg-gradient-to-br from-slate-900 via-slate-900/80 to-slate-950 flex flex-col items-center justify-center text-slate-400">
        <div className="text-center">
          <p className="text-3xl font-medium">Select a channel to preview</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 bg-gradient-to-b from-slate-900 via-slate-900/90 to-slate-950 flex flex-col overflow-hidden">
      {/* Video Preview - Main Area */}
      <div className="relative flex-1 bg-black overflow-hidden group">
        {show.thumbnail && (
          <img
            src={show.thumbnail}
            alt={show.currentlyPlaying}
            className="w-full h-full object-cover opacity-60 group-hover:opacity-75 transition-opacity"
          />
        )}

        {/* Dark overlay */}
        <div className="absolute inset-0 bg-gradient-to-t from-black via-black/60 to-transparent"></div>

        {/* Play Button Overlay - TV Friendly Size */}
        <div className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
          <button className="w-32 h-32 rounded-full bg-blue-500 hover:bg-blue-600 flex items-center justify-center transition-all shadow-2xl hover:scale-110 hover:shadow-blue-500/50">
            <svg className="w-16 h-16 text-white ml-2" fill="currentColor" viewBox="0 0 24 24">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
          </button>
        </div>

        {/* Status Info - Top Right */}
        <div className="absolute top-6 right-6 text-white text-lg font-bold bg-black/70 px-6 py-3 rounded-xl backdrop-blur">
          Now Playing
        </div>

        {/* Show Info - Bottom */}
        <div className="absolute bottom-0 left-0 right-0 p-8 text-white bg-gradient-to-t from-black via-black/80 to-transparent">
          <h2 className="text-4xl font-bold mb-4 tracking-tight">{show.currentlyPlaying || show.name}</h2>
          {show.description && (
            <p className="text-slate-300 text-lg leading-relaxed line-clamp-3">
              {show.description}
            </p>
          )}
        </div>
      </div>

      {/* Progress Bar - Larger for TV */}
      {show.progress !== undefined && (
        <div className="px-8 py-4 bg-slate-800/60 border-y border-slate-700">
          <div className="flex items-center gap-4">
            <span className="text-base text-slate-400 font-medium min-w-fit">01:52:37</span>
            <div className="flex-1 h-3 bg-slate-700 rounded-full overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-blue-500 to-blue-400 rounded-full transition-all"
                style={{ width: `${show.progress}%` }}
              ></div>
            </div>
            <span className="text-base text-slate-400 font-medium min-w-fit">02:10:46</span>
          </div>
        </div>
      )}

      {/* Controls - TV Optimized */}
      <div className="px-8 py-6 bg-slate-800/40 backdrop-blur border-t border-slate-700 flex items-center justify-between">
        <div className="flex items-center gap-5">
          <button className="w-16 h-16 rounded-full bg-blue-600 hover:bg-blue-700 flex items-center justify-center transition-all hover:shadow-2xl hover:shadow-blue-600/50 focus:outline-none focus:ring-4 focus:ring-blue-400">
            <Play size={28} className="text-white ml-1" strokeWidth={1.5} />
          </button>
          <button className="w-16 h-16 rounded-full bg-slate-700 hover:bg-slate-600 flex items-center justify-center transition-all hover:shadow-2xl focus:outline-none focus:ring-4 focus:ring-blue-400">
            <Volume2 size={28} className="text-white" strokeWidth={1.5} />
          </button>
        </div>
        <button className="w-16 h-16 rounded-full bg-slate-700 hover:bg-slate-600 flex items-center justify-center transition-all hover:shadow-2xl focus:outline-none focus:ring-4 focus:ring-blue-400">
          <Settings size={28} className="text-white" strokeWidth={1.5} />
        </button>
      </div>
    </div>
  );
}
