"use client";

import React from "react";
import Link from "next/link";
import {
  Layers,
  TrendingUp,
  Cpu,
  ShieldCheck,
  Zap,
  ArrowRight,
  CheckCircle2,
  Users,
} from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";
import ResellerCalculator from "@/components/ResellerCalculator";

export default function ResellerPage() {
  const resellerPerks = [
    {
      title: "Wholesale License Credits",
      desc: "Buy app software credits in bulk at steep discounts. Activating or extending a customer's TV device license consumes credits.",
    },
    {
      title: "Instant TV Device Activation",
      desc: "Direct web-based pairing and activation using the customer's 4-digit TV code. No manual setup required.",
    },
    {
      title: "No Device Limits",
      desc: "Sell to 10 or 10,000 clients. You maintain complete control over your customer database and renewal alerts.",
    },
    {
      title: "Instant OTA Update Distribution",
      desc: "All clients receive automated updates without manual APK sideloading whenever a new version is published.",
    },
    {
      title: "Branded Experience",
      desc: "Your customers get the highest rated, smoothest TV interface on the market, drastically reducing support tickets.",
    },
    {
      title: "Self-Service Portal",
      desc: "Manage device activations, view expiring accounts, top up credits via Crypto/Card, and manage sub-resellers.",
    },
  ];

  return (
    <div className="py-12 sm:py-20">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-20">
        {/* Header Hero */}
        <div className="text-center max-w-3xl mx-auto space-y-4">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <Layers className="w-3.5 h-3.5" />
            Official Reseller & Wholesaler Program
          </div>
          <h1 className="text-3xl sm:text-6xl font-black text-white tracking-tight">
            Scale Your Software Reselling with DX Play
          </h1>
          <p className="text-stone-400 text-sm sm:text-base leading-relaxed">
            Stop losing customers to clunky, crashing apps. Equip your clients with DX Play's 0ms latency Leanback TV interface and increase customer retention.
          </p>

          <div className="flex flex-wrap items-center justify-center gap-4 pt-4">
            <Link
              href="/reseller/login"
              className="btn-dx flex items-center gap-2 px-7 py-3.5 rounded-2xl text-xs font-bold uppercase tracking-wider shadow-lg"
            >
              <span>Login to Reseller Portal</span>
              <ArrowRight className="w-4 h-4" />
            </Link>

            <Link
              href="/reseller/signup"
              className="flex items-center gap-2 px-6 py-3.5 rounded-2xl text-xs font-bold bg-[#EC3013]/15 hover:bg-[#EC3013]/25 text-white border border-[#EC3013]/40 transition-colors uppercase tracking-wider"
            >
              <span>Register Reseller Account</span>
            </Link>

            <a
              href={`${APP_CONFIG.telegramSupport}?text=I%20want%20to%20become%20a%20DX%20Play%20Reseller`}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-2 px-6 py-3.5 rounded-2xl text-xs font-bold bg-white/10 hover:bg-white/15 text-stone-200 border border-white/10 transition-colors"
            >
              <span>Contact Wholesaler Rep</span>
            </a>
          </div>
        </div>

        {/* Reseller Calculator */}
        <ResellerCalculator />

        {/* Benefits Grid */}
        <div className="space-y-8">
          <div className="text-center max-w-2xl mx-auto space-y-2">
            <h3 className="text-2xl sm:text-3xl font-bold text-white tracking-tight">
              Why Top Providers Choose DX Play
            </h3>
            <p className="text-stone-400 text-sm">
              Built by IPTV veterans to eliminate support headaches and maximize customer retention.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {resellerPerks.map((perk, i) => (
              <div key={i} className="glass-panel glass-panel-hover rounded-3xl p-6 space-y-3">
                <div className="flex items-center gap-2 text-white font-bold text-base">
                  <CheckCircle2 className="w-5 h-5 text-[#EC3013] flex-shrink-0" />
                  <span>{perk.title}</span>
                </div>
                <p className="text-xs text-stone-400 leading-relaxed pl-7">
                  {perk.desc}
                </p>
              </div>
            ))}
          </div>
        </div>

        {/* CTA Footer */}
        <div className="rounded-3xl bg-gradient-to-r from-[#1C1412] to-[#12100F] border border-white/10 p-8 sm:p-12 text-center space-y-6">
          <h3 className="text-2xl sm:text-4xl font-black text-white tracking-tight">
            Ready to Onboard Your Clients Today?
          </h3>
          <p className="text-stone-400 text-sm max-w-xl mx-auto">
            Get your reseller account verified within minutes and receive starter demo credits to test on your own FireStick.
          </p>
          <div className="pt-2">
            <Link
              href={APP_CONFIG.resellerPortalUrl}
              className="btn-dx inline-flex items-center gap-2 px-8 py-4 rounded-2xl text-sm font-bold uppercase tracking-wider"
            >
              <span>Access Reseller Portal</span>
              <ArrowRight className="w-4 h-4" />
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
