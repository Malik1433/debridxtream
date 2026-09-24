import React from "react";
import { Zap, ShieldCheck, Database, Tv, Cpu, Cloud, Sparkles, Layers } from "lucide-react";

export default function FeaturesGrid() {
  const features = [
    {
      icon: Zap,
      title: "0ms Navigation Latency",
      badge: "Local-First Room DB",
      description:
        "Unlike ordinary IPTV apps that freeze or spin while fetching server data, DX Play indexes everything locally. Series and movie details render instantaneously (<50ms).",
    },
    {
      icon: Sparkles,
      title: "Debrid Torrent Proxy Engine",
      badge: "4K REMUX & Atmos",
      description:
        "Directly stream 4K HDR cached torrent files from Debrid proxies (StremThru/Debridio). Uncompressed bitrates with zero buffering and no need for local downloads.",
    },
    {
      icon: Tv,
      title: "Built for FireStick & TV Remote",
      badge: "10-Foot D-Pad UI",
      description:
        "Engineered strictly for Android TV Leanback standards. High-contrast focus states, fast channel zapping, and intuitive D-Pad shortcuts without touch screen quirks.",
    },
    {
      icon: Cloud,
      title: "Cloud Companion Device Pairing",
      badge: "No Remote Typing",
      description:
        "Never type long server URLs or passwords with a remote again. Simply open the web companion on your phone, type the 4-digit TV code, and pair instantly.",
    },
    {
      icon: Cpu,
      title: "Media3 ExoPlayer Acceleration",
      badge: "Hardware Decoding",
      description:
        "Powered by AndroidX Media3 with auto frame-rate matching, hardware accelerated HEVC/AV1 playback, Dolby Atmos passthrough, and custom subtitle styles.",
    },
    {
      icon: Layers,
      title: "Software License Reseller Portal",
      badge: "App Resellers",
      description:
        "Comprehensive reseller ecosystem with wholesale license credits, instant TV device activation via companion code, and full sub-reseller team management.",
    },
  ];

  return (
    <section id="features" className="py-24 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-16 space-y-4">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/30 text-[#EC3013] text-xs font-extrabold uppercase tracking-wider">
            <Zap className="w-3.5 h-3.5 fill-[#EC3013]" />
            Engineered For Pure Speed
          </div>
          <h2 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
            Why DX Play Leaves Standard Players in the Dust
          </h2>
          <p className="text-stone-400 text-base sm:text-lg">
            Standard IPTV apps crash with large playlists and struggle with 4K streams. DX Play was rebuilt from the ground up using database-first engineering.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 sm:gap-8">
          {features.map((feat, index) => {
            const Icon = feat.icon;
            return (
              <div
                key={index}
                className="glass-panel glass-panel-hover rounded-3xl p-8 relative flex flex-col justify-between group overflow-hidden"
              >
                <div className="absolute top-0 right-0 w-32 h-32 bg-[#EC3013]/10 rounded-full blur-2xl group-hover:bg-[#EC3013]/20 transition-all pointer-events-none" />

                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <div className="w-12 h-12 rounded-2xl bg-white/5 border border-white/10 flex items-center justify-center group-hover:border-[#EC3013]/50 group-hover:bg-[#EC3013]/10 transition-all">
                      <Icon className="w-6 h-6 text-[#EC3013]" />
                    </div>
                    <span className="px-2.5 py-1 rounded-md text-[10px] font-mono font-bold uppercase tracking-wider bg-white/5 text-stone-300 border border-white/5">
                      {feat.badge}
                    </span>
                  </div>

                  <h3 className="text-xl font-bold text-white tracking-tight pt-2">
                    {feat.title}
                  </h3>

                  <p className="text-stone-400 text-sm leading-relaxed">
                    {feat.description}
                  </p>
                </div>

                <div className="pt-6 mt-6 border-t border-white/[0.06] flex items-center text-xs font-semibold text-[#EC3013] opacity-0 group-hover:opacity-100 transition-opacity">
                  <span>Learn more in documentation &rarr;</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
