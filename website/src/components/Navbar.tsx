"use client";

import React, { useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { Download, Tv, Menu, X, ArrowUpRight, Copy, Check } from "lucide-react";
import { APP_CONFIG, NAVIGATION_LINKS } from "@/lib/constants";

export default function Navbar() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const pathname = usePathname();

  const handleCopyCode = () => {
    navigator.clipboard.writeText(APP_CONFIG.downloaderCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <header className="sticky top-0 z-50 w-full border-b border-white/[0.08] bg-[#0A0908]/80 backdrop-blur-xl">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-20">
          {/* Brand Logo */}
          <Link href="/" className="flex items-center gap-3.5 group">
            <div className="relative w-10 h-10 rounded-xl overflow-hidden bg-black/40 border border-white/10 p-1 flex items-center justify-center transition-transform group-hover:scale-105 group-hover:border-[#EC3013]/60 shadow-[0_0_20px_rgba(236,48,19,0.2)]">
              <Image
                src="/brand/logo-icon-cinema.png"
                alt="DX Play"
                width={36}
                height={36}
                className="object-contain"
                priority
              />
            </div>
            <div className="flex flex-col">
              <div className="flex items-center gap-1.5">
                <span className="text-xl font-extrabold tracking-tight text-white group-hover:text-white/95">
                  DX<span className="text-[#EC3013]">Play</span>
                </span>
                <span className="px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider bg-white/10 text-stone-300 rounded border border-white/10">
                  v{APP_CONFIG.version}
                </span>
              </div>
              <span className="text-[11px] text-stone-400 font-medium tracking-wide">
                Hybrid IPTV & Debrid
              </span>
            </div>
          </Link>

          {/* Desktop Navigation */}
          <nav className="hidden md:flex items-center gap-1 bg-white/[0.03] border border-white/[0.06] rounded-full px-4 py-1.5">
            {NAVIGATION_LINKS.map((link) => {
              const isActive = pathname === link.href;
              return (
                <Link
                  key={link.name}
                  href={link.href}
                  className={`px-3.5 py-1.5 text-sm font-medium rounded-full transition-all duration-200 ${
                    isActive
                      ? "text-white bg-white/10"
                      : "text-stone-300 hover:text-white hover:bg-white/[0.06]"
                  }`}
                >
                  {link.name}
                </Link>
              );
            })}
          </nav>

          {/* Action CTAs */}
          <div className="hidden lg:flex items-center gap-3">
            {/* Downloader Code Quick Copy */}
            <button
              onClick={handleCopyCode}
              className="flex items-center gap-2 px-3 py-1.5 bg-black/60 border border-white/10 rounded-lg text-xs font-mono text-stone-300 hover:border-[#EC3013]/50 transition-colors group"
              title="Click to copy Downloader code"
            >
              <span className="text-stone-500">Downloader:</span>
              <span className="text-[#EC3013] font-bold tracking-wider">
                {APP_CONFIG.downloaderCode}
              </span>
              {copied ? (
                <Check className="w-3.5 h-3.5 text-emerald-400" />
              ) : (
                <Copy className="w-3.5 h-3.5 text-stone-400 group-hover:text-white" />
              )}
            </button>

            {/* Link TV Button */}
            <Link
              href="/link"
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold text-white bg-white/10 hover:bg-white/15 border border-white/15 transition-all"
            >
              <Tv className="w-3.5 h-3.5 text-[#EC3013]" />
              <span>Link TV</span>
            </Link>

            {/* Direct Download Button */}
            <Link
              href="/download"
              className="btn-dx flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold transition-all shadow-[0_0_20px_rgba(236,48,19,0.3)] hover:shadow-[0_0_25px_rgba(236,48,19,0.5)]"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Download APK</span>
            </Link>
          </div>

          {/* Mobile menu button */}
          <div className="md:hidden flex items-center gap-2">
            <button
              onClick={handleCopyCode}
              className="flex items-center gap-1 px-2.5 py-1.5 bg-black/60 border border-white/10 rounded-lg text-[11px] font-mono text-stone-300"
            >
              <span className="text-[#EC3013] font-bold">{APP_CONFIG.downloaderCode}</span>
              {copied ? (
                <Check className="w-3 h-3 text-emerald-400" />
              ) : (
                <Copy className="w-3 h-3 text-stone-400" />
              )}
            </button>
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="p-2 rounded-lg bg-white/5 border border-white/10 text-stone-300 hover:text-white"
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </button>
          </div>
        </div>
      </div>

      {/* Mobile Drawer Menu */}
      {mobileMenuOpen && (
        <div className="md:hidden border-b border-white/10 bg-[#0E0C0B] px-4 pt-3 pb-6 space-y-3">
          <div className="flex flex-col space-y-1">
            {NAVIGATION_LINKS.map((link) => (
              <Link
                key={link.name}
                href={link.href}
                onClick={() => setMobileMenuOpen(false)}
                className="px-3 py-2.5 rounded-lg text-sm font-medium text-stone-200 hover:bg-white/5"
              >
                {link.name}
              </Link>
            ))}
          </div>

          <div className="pt-3 border-t border-white/10 flex flex-col gap-2.5">
            <Link
              href="/link"
              onClick={() => setMobileMenuOpen(false)}
              className="flex items-center justify-center gap-2 w-full py-2.5 rounded-lg text-sm font-bold bg-white/10 text-white border border-white/10"
            >
              <Tv className="w-4 h-4 text-[#EC3013]" />
              Link Device (Enter Code)
            </Link>
            <Link
              href="/download"
              onClick={() => setMobileMenuOpen(false)}
              className="btn-dx flex items-center justify-center gap-2 w-full py-2.5 rounded-lg text-sm font-bold"
            >
              <Download className="w-4 h-4" />
              Download APK (Direct)
            </Link>
          </div>
        </div>
      )}
    </header>
  );
}
