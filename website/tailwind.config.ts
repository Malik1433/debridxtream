import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        dx: {
          bg: "#0A0908",
          surface: "#141211",
          card: "rgba(22, 20, 19, 0.75)",
          border: "rgba(255, 255, 255, 0.08)",
          borderHover: "rgba(236, 48, 19, 0.4)",
          red: "#EC3013",
          redHover: "#DD260B",
          redDark: "#851505",
          redGlow: "rgba(236, 48, 19, 0.35)",
          text: "#FFFFFF",
          muted: "#9E9995",
          subtle: "#54504D",
        },
      },
      fontFamily: {
        sans: ["var(--font-inter)", "system-ui", "sans-serif"],
        mono: ["var(--font-mono)", "ui-monospace", "monospace"],
      },
      boxShadow: {
        glow: "0 0 35px -5px rgba(236, 48, 19, 0.35)",
        "glow-lg": "0 0 60px -10px rgba(236, 48, 19, 0.45)",
        glass: "0 8px 32px 0 rgba(0, 0, 0, 0.45)",
      },
      animation: {
        "pulse-subtle": "pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite",
        float: "float 6s ease-in-out infinite",
      },
      keyframes: {
        float: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-8px)" },
        },
      },
    },
  },
  plugins: [],
};
export default config;
