/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  reactStrictMode: true,
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [
      {
        source: "/admin",
        destination: "/admin/index.html",
      },
      {
        source: "/admin/:path*",
        destination: "/admin/index.html",
      },
      {
        source: "/link",
        destination: "/app.html",
      },
      {
        source: "/link/:path*",
        destination: "/app.html",
      },
      {
        source: "/account",
        destination: "/app.html",
      },
      {
        source: "/account/:path*",
        destination: "/app.html",
      },
      {
        source: "/signup",
        destination: "/app.html",
      },
      {
        source: "/reseller/login",
        destination: "/app.html",
      },
      {
        source: "/reseller/signup",
        destination: "/app.html",
      },
      {
        source: "/reseller/verify",
        destination: "/app.html",
      },
      {
        source: "/reseller/dashboard",
        destination: "/app.html",
      },
    ];
  },
};



export default nextConfig;

