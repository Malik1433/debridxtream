# DX Play Website — VPS Deployment Guide

Yeh guide aapko kisi bhi Linux VPS (Ubuntu 22.04 / 24.04 ya Debian) par **DX Play** ki website live karne ka mukammal tareeqa batati hai.

---

## 🚀 Quick 1-Click Method

1. **Website files ko VPS par copy karein:**
   ```bash
   scp -r website/* user@your-vps-ip:/var/www/dxplay/
   ```
2. **VPS par SSH karein aur deploy script run karein:**
   ```bash
   ssh user@your-vps-ip
   cd /var/www/dxplay
   chmod +x deployment/deploy.sh
   ./deployment/deploy.sh
   ```

---

## 🛠️ Step-by-Step Manual Method

### 1. Requirements Install Karein
```bash
sudo apt update && sudo apt upgrade -y
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs nginx certbot python3-certbot-nginx
sudo npm install -g pm2
```

### 2. Website Directory Setup & Build
```bash
sudo mkdir -p /var/www/dxplay
sudo chown -R $USER:$USER /var/www/dxplay
cd /var/www/dxplay

# Dependencies install karein
npm install

# Next.js standalone build banayein (Memory usage < 120MB)
npm run build
```

### 3. PM2 Process Manager se Run Karein (24/7 Live)
```bash
pm2 start deployment/ecosystem.config.js
pm2 save
pm2 startup
```
*Port 5500 par site start ho jayegi aur VPS reboot hone par khud ba khud dobara chal paregi.*

### 4. DNS Settings (Domain Pointing)
Apne Domain Registrar (Namecheap, Cloudflare, GoDaddy etc.) mein ja kar:
- **A Record:** `@` -> `Your VPS IP Address`
- **A Record:** `www` -> `Your VPS IP Address`

### 5. Nginx Reverse Proxy Setup (dxplay.xyz)
1. Nginx config copy karein:
   ```bash
   sudo cp deployment/nginx-dxplay.conf /etc/nginx/sites-available/dxplay.conf
   ```
2. Enable karein aur Nginx test karein:
   ```bash
   sudo ln -s /etc/nginx/sites-available/dxplay.conf /etc/nginx/sites-enabled/
   sudo nginx -t
   sudo systemctl restart nginx
   ```

### 6. Free SSL Certificate (HTTPS)
```bash
sudo certbot --nginx -d dxplay.xyz -d www.dxplay.xyz
```


---

## 📊 Useful VPS Commands

| Command | Purpose |
|---------|---------|
| `pm2 status` | Website ka live status aur RAM/CPU check karna |
| `pm2 logs dxplay-website` | Live server logs dekhna |
| `pm2 restart dxplay-website` | Website ko restart karna |
| `sudo systemctl status nginx` | Nginx web server check karna |
