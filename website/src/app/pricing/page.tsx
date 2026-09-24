"use client";

import React from "react";
import PricingCards from "@/components/PricingCards";
import ComparisonTable from "@/components/ComparisonTable";
import { ShieldCheck, HelpCircle } from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";

export default function PricingPage() {
  return (
    <div className="py-12 sm:py-20">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-16">
        <PricingCards />

        {/* Feature Comparison */}
        <ComparisonTable />

        {/* Guarantee Banner */}
        <div className="glass-panel rounded-3xl p-8 max-w-4xl mx-auto flex flex-col sm:flex-row items-center gap-6 text-center sm:text-left">
          <div className="w-14 h-14 rounded-2xl bg-[#EC3013]/20 border border-[#EC3013]/30 flex items-center justify-center flex-shrink-0">
            <ShieldCheck className="w-8 h-8 text-[#EC3013]" />
          </div>
          <div className="space-y-1">
            <h4 className="text-lg font-bold text-white">Risk-Free 24-Hour Free Trial</h4>
            <p className="text-xs text-stone-400 leading-relaxed">
              Test DX Play with your own provider server or Debrid account before purchasing any license. Contact our automated support on Telegram to get your trial software license instantly.
            </p>
          </div>
          <a
            href={APP_CONFIG.telegramSupport}
            target="_blank"
            rel="noreferrer"
            className="btn-dx px-6 py-3.5 rounded-xl text-xs font-bold uppercase tracking-wider flex-shrink-0"
          >
            Get Free Trial
          </a>
        </div>
      </div>
    </div>
  );
}
