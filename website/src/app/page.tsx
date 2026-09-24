"use client";

import React, { useState } from "react";
import Link from "next/link";
import Image from "next/image";
import {
  Download,
  Tv,
  Zap,
  ArrowRight,
  ShieldCheck,
  Cpu,
  Layers,
  Sparkles,
  ChevronDown,
  Check,
  Copy,
} from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";
import DownloaderCard from "@/components/DownloaderCard";
import DeviceMockup from "@/components/DeviceMockup";
import FeaturesGrid from "@/components/FeaturesGrid";
import ComparisonTable from "@/components/ComparisonTable";
import PricingCards from "@/components/PricingCards";
import ResellerCalculator from "@/components/ResellerCalculator";

export default function HomePage() {
  const [openFaq, setOpenFaq] = useState<number | null>(0);
  const [copiedCode, setCopiedCode] = useState(false);

  const handleCopyCode = () => {
    navigator.clipboard.writeText(APP_CONFIG.downloaderCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  const faqs = [
    {
      q: `How do I install DX Play on Amazon FireStick?`,
      a: `Simply open the Downloader app on your Fire TV, type the official 7-digit code "${APP_CONFIG.downloaderCode}" into the URL field, and click GO. The official APK will download and prompt you to install in seconds.`,
    },
    {
      q: `Why is DX Play significantly faster than TiviMate or IPTV Smarters?`,
      a: `Traditional players make blocking HTTP network requests every time you navigate to a movie or series. DX Play uses a "Database-First" architecture powered by Android Room DB and Optimistic Skeleton UI. The interface responds in 0ms (<50ms) while background workers silently sync data.`,
    },
    {
      q: `What is the Debrid Torrent Proxy feature?`,
      a: `DX Play integrates natively with Debrid proxies (StremThru/Debridio). This allows you to stream massive 4K HDR, Dolby Vision, and REMUX movie files directly from high-speed cached torrent cloud servers with zero buffering and no need for local storage.`,
    },
    {
      q: `How does TV Device Linking work?`,
      a: `When you open DX Play on your TV, it displays a unique activation code (e.g. X8K2-9PLA) or QR code. You can simply scan it or go to /link from your smartphone or laptop to connect your playlists and activate your device immediately without typing long URLs on a remote.`,
    },
    {
      q: `Can I use my existing Xtream Codes playlist?`,
      a: `Yes! DX Play fully supports any standard Xtream Codes API server (Server URL, Username, and Password), as well as standard M3U playlists and multi-DNS configurations. Users simply connect their own provider account.`,
    },
  ];

  return (
    <div className="flex flex-col min-h-screen">
      {/* HERO SECTION */}
      <section className="relative pt-12 pb-20 lg:pt-20 lg:pb-32 overflow-hidden">
        {/* Ambient background glows */}
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[1000px] h-[550px] bg-gradient-to-b from-[#EC3013]/25 via-red-950/15 to-transparent rounded-full blur-[140px] pointer-events-none" />

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
          <div className="text-center max-w-4xl mx-auto space-y-6">
            {/* Top Pill */}
            <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-white/5 border border-white/10 text-stone-200 text-xs font-semibold backdrop-blur-md">
              <span className="flex h-2 w-2 rounded-full bg-[#EC3013] animate-pulse" />
              <span>Version {APP_CONFIG.version} Released</span>
              <span className="text-stone-500">•</span>
              <span className="text-[#EC3013] font-bold">FireStick Downloader Code: {APP_CONFIG.downloaderCode}</span>
            </div>

            {/* Main Headline */}
            <h1 className="text-4xl sm:text-6xl lg:text-7xl font-black text-white tracking-tight leading-[1.08]">
              The Fastest Player Built For{" "}
              <span className="text-transparent bg-clip-text bg-gradient-to-r from-[#FF6B4A] via-[#EC3013] to-[#FF451A]">
                FireStick & Android TV
              </span>
            </h1>

            {/* Subtitle */}
            <p className="text-stone-400 text-base sm:text-xl max-w-2xl mx-auto leading-relaxed">
              Experience instant 0ms navigation, Room DB local-first caching, and high-bitrate 4K HDR Debrid streaming with zero buffering.
            </p>

            {/* Quick Actions */}
            <div className="flex flex-wrap items-center justify-center gap-4 pt-4">
              <a
                href={APP_CONFIG.downloadUrl}
                download
                className="btn-dx flex items-center gap-2.5 px-7 py-4 rounded-2xl text-sm font-bold shadow-[0_0_35px_rgba(236,48,19,0.4)]"
              >
                <Download className="w-5 h-5" />
                <span>Download APK ({APP_CONFIG.apkSize})</span>
              </a>

              <button
                onClick={handleCopyCode}
                className="flex items-center gap-2.5 px-6 py-4 rounded-2xl text-sm font-mono font-bold bg-[#141211] hover:bg-[#1A1817] text-white border border-white/15 hover:border-[#EC3013]/60 transition-all shadow-lg group"
              >
                <span className="text-stone-400 font-sans text-xs">Downloader:</span>
                <span className="text-[#EC3013] text-base">{APP_CONFIG.downloaderCode}</span>
                {copiedCode ? (
                  <Check className="w-4 h-4 text-emerald-400" />
                ) : (
                  <Copy className="w-4 h-4 text-stone-400 group-hover:text-white" />
                )}
              </button>

              <Link
                href="/link"
                className="flex items-center gap-2 px-6 py-4 rounded-2xl text-sm font-bold bg-white/5 hover:bg-white/10 text-stone-200 border border-white/10 transition-colors"
              >
                <Tv className="w-4 h-4 text-[#EC3013]" />
                <span>Pair TV Code</span>
              </Link>
            </div>

            {/* Trust Metrics Pill Row */}
            <div className="pt-8 flex flex-wrap items-center justify-center gap-6 text-xs text-stone-400 font-mono">
              <div className="flex items-center gap-2">
                <Zap className="w-4 h-4 text-[#EC3013]" />
                <span>0ms Optimistic Latency</span>
              </div>
              <span className="text-stone-700">•</span>
              <div className="flex items-center gap-2">
                <Cpu className="w-4 h-4 text-[#EC3013]" />
                <span>Media3 ExoPlayer</span>
              </div>
              <span className="text-stone-700">•</span>
              <div className="flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-emerald-400" />
                <span>Automated License System</span>
              </div>
            </div>
          </div>

          {/* INTERACTIVE TV DEVICE MOCKUP */}
          <div className="mt-16 lg:mt-24">
            <DeviceMockup />
          </div>
        </div>
      </section>

      {/* DOWNLOADER PROMINENT BANNER SECTION */}
      <section className="py-12 relative">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <DownloaderCard />
        </div>
      </section>

      {/* CORE FEATURES GRID */}
      <FeaturesGrid />

      {/* SIDE BY SIDE COMPARISON */}
      <ComparisonTable />

      {/* PRICING PLANS */}
      <PricingCards />

      {/* RESELLER HIGHLIGHT & CALCULATOR */}
      <section className="py-20 relative">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-3xl mx-auto mb-14 space-y-4">
            <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
              <Layers className="w-3.5 h-3.5" />
              Reseller & Wholesaler Program
            </div>
            <h2 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
              Start Your Own Software Reselling Business
            </h2>
            <p className="text-stone-400 text-base sm:text-lg">
              Deliver the fastest player on the market to your clients. Buy license credits at wholesale rates, activate customer TV devices instantly, and keep 100% of your retail margins.
            </p>
          </div>

          <ResellerCalculator />
        </div>
      </section>

      {/* FAQ SECTION */}
      <section className="py-20 relative border-t border-white/[0.08]">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-14 space-y-3">
            <h2 className="text-3xl sm:text-4xl font-black text-white tracking-tight">
              Frequently Asked Questions
            </h2>
            <p className="text-stone-400 text-sm sm:text-base">
              Everything you need to know about DX Play, FireStick installation, and Debrid integration.
            </p>
          </div>

          <div className="space-y-4">
            {faqs.map((faq, index) => {
              const isOpen = openFaq === index;
              return (
                <div
                  key={index}
                  className="rounded-2xl border border-white/10 bg-[#12100F] overflow-hidden transition-colors"
                >
                  <button
                    onClick={() => setOpenFaq(isOpen ? null : index)}
                    className="w-full flex items-center justify-between p-5 text-left font-bold text-base text-white hover:text-[#EC3013] transition-colors"
                  >
                    <span>{faq.q}</span>
                    <ChevronDown
                      className={`w-5 h-5 text-stone-400 transition-transform duration-200 ${
                        isOpen ? "rotate-180 text-[#EC3013]" : ""
                      }`}
                    />
                  </button>

                  {isOpen && (
                    <div className="px-5 pb-5 pt-1 text-sm text-stone-400 leading-relaxed border-t border-white/5">
                      {faq.a}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </section>
    </div>
  );
}
