import type { Metadata } from "next";
import "./globals.css";
import Navbar from "@/components/Navbar";
import Footer from "@/components/Footer";
import { APP_CONFIG } from "@/lib/constants";

export const metadata: Metadata = {
  metadataBase: new URL("https://www.dxplay.xyz"),
  title: `${APP_CONFIG.name} — ${APP_CONFIG.tagline}`,
  description: APP_CONFIG.description,
  keywords: [
    "DX Play",
    "DebridXtream",
    "IPTV Player Android TV",
    "FireStick Downloader Code 4282574",
    "Real-Debrid Player",
    "StremThru",
    "Debridio",
    "Xtream Codes IPTV",
    "TiviMate Alternative",
    "4K HDR Media3 Player",
  ],
  authors: [{ name: "DX Play Engineering Team" }],
  icons: {
    icon: "/brand/logo-icon-cinema.png",
    shortcut: "/brand/logo-icon-cinema.png",
    apple: "/brand/logo-icon-cinema.png",
  },
  openGraph: {
    title: `${APP_CONFIG.name} — ${APP_CONFIG.tagline}`,
    description: APP_CONFIG.description,
    url: "https://www.dxplay.xyz",
    siteName: APP_CONFIG.name,
    images: [
      {
        url: "/brand/logo-icon-cinema.png",
        width: 512,
        height: 512,
        alt: "DX Play Official Logo",
      },
    ],
    locale: "en_US",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark scroll-smooth">
      <head>
        <link rel="icon" href="/brand/logo-icon-cinema.png" />
      </head>
      <body className="bg-[#0A0908] text-white min-h-screen flex flex-col antialiased selection:bg-[#EC3013]/30">
        <Navbar />
        <main className="flex-1">{children}</main>
        <Footer />
      </body>
    </html>
  );
}
