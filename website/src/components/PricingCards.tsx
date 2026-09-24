"use client";

import React, { useState } from "react";
import Link from "next/link";
import { Check, Sparkles, Zap, ArrowRight, ShieldCheck } from "lucide-react";
import { PRICING_PLANS, APP_CONFIG } from "@/lib/constants";

export default function PricingCards() {
  const [filter, setFilter] = useState<"all" | "annual" | "lifetime">("all");

  const filteredPlans =
    filter === "all"
      ? PRICING_PLANS
      : PRICING_PLANS.filter((p) => p.id === filter || p.id === "trial");

  return (
    <section className="py-20 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-10 space-y-4">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <Sparkles className="w-3.5 h-3.5 fill-[#EC3013]" />
            Media Player Software Licensing
          </div>
          <h2 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
            Software Licenses — 1-Year & Lifetime
          </h2>
          <p className="text-stone-400 text-base sm:text-lg">
            Pay once or annually for the fastest Android TV & FireStick player engine. No monthly subscription traps.
          </p>

          {/* Legal Notice Banner */}
          <div className="mt-6 p-4 sm:p-5 rounded-2xl bg-[#EC3013]/10 border border-[#EC3013]/30 text-left flex items-start gap-3.5">
            <ShieldCheck className="w-5 h-5 text-[#EC3013] flex-shrink-0 mt-0.5" />
            <div className="space-y-1">
              <div className="text-xs sm:text-sm font-bold text-white flex items-center gap-2">
                <span>Software-Only Disclaimer</span>
                <span className="px-2 py-0.5 rounded text-[10px] bg-[#EC3013] text-white uppercase font-bold tracking-wider">
                  Important
                </span>
              </div>
              <p className="text-xs text-stone-300 leading-relaxed">
                <strong>DX Play is purely a media player software.</strong> We do <strong>NOT</strong> provide, host, sell, or distribute any IPTV subscriptions, video streams, or TV channels. Users must supply their own Xtream Codes server credentials, M3U playlists, or Debrid accounts (Real-Debrid / AllDebrid).
              </p>
            </div>
          </div>

          {/* License Switcher */}
          <div className="inline-flex items-center bg-[#151312] border border-white/10 rounded-full p-1.5 mt-4">
            <button
              onClick={() => setFilter("all")}
              className={`px-5 py-2 rounded-full text-xs font-bold transition-all ${
                filter === "all"
                  ? "bg-[#EC3013] text-white shadow-md shadow-[#EC3013]/30"
                  : "text-stone-400 hover:text-white"
              }`}
            >
              All Licenses
            </button>
            <button
              onClick={() => setFilter("annual")}
              className={`px-5 py-2 rounded-full text-xs font-bold transition-all ${
                filter === "annual"
                  ? "bg-[#EC3013] text-white shadow-md shadow-[#EC3013]/30"
                  : "text-stone-400 hover:text-white"
              }`}
            >
              1-Year License ($14.99/yr)
            </button>
            <button
              onClick={() => setFilter("lifetime")}
              className={`px-5 py-2 rounded-full text-xs font-bold transition-all flex items-center gap-1.5 ${
                filter === "lifetime"
                  ? "bg-[#EC3013] text-white shadow-md shadow-[#EC3013]/30"
                  : "text-stone-400 hover:text-white"
              }`}
            >
              <span>Lifetime License ($29.99)</span>
              <span className="px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-400 text-[10px] font-bold">
                Pay Once
              </span>
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-stretch">
          {filteredPlans.map((plan) => {
            return (
              <div
                key={plan.id}
                className={`relative rounded-3xl p-8 flex flex-col justify-between transition-all duration-300 ${
                  plan.highlighted
                    ? "bg-gradient-to-b from-[#1F1917] to-[#120F0E] border-2 border-[#EC3013] shadow-[0_0_50px_rgba(236,48,19,0.25)] scale-105 z-10"
                    : "glass-panel glass-panel-hover"
                }`}
              >
                {plan.highlighted && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 px-4 py-1 rounded-full bg-[#EC3013] text-white text-[11px] font-black uppercase tracking-wider shadow-lg flex items-center gap-1.5">
                    <Sparkles className="w-3 h-3 fill-white" />
                    <span>Best Value • Pay Once</span>
                  </div>
                )}

                <div className="space-y-6">
                  <div className="space-y-2">
                    <span className="px-2.5 py-1 rounded text-[10px] font-mono font-bold uppercase tracking-wider bg-white/10 text-stone-300">
                      {plan.badge}
                    </span>
                    <h3 className="text-2xl font-bold text-white tracking-tight pt-1">
                      {plan.name}
                    </h3>
                    <p className="text-xs text-stone-400 leading-relaxed min-h-[36px]">
                      {plan.description}
                    </p>
                  </div>

                  <div className="pt-2 border-t border-white/10 flex items-baseline gap-2">
                    <span className="text-4xl sm:text-5xl font-black text-white tracking-tight">
                      {plan.price}
                    </span>
                    <span className="text-xs text-stone-400 font-medium">/{plan.period}</span>
                  </div>

                  <div className="space-y-3 pt-2">
                    <div className="text-[11px] font-bold text-stone-400 uppercase tracking-wider">
                      Included License Features:
                    </div>
                    <ul className="space-y-2.5 text-xs text-stone-300">
                      {plan.features.map((feat, i) => (
                        <li key={i} className="flex items-start gap-2.5">
                          <Check className="w-4 h-4 text-[#EC3013] flex-shrink-0 mt-0.5" />
                          <span>{feat}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>

                <div className="pt-8 mt-6 border-t border-white/10">
                  <a
                    href={`${APP_CONFIG.telegramSupport}?text=I%20want%20to%20activate%20DX%20Play%20${plan.name}`}
                    target="_blank"
                    rel="noreferrer"
                    className={`w-full flex items-center justify-center gap-2 py-3.5 rounded-xl font-bold text-xs uppercase tracking-wider transition-all ${
                      plan.highlighted
                        ? "btn-dx"
                        : "bg-white/10 hover:bg-white/15 text-white border border-white/10 hover:border-white/20"
                    }`}
                  >
                    <span>{plan.cta}</span>
                    <ArrowRight className="w-4 h-4" />
                  </a>
                </div>
              </div>
            );
          })}
        </div>

        {/* Device Linking Footer Note */}
        <div className="mt-14 p-6 rounded-2xl bg-[#141211] border border-white/10 flex flex-col sm:flex-row items-center justify-between gap-4 text-center sm:text-left">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center flex-shrink-0">
              <ShieldCheck className="w-5 h-5 text-[#EC3013]" />
            </div>
            <div>
              <h4 className="text-sm font-bold text-white">Already have an active account or activation code?</h4>
              <p className="text-xs text-stone-400">Link your TV in seconds using the official web pairing companion.</p>
            </div>
          </div>
          <Link
            href="/link"
            className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-bold border border-white/15 flex-shrink-0"
          >
            <span>Pair Device Code</span>
            <ArrowRight className="w-3.5 h-3.5 text-[#EC3013]" />
          </Link>
        </div>
      </div>
    </section>
  );
}
