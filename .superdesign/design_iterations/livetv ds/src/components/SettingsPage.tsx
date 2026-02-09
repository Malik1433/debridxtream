/**
 * Settings Page Component
 * Video quality, language, audio settings with TV-optimized interface
 * Matches the Live TV design language with focus-based navigation
 */

import React, { useState } from 'react';
import { Volume2, Settings, ChevronRight, Radio } from 'lucide-react';

interface SettingsItem {
  id: string;
  label: string;
  value: string;
  options: Array<{ label: string; value: string }>;
  icon: React.ReactNode;
}

export function SettingsPage({ onBack }: { onBack: () => void }) {
  const [focusedIndex, setFocusedIndex] = useState(0);
  const [settings, setSettings] = useState({
    videoQuality: '1080p',
    language: 'English',
    audioFormat: 'Stereo',
    subtitles: 'On',
    brightness: '100',
    volume: '80',
  });

  const settingsItems: SettingsItem[] = [
    {
      id: 'videoQuality',
      label: 'Video Quality',
      value: settings.videoQuality,
      icon: <Radio size={28} className="text-blue-400" strokeWidth={1.5} />,
      options: [
        { label: '480p', value: '480p' },
        { label: '720p HD', value: '720p' },
        { label: '1080p Full HD', value: '1080p' },
        { label: '4K Ultra HD', value: '4K' },
      ],
    },
    {
      id: 'language',
      label: 'Language',
      value: settings.language,
      icon: <Radio size={28} className="text-purple-400" strokeWidth={1.5} />,
      options: [
        { label: 'English', value: 'English' },
        { label: 'Spanish', value: 'Spanish' },
        { label: 'French', value: 'French' },
        { label: 'Portuguese', value: 'Portuguese' },
      ],
    },
    {
      id: 'audioFormat',
      label: 'Audio Format',
      value: settings.audioFormat,
      icon: <Volume2 size={28} className="text-green-400" strokeWidth={1.5} />,
      options: [
        { label: 'Stereo', value: 'Stereo' },
        { label: 'Surround', value: 'Surround' },
        { label: 'Dolby Atmos', value: 'Dolby Atmos' },
      ],
    },
    {
      id: 'subtitles',
      label: 'Subtitles',
      value: settings.subtitles,
      icon: <Radio size={28} className="text-yellow-400" strokeWidth={1.5} />,
      options: [
        { label: 'Off', value: 'Off' },
        { label: 'On', value: 'On' },
        { label: 'Auto', value: 'Auto' },
      ],
    },
  ];

  const handleSelect = (settingId: string, newValue: string) => {
    setSettings((prev) => ({
      ...prev,
      [settingId]: newValue,
    }));
  };

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setFocusedIndex((prev) => (prev < settingsItems.length - 1 ? prev + 1 : prev));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setFocusedIndex((prev) => (prev > 0 ? prev - 1 : 0));
      } else if (e.key === 'Escape' || e.key === 'Backspace') {
        e.preventDefault();
        onBack();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onBack, settingsItems.length]);

  return (
    <div className="w-full h-full bg-gradient-to-br from-slate-900 via-slate-900/90 to-slate-950 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="px-8 py-6 border-b border-slate-800 bg-slate-900/60 backdrop-blur">
        <div className="flex items-center gap-4 mb-2">
          <Settings size={36} className="text-blue-400" strokeWidth={1.5} />
          <h1 className="text-4xl font-bold text-white tracking-tight">Settings</h1>
        </div>
        <p className="text-slate-400 text-base ml-14">Configure your viewing preferences</p>
      </div>

      {/* Settings List */}
      <div className="flex-1 overflow-y-auto px-8 py-6">
        <div className="space-y-4 max-w-4xl">
          {settingsItems.map((setting, index) => (
            <div
              key={setting.id}
              className={`
                relative rounded-2xl overflow-hidden transition-all duration-200
                bg-slate-800/50 backdrop-blur-sm border-2 border-slate-700/60
                p-6 cursor-pointer
                ${
                  focusedIndex === index
                    ? 'ring-4 ring-yellow-400 shadow-2xl shadow-yellow-400/50 bg-slate-800/80 border-yellow-400'
                    : 'hover:bg-slate-800/70 hover:ring-2 hover:ring-blue-400/60'
                }
              `}
              onClick={() => setFocusedIndex(index)}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-6 flex-1">
                  <div className="w-14 h-14 rounded-xl bg-slate-700/50 flex items-center justify-center flex-shrink-0">
                    {setting.icon}
                  </div>
                  <div className="flex-1">
                    <h3 className="text-white font-bold text-2xl">{setting.label}</h3>
                    <p className="text-slate-400 text-lg mt-1">{setting.value}</p>
                  </div>
                </div>
                <ChevronRight size={32} className="text-slate-500 flex-shrink-0" strokeWidth={1.5} />
              </div>

              {/* Options Dropdown - Show when focused */}
              {focusedIndex === index && (
                <div className="mt-6 pt-6 border-t border-slate-700 flex flex-wrap gap-3">
                  {setting.options.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => handleSelect(setting.id, option.value)}
                      className={`
                        px-6 py-3 rounded-xl font-bold text-lg transition-all
                        ${
                          settings[setting.id as keyof typeof settings] === option.value
                            ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/50 ring-2 ring-blue-400'
                            : 'bg-slate-700/60 text-slate-200 hover:bg-slate-700 hover:text-white'
                        }
                      `}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Footer Info */}
      <div className="px-8 py-4 bg-slate-900/60 border-t border-slate-800 text-center">
        <p className="text-slate-400 text-sm">Press ESC or BACK to return to Live TV</p>
      </div>
    </div>
  );
}
