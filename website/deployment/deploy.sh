#!/usr/bin/env bash
# ==============================================================================
# DX Play Website — 1-Click Production VPS Deploy Script (Ubuntu/Debian)
# ==============================================================================
set -e

echo "🚀 [1/5] Checking Node.js and PM2..."
if ! command -v node &> /dev/null; then
    echo "Installing Node.js 20 LTS..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
    sudo apt-get install -y nodejs
fi

if ! command -v pm2 &> /dev/null; then
    echo "Installing PM2 globally..."
    sudo npm install -g pm2
fi

echo "📦 [2/5] Installing dependencies..."
npm install --production=false

echo "🔨 [3/5] Building Next.js standalone production release..."
npm run build

echo "⚡ [4/5] Starting application on Port 5500 via PM2..."
pm2 delete dxplay-website 2>/dev/null || true
pm2 start deployment/ecosystem.config.js
pm2 save

echo "🎉 [5/5] DX Play Website is LIVE on port 5500!"
echo "Check status with: pm2 status"
echo "View logs with: pm2 logs dxplay-website"
