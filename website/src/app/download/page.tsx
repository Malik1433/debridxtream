"use client";

import React, { useState } from "react";
import Link from "next/link";
import {
  Download,
  Copy,
  Check,
  ShieldCheck,
  Cpu,
  FileCheck,
  Zap,
  ArrowRight,
  Tv,
} from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";

export default function DownloadPage() {
  const [copiedCode, setCopiedCode] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(APP_CONFIG.downloaderCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  const changelog = [
    {
      version: "v3.0.6 (Latest Stable)",
      date: "September 2026",
      items: [
        "Room DB 0ms Latency Engine: Instant optimistic Series & Movie navigation.",
        "Debrid Proxy Link Resolver: Full support for StremThru and Debridio 4K torrent streams.",
        "Generational EPG Sync: Never wipe existing favorites during daily schedule refresh.",
        "Cold Start Optimization: Boot time reduced to <1.4s on Amazon FireStick 4K.",
        "Media3 ExoPlayer: Added Dolby Atmos passthrough & custom subtitle font scaling.",
        "OTA Background Updater: Silent version checking with self-hosted APK installation.",
      ],
    },
    {
      version: "v3.0.0",
      date: "July 2026",
      items: [
        "Major architecture redesign: Total migration to AndroidX Media3.",
        "Cloud Companion: Instant QR code and 4-digit TV pairing without remote typing.",
        "Automated License Provisioning: Reseller portal integration for instant TV activations.",
      ],
    },
  ];

  return (
    <div className="py-12 sm:py-20">
      <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
        {/* Header */}
        <div className="text-center max-w-3xl mx-auto space-y-4">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <Download className="w-3.5 h-3.5" />
            Official Release Downloads
          </div>
          <h1 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
            Download DX Play for Android TV & FireStick
          </h1>
          <p className="text-stone-400 text-sm sm:text-base">
            Verified, malware-free official release. Direct download or install using the Downloader app.
          </p>
        </div>

        {/* Big Download & Code Cards Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8 items-stretch">
          {/* Card 1: Downloader App Code */}
          <div className="glass-panel rounded-3xl p-8 flex flex-col justify-between space-y-6 relative overflow-hidden border-[#EC3013]/30">
            <div className="space-y-4">
              <span className="px-3 py-1 rounded-md text-[10px] font-mono font-bold uppercase tracking-wider bg-[#EC3013]/20 text-[#EC3013]">
                Fire TV & Android TV Recommended
              </span>
              <h3 className="text-2xl font-bold text-white tracking-tight">
                FireStick Downloader Code
              </h3>
              <p className="text-xs text-stone-400 leading-relaxed">
                Type this 7-digit code into the URL field of the Downloader app to install directly onto your television.
              </p>

              <div className="p-5 rounded-2xl bg-black/80 border-2 border-[#EC3013]/50 flex items-center justify-between">
                <span className="font-mono text-3xl sm:text-4xl font-black text-white tracking-[0.2em]">
                  {APP_CONFIG.downloaderCode}
                </span>
                <button
                  onClick={handleCopy}
                  className={`p-3 rounded-xl transition-colors ${
                    copiedCode ? "bg-emerald-500 text-white" : "bg-white/10 hover:bg-white/15 text-stone-300"
                  }`}
                  title="Copy code"
                >
                  {copiedCode ? <Check className="w-5 h-5" /> : <Copy className="w-5 h-5" />}
                </button>
              </div>
            </div>

            <Link
              href="/setup-guide"
              className="w-full flex items-center justify-center gap-2 py-3.5 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-bold border border-white/10 transition-colors"
            >
              <span>View FireStick Setup Guide</span>
              <ArrowRight className="w-4 h-4 text-[#EC3013]" />
            </Link>
          </div>

          {/* Card 2: Direct APK Download */}
          <div className="glass-panel rounded-3xl p-8 flex flex-col justify-between space-y-6 relative overflow-hidden">
            <div className="space-y-4">
              <span className="px-3 py-1 rounded-md text-[10px] font-mono font-bold uppercase tracking-wider bg-white/10 text-stone-300">
                Direct Sideload File
              </span>
              <h3 className="text-2xl font-bold text-white tracking-tight">
                Direct APK Package
              </h3>
              <p className="text-xs text-stone-400 leading-relaxed">
                Universal APK file compatible with ARM64-v8a, ARMeabi-v7a, and x86 devices running Android 5.0 and above.
              </p>

              <div className="p-4 rounded-2xl bg-black/60 border border-white/10 space-y-2 text-xs font-mono text-stone-400">
                <div className="flex justify-between">
                  <span>Version:</span>
                  <span className="text-white font-bold">{APP_CONFIG.version} (Build {APP_CONFIG.versionCode})</span>
                </div>
                <div className="flex justify-between">
                  <span>File Size:</span>
                  <span className="text-white font-bold">{APP_CONFIG.apkSize}</span>
                </div>
                <div className="flex justify-between">
                  <span>Requirements:</span>
                  <span className="text-emerald-400 font-bold">Android 5.0+ (TV & Mobile)</span>
                </div>
              </div>
            </div>

            <a
              href={APP_CONFIG.downloadUrl}
              download
              className="btn-dx w-full flex items-center justify-center gap-2 py-4 rounded-xl text-xs font-bold uppercase tracking-wider shadow-lg"
            >
              <Download className="w-4 h-4" />
              <span>Download APK ({APP_CONFIG.apkSize})</span>
            </a>
          </div>
        </div>

        {/* Changelog Accordion */}
        <div className="glass-panel rounded-3xl p-8 space-y-6">
          <div className="flex items-center gap-3 border-b border-white/10 pb-4">
            <FileCheck className="w-5 h-5 text-[#EC3013]" />
            <h3 className="text-xl font-bold text-white">Release Notes & Changelog</h3>
          </div>

          <div className="space-y-6">
            {changelog.map((entry, idx) => (
              <div key={idx} className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-base font-bold text-white font-mono">{entry.version}</span>
                  <span className="text-xs text-stone-500 font-mono">{entry.date}</span>
                </div>
                <ul className="space-y-1.5 text-xs text-stone-400 list-disc list-inside">
                  {entry.items.map((item, i) => (
                    <li key={i}>{item}</li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
