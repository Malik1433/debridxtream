import React from "react";
import Link from "next/link";
import Image from "next/image";
import { Send, MessageCircle, ShieldCheck, Cpu, Tv, ArrowUpRight } from "lucide-react";
import { APP_CONFIG, NAVIGATION_LINKS } from "@/lib/constants";

export default function Footer() {
  return (
    <footer className="border-t border-white/[0.08] bg-[#070605] text-stone-400 text-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-10">
          {/* Brand Info */}
          <div className="lg:col-span-2 space-y-4">
            <div className="flex items-center gap-3">
              <div className="relative w-9 h-9 rounded-xl bg-black/60 border border-white/10 p-1 flex items-center justify-center">
                <Image
                  src="/brand/logo-icon-cinema.png"
                  alt="DX Play"
                  width={32}
                  height={32}
                  className="object-contain"
                />
              </div>
              <span className="text-xl font-extrabold text-white tracking-tight">
                DX<span className="text-[#EC3013]">Play</span>
              </span>
            </div>
            <p className="text-stone-400 text-xs sm:text-sm leading-relaxed max-w-sm">
              The next-generation hybrid IPTV & Debrid streaming client. Engineered with local-first
              Room DB caching and Media3 ExoPlayer for instant 0ms response on Android TV & FireStick.
            </p>
            <div className="flex items-center gap-3 pt-2">
              <a
                href={APP_CONFIG.telegramSupport}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 text-white text-xs border border-white/10 transition-colors"
              >
                <Send className="w-3.5 h-3.5 text-sky-400" />
                Telegram Community
              </a>
              <a
                href={APP_CONFIG.whatsappSupport}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 text-white text-xs border border-white/10 transition-colors"
              >
                <MessageCircle className="w-3.5 h-3.5 text-emerald-400" />
                WhatsApp VIP Support
              </a>
            </div>
          </div>

          {/* Quick Navigation */}
          <div>
            <h4 className="text-white font-bold text-xs uppercase tracking-wider mb-4">
              Navigation
            </h4>
            <ul className="space-y-2.5 text-xs">
              {NAVIGATION_LINKS.map((link) => (
                <li key={link.name}>
                  <Link
                    href={link.href}
                    className="hover:text-white transition-colors"
                  >
                    {link.name}
                  </Link>
                </li>
              ))}
              <li>
                <Link href="/link" className="hover:text-white transition-colors flex items-center gap-1">
                  <span>Link Device (Companion)</span>
                  <ArrowUpRight className="w-3 h-3 text-[#EC3013]" />
                </Link>
              </li>
            </ul>
          </div>

          {/* Platforms */}
          <div>
            <h4 className="text-white font-bold text-xs uppercase tracking-wider mb-4">
              Target Devices
            </h4>
            <ul className="space-y-2.5 text-xs">
              <li className="flex items-center gap-2">
                <Tv className="w-3.5 h-3.5 text-stone-500" />
                <span>Amazon Fire TV / FireStick</span>
              </li>
              <li className="flex items-center gap-2">
                <Tv className="w-3.5 h-3.5 text-stone-500" />
                <span>Google TV & Chromecast</span>
              </li>
              <li className="flex items-center gap-2">
                <Tv className="w-3.5 h-3.5 text-stone-500" />
                <span>Nvidia Shield Pro</span>
              </li>
              <li className="flex items-center gap-2">
                <Tv className="w-3.5 h-3.5 text-stone-500" />
                <span>Android Smart TVs (Sony, TCL)</span>
              </li>
              <li className="flex items-center gap-2">
                <Cpu className="w-3.5 h-3.5 text-stone-500" />
                <span>Android Phones & Tablets</span>
              </li>
            </ul>
          </div>

          {/* Fast Downloader Info */}
          <div>
            <h4 className="text-white font-bold text-xs uppercase tracking-wider mb-4">
              Downloader Code
            </h4>
            <div className="p-3.5 rounded-xl bg-black/60 border border-white/10 space-y-2">
              <div className="text-[11px] text-stone-400">FireStick Downloader Code:</div>
              <div className="font-mono text-xl font-black text-[#EC3013] tracking-widest">
                {APP_CONFIG.downloaderCode}
              </div>
              <div className="text-[10px] text-stone-500">
                Type this 7-digit code into Downloader app and click GO to install instantly.
              </div>
            </div>
          </div>
        </div>

        {/* Legal Disclaimer */}
        <div className="mt-14 pt-8 border-t border-white/[0.06] text-xs text-stone-500 space-y-3">
          <p>
            <strong>Disclaimer:</strong> DX Play (DebridXtream) does not host, store, index, or distribute any media streams, playlist files, or digital content. It is purely a media player application that connects to user-supplied Xtream Codes APIs or authorized Debrid accounts. Users are solely responsible for ensuring they possess the necessary rights for any stream or content consumed.
          </p>
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4 text-stone-500 text-[11px]">
            <div>&copy; {new Date().getFullYear()} DX Play • All Rights Reserved.</div>
            <div className="flex items-center gap-4">
              <span>Privacy Policy</span>
              <span>•</span>
              <span>Terms of Service</span>
              <span>•</span>
              <span className="text-[#EC3013]">Ultra Fast Build v{APP_CONFIG.version}</span>
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
}
