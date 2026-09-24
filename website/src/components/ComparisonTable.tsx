import React from "react";
import { Check, X, Sparkles, HelpCircle } from "lucide-react";
import { COMPARISON_DATA } from "@/lib/constants";

export default function ComparisonTable() {
  return (
    <section className="py-20 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-16 space-y-4">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-white/5 border border-white/10 text-stone-300 text-xs font-bold uppercase tracking-wider">
            <Sparkles className="w-3.5 h-3.5 text-[#EC3013]" />
            Side-By-Side Benchmark
          </div>
          <h2 className="text-3xl sm:text-5xl font-black text-white tracking-tight">
            How DX Play Compares to the Competition
          </h2>
          <p className="text-stone-400 text-base sm:text-lg">
            See why power streamers and resellers are switching from legacy players to DX Play.
          </p>
        </div>

        <div className="overflow-x-auto">
          <div className="inline-block min-w-full align-middle">
            <div className="rounded-3xl border border-white/10 bg-[#12100F]/90 backdrop-blur-xl overflow-hidden shadow-2xl">
              <table className="min-w-full divide-y divide-white/10 text-left">
                <thead>
                  <tr className="bg-white/[0.02]">
                    <th scope="col" className="py-5 pl-6 pr-3 text-sm font-bold text-stone-400 uppercase tracking-wider w-2/5">
                      Core Architecture & Capabilities
                    </th>
                    <th scope="col" className="px-4 py-5 text-center text-sm font-extrabold text-white bg-[#EC3013]/10 border-x border-[#EC3013]/30 w-1/5">
                      <div className="text-[#EC3013] text-base tracking-tight">DX Play</div>
                      <div className="text-[10px] text-stone-400 font-normal">Next-Gen Hybrid</div>
                    </th>
                    <th scope="col" className="px-4 py-5 text-center text-sm font-semibold text-stone-400 w-1/5">
                      TiviMate
                    </th>
                    <th scope="col" className="px-4 py-5 text-center text-sm font-semibold text-stone-400 w-1/5">
                      IPTV Smarters
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {COMPARISON_DATA.map((row, idx) => (
                    <tr key={idx} className="hover:bg-white/[0.02] transition-colors">
                      <td className="py-4 pl-6 pr-3 text-sm font-medium text-white space-y-1">
                        <div>{row.feature}</div>
                        <div className="text-xs text-stone-500 font-normal">{row.detail}</div>
                      </td>

                      {/* DX Play Column */}
                      <td className="px-4 py-4 text-center bg-[#EC3013]/5 border-x border-[#EC3013]/20">
                        {row.dxPlay ? (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-[#EC3013]/20 text-[#EC3013]">
                            <Check className="w-4 h-4 stroke-[3]" />
                          </div>
                        ) : (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-white/5 text-stone-600">
                            <X className="w-4 h-4" />
                          </div>
                        )}
                      </td>

                      {/* TiviMate Column */}
                      <td className="px-4 py-4 text-center">
                        {row.tivimate ? (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-white/10 text-stone-300">
                            <Check className="w-4 h-4" />
                          </div>
                        ) : (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-white/5 text-stone-600">
                            <X className="w-4 h-4" />
                          </div>
                        )}
                      </td>

                      {/* Smarters Column */}
                      <td className="px-4 py-4 text-center">
                        {row.smarters ? (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-white/10 text-stone-300">
                            <Check className="w-4 h-4" />
                          </div>
                        ) : (
                          <div className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-white/5 text-stone-600">
                            <X className="w-4 h-4" />
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
