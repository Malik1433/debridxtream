"use client";

import React, { useState } from "react";
import Link from "next/link";
import {
  Tv,
  Download,
  CheckCircle2,
  Copy,
  Check,
  AlertCircle,
  ArrowRight,
  Smartphone,
  Shield,
  HelpCircle,
} from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";

export default function SetupGuidePage() {
  const [activeDevice, setActiveDevice] = useState<"firestick" | "androidtv" | "mobile">("firestick");
  const [copiedCode, setCopiedCode] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(APP_CONFIG.downloaderCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  return (
    <div className="py-12 sm:py-20">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="text-center max-w-3xl mx-auto mb-14 space-y-4">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <Tv className="w-3.5 h-3.5" />
            Official Installation Walkthrough
          </div>
          <h1 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
            How to Install DX Play on Any Device
          </h1>
          <p className="text-stone-400 text-sm sm:text-base">
            Follow our verified step-by-step instructions. Takes less than 2 minutes to complete on your FireStick or Android TV box.
          </p>

          {/* Device Tabs */}
          <div className="flex flex-wrap items-center justify-center gap-2 pt-4">
            <button
              onClick={() => setActiveDevice("firestick")}
              className={`flex items-center gap-2 px-5 py-3 rounded-2xl text-xs sm:text-sm font-bold transition-all ${
                activeDevice === "firestick"
                  ? "bg-[#EC3013] text-white shadow-lg shadow-[#EC3013]/30"
                  : "bg-white/5 hover:bg-white/10 text-stone-300 border border-white/10"
              }`}
            >
              <Tv className="w-4 h-4" />
              <span>Amazon FireStick & Fire TV</span>
            </button>

            <button
              onClick={() => setActiveDevice("androidtv")}
              className={`flex items-center gap-2 px-5 py-3 rounded-2xl text-xs sm:text-sm font-bold transition-all ${
                activeDevice === "androidtv"
                  ? "bg-[#EC3013] text-white shadow-lg shadow-[#EC3013]/30"
                  : "bg-white/5 hover:bg-white/10 text-stone-300 border border-white/10"
              }`}
            >
              <Tv className="w-4 h-4" />
              <span>Google TV & Android TV</span>
            </button>

            <button
              onClick={() => setActiveDevice("mobile")}
              className={`flex items-center gap-2 px-5 py-3 rounded-2xl text-xs sm:text-sm font-bold transition-all ${
                activeDevice === "mobile"
                  ? "bg-[#EC3013] text-white shadow-lg shadow-[#EC3013]/30"
                  : "bg-white/5 hover:bg-white/10 text-stone-300 border border-white/10"
              }`}
            >
              <Smartphone className="w-4 h-4" />
              <span>Android Phones & Tablets</span>
            </button>
          </div>
        </div>

        {/* Tab 1: FIRESTICK GUIDE */}
        {activeDevice === "firestick" && (
          <div className="space-y-8 animate-fadeIn">
            {/* Quick Code Reminder Box */}
            <div className="rounded-3xl border border-[#EC3013]/40 bg-gradient-to-r from-[#1E1412] to-[#141211] p-6 sm:p-8 flex flex-col sm:flex-row items-center justify-between gap-6 shadow-xl">
              <div>
                <span className="text-[11px] font-mono uppercase text-[#EC3013] font-bold tracking-widest block mb-1">
                  OFFICIAL FIRESTICK CODE
                </span>
                <h3 className="text-xl sm:text-2xl font-black text-white">
                  Downloader Code:{" "}
                  <span className="font-mono text-[#EC3013]">{APP_CONFIG.downloaderCode}</span>
                </h3>
                <p className="text-xs text-stone-400 mt-1">
                  Enter this code into the URL field in Downloader and click Go.
                </p>
              </div>

              <button
                onClick={handleCopy}
                className="flex items-center gap-2 px-6 py-3.5 rounded-xl bg-[#EC3013] hover:bg-[#D4270D] text-white text-xs font-bold transition-colors shadow-md flex-shrink-0"
              >
                {copiedCode ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                <span>{copiedCode ? "Code Copied!" : "Copy Code"}</span>
              </button>
            </div>

            {/* Steps Container */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* Step 1 */}
              <div className="glass-panel rounded-3xl p-6 sm:p-8 space-y-3 relative overflow-hidden">
                <div className="w-10 h-10 rounded-xl bg-white/10 text-white font-black text-base flex items-center justify-center">
                  1
                </div>
                <h4 className="text-lg font-bold text-white">Install the Downloader App</h4>
                <p className="text-sm text-stone-400 leading-relaxed">
                  From your Fire TV Home screen, go to <strong>Find &rarr; Search</strong>. Type <strong>Downloader</strong>, select the orange app icon by AFTVnews, and click <strong>Download / Get</strong>.
                </p>
              </div>

              {/* Step 2 */}
              <div className="glass-panel rounded-3xl p-6 sm:p-8 space-y-3 relative overflow-hidden">
                <div className="w-10 h-10 rounded-xl bg-white/10 text-white font-black text-base flex items-center justify-center">
                  2
                </div>
                <h4 className="text-lg font-bold text-white">Enable Unknown Apps</h4>
                <p className="text-sm text-stone-400 leading-relaxed">
                  Go to FireStick <strong>Settings &rarr; My Fire TV &rarr; Developer Options &rarr; Install Unknown Apps</strong>. Find <strong>Downloader</strong> and toggle it to <strong>ON</strong>.
                </p>
                <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 text-xs text-amber-300 flex items-start gap-2">
                  <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                  <span>
                    Don't see Developer Options? Go to <strong>About</strong>, highlight Fire TV Stick, and click the remote button <strong>7 times</strong> until it says 'You are now a developer'.
                  </span>
                </div>
              </div>

              {/* Step 3 */}
              <div className="glass-panel rounded-3xl p-6 sm:p-8 space-y-3 relative overflow-hidden">
                <div className="w-10 h-10 rounded-xl bg-[#EC3013]/20 text-[#EC3013] font-black text-base flex items-center justify-center">
                  3
                </div>
                <h4 className="text-lg font-bold text-white">Enter Code {APP_CONFIG.downloaderCode}</h4>
                <p className="text-sm text-stone-400 leading-relaxed">
                  Launch Downloader, select the URL box, type <strong>{APP_CONFIG.downloaderCode}</strong>, and click <strong>Go</strong>. The APK will start downloading automatically.
                </p>
              </div>

              {/* Step 4 */}
              <div className="glass-panel rounded-3xl p-6 sm:p-8 space-y-3 relative overflow-hidden">
                <div className="w-10 h-10 rounded-xl bg-emerald-500/20 text-emerald-400 font-black text-base flex items-center justify-center">
                  4
                </div>
                <h4 className="text-lg font-bold text-white">Click Install & Launch</h4>
                <p className="text-sm text-stone-400 leading-relaxed">
                  Once the download finishes, click <strong>Install</strong>. When installation completes, click <strong>Open</strong>. Your TV will display an activation code to pair via mobile!
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Tab 2: ANDROID TV / GOOGLE TV */}
        {activeDevice === "androidtv" && (
          <div className="space-y-6 animate-fadeIn">
            <div className="glass-panel rounded-3xl p-8 space-y-6">
              <h3 className="text-2xl font-bold text-white">
                Installing on Google TV, Sony, TCL, Philips, or Nvidia Shield
              </h3>

              <div className="space-y-4 text-sm text-stone-300 leading-relaxed">
                <div className="flex items-start gap-3">
                  <CheckCircle2 className="w-5 h-5 text-[#EC3013] flex-shrink-0 mt-0.5" />
                  <div>
                    <strong>Method 1: Using the Downloader App (Recommended)</strong>
                    <p className="text-stone-400 text-xs mt-1">
                      Open Google Play Store on your Android TV, install <strong>Downloader by AFTVnews</strong>, and type code <strong>{APP_CONFIG.downloaderCode}</strong>.
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-3">
                  <CheckCircle2 className="w-5 h-5 text-[#EC3013] flex-shrink-0 mt-0.5" />
                  <div>
                    <strong>Method 2: Using 'Send Files to TV' or USB Sideload</strong>
                    <p className="text-stone-400 text-xs mt-1">
                      Download the direct APK on your phone or PC, transfer it via the free app <strong>Send Files to TV</strong>, and open it with a file manager (e.g. FX File Explorer).
                    </p>
                  </div>
                </div>
              </div>

              <div className="pt-4 border-t border-white/10 flex items-center gap-4">
                <a
                  href={APP_CONFIG.downloadUrl}
                  download
                  className="btn-dx px-6 py-3 rounded-xl text-xs font-bold inline-flex items-center gap-2"
                >
                  <Download className="w-4 h-4" />
                  <span>Download Universal APK</span>
                </a>
              </div>
            </div>
          </div>
        )}

        {/* Tab 3: MOBILE */}
        {activeDevice === "mobile" && (
          <div className="space-y-6 animate-fadeIn">
            <div className="glass-panel rounded-3xl p-8 space-y-6">
              <h3 className="text-2xl font-bold text-white">
                Installing on Android Phone or Tablet
              </h3>
              <p className="text-sm text-stone-400 leading-relaxed">
                DX Play features dedicated Touch & Portrait/Landscape optimization for mobile phones and tablets.
              </p>

              <ol className="space-y-3 text-sm text-stone-300 list-decimal list-inside">
                <li>Click the direct download button below to download the APK.</li>
                <li>Tap the downloaded file in your notification bar or Downloads folder.</li>
                <li>If prompted, tap <strong>Settings &rarr; Allow from this source</strong>.</li>
                <li>Tap <strong>Install</strong> and enjoy!</li>
              </ol>

              <div className="pt-4 border-t border-white/10">
                <a
                  href={APP_CONFIG.downloadUrl}
                  download
                  className="btn-dx px-6 py-3.5 rounded-xl text-xs font-bold inline-flex items-center gap-2"
                >
                  <Download className="w-4 h-4" />
                  <span>Download Mobile APK ({APP_CONFIG.apkSize})</span>
                </a>
              </div>
            </div>
          </div>
        )}

        {/* Need Help CTA */}
        <div className="mt-16 p-8 rounded-3xl bg-[#141211] border border-white/10 flex flex-col sm:flex-row items-center justify-between gap-6 text-center sm:text-left">
          <div className="space-y-1">
            <h4 className="text-lg font-bold text-white">Need help during setup?</h4>
            <p className="text-xs text-stone-400">
              Our 24/7 technical team is available on Telegram and WhatsApp to assist you with installation.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <a
              href={APP_CONFIG.telegramSupport}
              target="_blank"
              rel="noreferrer"
              className="px-5 py-2.5 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-bold border border-white/15 transition-colors"
            >
              Telegram Support
            </a>
            <a
              href={APP_CONFIG.whatsappSupport}
              target="_blank"
              rel="noreferrer"
              className="btn-dx px-5 py-2.5 rounded-xl text-xs font-bold transition-all"
            >
              WhatsApp Support
            </a>
          </div>
        </div>
      </div>
    </div>
  );
}
