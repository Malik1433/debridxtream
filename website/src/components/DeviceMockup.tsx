"use client";

import React, { useState } from "react";
import Image from "next/image";
import { Play, Radio, Film, Tv, Sparkles, Shield, Zap, Volume2, Globe } from "lucide-react";

export default function DeviceMockup() {
  const [activeTab, setActiveTab] = useState<"live" | "movies" | "series">("movies");
  const [selectedSource, setSelectedSource] = useState(0);

  const sources = [
    { name: "Debrid 4K HDR • REMUX 65.4 GB", quality: "4K REMUX", speed: "120 Mbps", tag: "Fastest" },
    { name: "Debrid 4K HEVC • Atmos 24.1 GB", quality: "4K HDR", speed: "85 Mbps", tag: "Recommended" },
    { name: "Xtream Live FHD • 60 FPS", quality: "1080p 60fps", speed: "18 Mbps", tag: "Low Buffer" },
  ];

  return (
    <div className="relative mx-auto max-w-5xl w-full">
      {/* Ambient television back-glow */}
      <div className="absolute -inset-4 bg-gradient-to-r from-[#EC3013]/30 via-red-900/20 to-orange-600/20 rounded-[2.5rem] blur-2xl opacity-70 pointer-events-none" />

      {/* Television Device Frame */}
      <div className="relative rounded-[2rem] border-4 border-[#252220] bg-[#0E0C0B] shadow-[0_25px_60px_rgba(0,0,0,0.9)] overflow-hidden">
        {/* Top TV Bezel Bar */}
        <div className="h-6 bg-[#181514] border-b border-white/5 flex items-center justify-between px-4">
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full bg-red-500/80" />
            <div className="w-2 h-2 rounded-full bg-yellow-500/80" />
            <div className="w-2 h-2 rounded-full bg-green-500/80" />
          </div>
          <div className="text-[10px] font-mono uppercase tracking-widest text-stone-500 flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
            DX Play TV • FireStick 4K Max Mode
          </div>
          <div className="text-[10px] font-mono text-stone-500">
            0ms Latency
          </div>
        </div>

        {/* TV Screen Interface */}
        <div className="p-4 sm:p-6 bg-gradient-to-br from-[#12100F] to-[#0A0908] min-h-[460px] flex flex-col justify-between">
          {/* Top Header inside TV */}
          <div className="flex items-center justify-between border-b border-white/10 pb-4">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-lg bg-black/60 border border-white/10 p-0.5 flex items-center justify-center">
                <Image
                  src="/brand/logo-icon-cinema.png"
                  alt="DX"
                  width={26}
                  height={26}
                  className="object-contain"
                />
              </div>
              <div className="flex items-center gap-1">
                {(["movies", "live", "series"] as const).map((tab) => (
                  <button
                    key={tab}
                    onClick={() => setActiveTab(tab)}
                    className={`px-3 py-1 rounded-md text-xs font-bold uppercase tracking-wider transition-colors ${
                      activeTab === tab
                        ? "bg-[#EC3013] text-white shadow-md shadow-[#EC3013]/30"
                        : "text-stone-400 hover:text-white hover:bg-white/5"
                    }`}
                  >
                    {tab === "movies" ? "Movies & VOD" : tab === "live" ? "Live TV" : "Series V2"}
                  </button>
                ))}
              </div>
            </div>

            <div className="hidden sm:flex items-center gap-2">
              <span className="px-2 py-0.5 text-[10px] font-mono bg-emerald-500/20 text-emerald-400 rounded border border-emerald-500/30 font-bold">
                ROOM DB SYNCED
              </span>
              <span className="px-2 py-0.5 text-[10px] font-mono bg-amber-500/20 text-amber-300 rounded border border-amber-500/30 font-bold">
                DEBRID ACTIVE
              </span>
            </div>
          </div>

          {/* Featured Content Area */}
          <div className="my-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-center">
            {/* Left Movie Info */}
            <div className="lg:col-span-7 space-y-3">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded text-[10px] font-extrabold bg-[#EC3013] text-white uppercase tracking-wider">
                  4K ULTRA HD
                </span>
                <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-white/10 text-stone-300">
                  IMAX ENHANCED
                </span>
                <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-white/10 text-stone-300">
                  DOLBY VISION
                </span>
              </div>

              <h2 className="text-2xl sm:text-4xl font-black text-white tracking-tight leading-none">
                DUNE: PART TWO
              </h2>

              <p className="text-xs sm:text-sm text-stone-400 line-clamp-2 leading-relaxed">
                Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family.
              </p>

              {/* Stream Quality Selector inside Player */}
              <div className="pt-2">
                <div className="text-[11px] font-semibold text-stone-400 mb-2 uppercase tracking-wider flex items-center gap-1.5">
                  <Zap className="w-3 h-3 text-[#EC3013]" />
                  <span>Instant Multi-Source Debrid Streams:</span>
                </div>
                <div className="space-y-1.5">
                  {sources.map((src, i) => (
                    <button
                      key={i}
                      onClick={() => setSelectedSource(i)}
                      className={`w-full flex items-center justify-between p-2 rounded-lg text-xs transition-all border ${
                        selectedSource === i
                          ? "bg-[#EC3013]/15 border-[#EC3013] text-white shadow-sm"
                          : "bg-black/40 border-white/5 text-stone-400 hover:border-white/20"
                      }`}
                    >
                      <div className="flex items-center gap-2 font-medium">
                        <Play className={`w-3 h-3 ${selectedSource === i ? "text-[#EC3013] fill-[#EC3013]" : "text-stone-500"}`} />
                        <span>{src.name}</span>
                      </div>
                      <div className="flex items-center gap-2 text-[10px] font-mono">
                        <span className="text-stone-400">{src.speed}</span>
                        <span className="px-1.5 py-0.2 rounded bg-white/10 text-stone-200">
                          {src.tag}
                        </span>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Right Screen Cards Preview */}
            <div className="lg:col-span-5 grid grid-cols-2 gap-3">
              <div className="rounded-xl overflow-hidden border border-white/10 bg-black/60 p-3 space-y-2 relative group">
                <div className="h-28 rounded-lg bg-gradient-to-t from-black via-stone-800 to-stone-700 flex items-end p-2 relative overflow-hidden">
                  <div className="absolute inset-0 bg-[#EC3013]/10" />
                  <span className="relative z-10 text-[11px] font-bold text-white">
                    Gladiator II (2024)
                  </span>
                </div>
                <div className="flex justify-between items-center text-[10px] text-stone-400">
                  <span>Debrid 4K HDR</span>
                  <span className="text-emerald-400 font-bold">Cached</span>
                </div>
              </div>

              <div className="rounded-xl overflow-hidden border border-white/10 bg-black/60 p-3 space-y-2 relative group">
                <div className="h-28 rounded-lg bg-gradient-to-t from-black via-stone-800 to-stone-700 flex items-end p-2 relative overflow-hidden">
                  <div className="absolute inset-0 bg-blue-500/10" />
                  <span className="relative z-10 text-[11px] font-bold text-white">
                    Sky Sports Main Event
                  </span>
                </div>
                <div className="flex justify-between items-center text-[10px] text-stone-400">
                  <span>Live FHD 60FPS</span>
                  <span className="text-red-400 font-bold animate-pulse">● LIVE</span>
                </div>
              </div>
            </div>
          </div>

          {/* Bottom D-Pad Remote Legend */}
          <div className="pt-3 border-t border-white/10 flex flex-wrap items-center justify-between text-[11px] font-mono text-stone-400 gap-2">
            <div className="flex items-center gap-3">
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded bg-white/10 text-white font-bold">OK</kbd> Play Stream
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded bg-white/10 text-white font-bold">UP / DOWN</kbd> Zap Channel
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded bg-white/10 text-white font-bold">MENU</kbd> Subtitles & Audio
              </span>
            </div>
            <div className="text-stone-500 font-bold">
              Leanback D-Pad Remote Optimized
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
