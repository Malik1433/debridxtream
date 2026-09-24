"use client";

import React, { useState } from "react";
import Link from "next/link";
import { DollarSign, TrendingUp, Users, ShieldCheck, ArrowRight, Layers } from "lucide-react";
import { APP_CONFIG } from "@/lib/constants";

export default function ResellerCalculator() {
  const [credits, setCredits] = useState(100);
  const [retailPrice, setRetailPrice] = useState(12);
  const costPerCredit = 3.0; // Wholesale cost

  const totalCost = credits * costPerCredit;
  const totalRevenue = credits * retailPrice;
  const profit = Math.max(0, totalRevenue - totalCost);
  const margin = totalRevenue > 0 ? Math.round((profit / totalRevenue) * 100) : 0;

  return (
    <div className="rounded-3xl border border-white/10 bg-gradient-to-b from-[#181514] to-[#0E0C0B] p-6 sm:p-10 backdrop-blur-2xl shadow-2xl">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
        {/* Left Interactive Calculator */}
        <div className="lg:col-span-7 space-y-6">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#EC3013]/10 border border-[#EC3013]/25 text-[#EC3013] text-xs font-bold uppercase tracking-wider">
            <TrendingUp className="w-3.5 h-3.5" />
            Interactive Reseller Profit Estimator
          </div>

          <h3 className="text-2xl sm:text-3xl font-extrabold text-white tracking-tight">
            Calculate Your Reseller License Margins
          </h3>

          <p className="text-stone-400 text-sm leading-relaxed">
            Wholesale software credits allow you to activate client TV app licenses on demand. You set your own customer prices and keep 100% of the profit.
          </p>

          {/* Slider 1: Number of Credits / Devices */}
          <div className="space-y-2 pt-2">
            <div className="flex justify-between items-center text-xs font-semibold">
              <span className="text-stone-300">Credits / Devices To Sell:</span>
              <span className="font-mono text-lg text-[#EC3013] font-bold">
                {credits} Devices
              </span>
            </div>
            <input
              type="range"
              min="10"
              max="500"
              step="10"
              value={credits}
              onChange={(e) => setCredits(Number(e.target.value))}
              className="w-full accent-[#EC3013] cursor-pointer"
            />
            <div className="flex justify-between text-[10px] text-stone-500 font-mono">
              <span>10 Devices</span>
              <span>100 Devices</span>
              <span>250 Devices</span>
              <span>500 Devices</span>
            </div>
          </div>

          {/* Slider 2: Retail Price to End User */}
          <div className="space-y-2 pt-2">
            <div className="flex justify-between items-center text-xs font-semibold">
              <span className="text-stone-300">Your Retail Selling Price (Per License):</span>
              <span className="font-mono text-lg text-emerald-400 font-bold">
                ${retailPrice}.00 / license
              </span>
            </div>
            <input
              type="range"
              min="5"
              max="25"
              step="1"
              value={retailPrice}
              onChange={(e) => setRetailPrice(Number(e.target.value))}
              className="w-full accent-emerald-500 cursor-pointer"
            />
            <div className="flex justify-between text-[10px] text-stone-500 font-mono">
              <span>$5.00</span>
              <span>$12.00 (Standard)</span>
              <span>$20.00</span>
              <span>$25.00</span>
            </div>
          </div>
        </div>

        {/* Right Financial Breakdown Cards */}
        <div className="lg:col-span-5 rounded-2xl bg-black/60 border border-white/10 p-6 space-y-5">
          <div className="text-xs font-bold uppercase tracking-wider text-stone-400">
            Projected Earnings
          </div>

          <div className="space-y-3 pb-4 border-b border-white/10">
            <div className="flex justify-between text-xs text-stone-400">
              <span>Gross Retail Revenue:</span>
              <span className="font-mono text-white font-bold">${totalRevenue.toLocaleString()}</span>
            </div>
            <div className="flex justify-between text-xs text-stone-400">
              <span>Wholesale Credits Cost:</span>
              <span className="font-mono text-stone-300">-${totalCost.toLocaleString()}</span>
            </div>
          </div>

          <div className="space-y-1">
            <div className="text-xs text-stone-400 font-medium">Estimated Net Profit:</div>
            <div className="font-mono text-4xl sm:text-5xl font-black text-emerald-400 tracking-tight">
              ${profit.toLocaleString()}
            </div>
            <div className="text-[11px] text-stone-500 font-mono">
              Return on Investment: <strong className="text-emerald-400 font-bold">{margin}% Margin</strong>
            </div>
          </div>

          <div className="pt-2">
            <Link
              href={APP_CONFIG.resellerPortalUrl}
              className="btn-dx w-full flex items-center justify-center gap-2 py-3.5 rounded-xl font-bold text-xs uppercase tracking-wider shadow-lg"
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
