"use client";

import React, { useState } from "react";
import Link from "next/link";
import { Copy, Check, Download, ArrowRight, Zap, ShieldCheck } from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";

export default function DownloaderCard() {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(APP_CONFIG.downloaderCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2200);
  };

  return (
    <div className="relative overflow-hidden rounded-3xl border border-white/10 bg-gradient-to-b from-[#1E1B19]/90 to-[#12100F]/95 p-6 sm:p-8 backdrop-blur-2xl shadow-2xl">
      {/* Glow highlight */}
      <div className="absolute -top-24 -right-24 w-64 h-64 bg-[#EC3013]/20 rounded-full blur-3xl pointer-events-none" />

      <div className="relative z-10 flex flex-col lg:flex-row items-center justify-between gap-8">
        {/* Left Side: Code and Copy */}
        <div className="space-y-4 text-center lg:text-left">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <Zap className="w-3.5 h-3.5 fill-[#EC3013]" />
            Fast FireStick Sideloading
          </div>

          <div>
            <h3 className="text-2xl sm:text-3xl font-extrabold text-white tracking-tight">
              FireStick Downloader Code
            </h3>
            <p className="text-stone-400 text-sm mt-1 max-w-md">
              Enter this 7-digit code directly into the URL bar of the Amazon Downloader app to fetch and install DX Play instantly.
            </p>
          </div>

          <div className="flex flex-wrap items-center justify-center lg:justify-start gap-3">
            <div className="flex items-center bg-black/80 border-2 border-[#EC3013]/50 rounded-2xl px-5 py-3 shadow-[0_0_30px_rgba(236,48,19,0.25)]">
              <span className="font-mono text-3xl sm:text-4xl font-black text-white tracking-[0.2em]">
                {APP_CONFIG.downloaderCode}
              </span>
            </div>

            <button
              onClick={handleCopy}
              className={`flex items-center gap-2 px-5 py-4 rounded-2xl font-bold text-sm transition-all duration-200 ${
                copied
                  ? "bg-emerald-500 text-white"
                  : "bg-white/10 hover:bg-white/15 text-white border border-white/15 hover:border-white/30"
              }`}
            >
              {copied ? (
                <>
                  <Check className="w-4 h-4" />
                  <span>Code Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-4 h-4 text-stone-300" />
                  <span>Copy Code</span>
                </>
              )}
            </button>
          </div>
        </div>

        {/* Right Side: Quick Action & direct APK */}
        <div className="w-full lg:w-auto flex flex-col sm:flex-row lg:flex-col gap-3 min-w-[260px]">
          <a
            href={APP_CONFIG.downloadUrl}
            download
            className="btn-dx flex items-center justify-center gap-2.5 py-4 px-6 rounded-2xl font-bold text-sm text-center shadow-lg"
          >
            <Download className="w-4 h-4" />
            <span>Direct APK Download</span>
            <span className="text-xs opacity-75 font-normal">({APP_CONFIG.apkSize})</span>
          </a>

          <Link
            href="/setup-guide"
            className="flex items-center justify-center gap-2 py-3.5 px-6 rounded-2xl font-bold text-sm bg-white/5 hover:bg-white/10 text-stone-300 hover:text-white border border-white/10 transition-colors"
          >
            <span>Step-by-Step Guide</span>
            <ArrowRight className="w-4 h-4 text-[#EC3013]" />
          </Link>
        </div>
      </div>

      {/* 3 Step Micro Quick Guide */}
      <div className="relative z-10 mt-8 pt-6 border-t border-white/[0.08] grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs">
        <div className="flex items-start gap-3 text-stone-400">
          <div className="w-6 h-6 rounded-full bg-white/10 text-white font-bold flex items-center justify-center flex-shrink-0">
            1
          </div>
          <div>
            <strong className="text-white block font-medium">Open Downloader</strong>
            Search & install 'Downloader' on your Fire TV.
          </div>
        </div>
        <div className="flex items-start gap-3 text-stone-400">
          <div className="w-6 h-6 rounded-full bg-[#EC3013]/20 text-[#EC3013] font-bold flex items-center justify-center flex-shrink-0">
            2
          </div>
          <div>
            <strong className="text-white block font-medium">Type {APP_CONFIG.downloaderCode}</strong>
            Enter the code in the URL box and click 'Go'.
          </div>
        </div>
        <div className="flex items-start gap-3 text-stone-400">
          <div className="w-6 h-6 rounded-full bg-emerald-500/20 text-emerald-400 font-bold flex items-center justify-center flex-shrink-0">
            3
          </div>
          <div>
            <strong className="text-white block font-medium">Install & Launch</strong>
            Click Install, open the app, and enter your TV code!
          </div>
        </div>
      </div>
    </div>
  );
}
