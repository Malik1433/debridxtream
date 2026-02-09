/**
 * Header Component
 * Top bar showing time, weather, search, and user profile
 * Displays current time, temperature, search icon, and profile avatar
 */

import React, { useState, useEffect } from 'react';
import { Cloud, Search, User } from 'lucide-react';

interface HeaderProps {
  onSearch?: () => void;
}

export function Header({ onSearch }: HeaderProps) {
  const [time, setTime] = useState<string>('');

  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      const hours = String(now.getHours()).padStart(2, '0');
      const minutes = String(now.getMinutes()).padStart(2, '0');
      setTime(`${hours}:${minutes}`);
    };

    updateTime();
    const interval = setInterval(updateTime, 1000);
    return () => clearInterval(interval);
  }, []);

  return (
    <header className="h-24 bg-gradient-to-b from-slate-900/80 via-slate-900/40 to-transparent border-b border-slate-800/50 backdrop-blur-sm flex items-center justify-between px-8">
      {/* Left: Live TV's Title */}
      <div className="flex-1">
        <h1 className="text-3xl font-bold text-white tracking-tight">Live TV's</h1>
      </div>

      {/* Center: Empty space */}
      <div className="flex-1"></div>

      {/* Right: Time, Weather, Search, Profile */}
      <div className="flex-1 flex items-center justify-end gap-6">
        {/* Time */}
        <div className="text-white font-semibold text-lg">
          {time || '00:00'}
        </div>

        {/* Weather */}
        <button className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800/50 hover:bg-slate-700/50 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500">
          <Cloud size={18} className="text-slate-300" strokeWidth={1.5} />
          <span className="text-slate-300 text-sm">24°</span>
        </button>

        {/* Search */}
        <button
          onClick={onSearch}
          className="w-10 h-10 rounded-full bg-slate-800 hover:bg-slate-700 flex items-center justify-center transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 hover:ring-2 hover:ring-blue-400"
        >
          <Search size={20} className="text-slate-300" strokeWidth={1.5} />
        </button>

        {/* Profile Avatar */}
        <button className="w-12 h-12 rounded-full bg-gradient-to-br from-red-500 to-pink-600 flex items-center justify-center transition-all hover:ring-2 hover:ring-red-400 focus:outline-none focus:ring-2 focus:ring-red-500 overflow-hidden shadow-lg">
          <div className="w-full h-full flex items-center justify-center bg-gradient-to-br from-red-500 via-pink-500 to-red-600">
            <User size={20} className="text-white" strokeWidth={1.5} />
          </div>
        </button>
      </div>
    </header>
  );
}
