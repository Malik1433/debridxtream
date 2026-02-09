# GitHub Backup Guide - DebridXtreme Project

## ✅ Step 1: Local Backup Complete
Your project has been committed to Git locally with:
- **100 files** backed up
- **9,869 lines of code** saved
- Commit ID: `b525eb3`

## 📋 Step 2: Create GitHub Repository

### Option A: Using GitHub Website (Easiest)
1. Go to https://github.com
2. Click the **"+"** icon in top right → **"New repository"**
3. Fill in the details:
   - **Repository name**: `debridxtreme` (or any name you prefer)
   - **Description**: Complete DebridXtreme IPTV Android TV Application
   - **Visibility**: Choose **Private** (recommended) or Public
   - ⚠️ **DO NOT** check "Initialize with README"
   - ⚠️ **DO NOT** add .gitignore (we already have one)
4. Click **"Create repository"**

### Option B: Using GitHub CLI (If installed)
```bash
gh repo create debridxtreme --private --source=. --remote=origin
```

## 📤 Step 3: Push Your Code to GitHub

After creating the repository, GitHub will show you commands. Use these:

### For NEW repository (first time):
```bash
cd /home/alik_iving_room/debxtrem
git remote add origin https://github.com/YOUR_USERNAME/debridxtreme.git
git branch -M main
git push -u origin main
```

### If you already have a repository:
```bash
cd /home/alik_iving_room/debxtrem
git remote add origin https://github.com/YOUR_USERNAME/debridxtreme.git
git push -u origin plan/iptv-debrid-mixer-20251007-011549
```

**Note**: Replace `YOUR_USERNAME` with your actual GitHub username.

## 🔐 Authentication Options

### Option 1: Personal Access Token (Recommended)
1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Give it a name: "DebridXtreme Backup"
4. Select scopes: ✅ **repo** (full control of private repositories)
5. Click "Generate token"
6. **COPY THE TOKEN** (you won't see it again!)
7. When pushing, use token as password:
   - Username: your GitHub username
   - Password: paste the token

### Option 2: SSH (More Secure)
```bash
# Generate SSH key
ssh-keygen -t ed25519 -C "your_email@example.com"

# Copy the public key
cat ~/.ssh/id_ed25519.pub

# Add to GitHub: Settings → SSH and GPG keys → New SSH key
# Then use SSH URL instead:
git remote set-url origin git@github.com:YOUR_USERNAME/debridxtreme.git
git push -u origin main
```

## 🔄 Future Updates - How to Backup New Changes

Whenever you make changes, run these commands:

```bash
cd /home/alik_iving_room/debxtrem

# Check what changed
git status

# Add all changes
git add .

# Commit with a message
git commit -m "Description of your changes"

# Push to GitHub
git push
```

## 🚀 Quick Backup Script

Save this as `backup.sh` in your project:

```bash
#!/bin/bash
cd /home/alik_iving_room/debxtrem
echo "📦 Adding changes..."
git add .
echo "💾 Committing..."
git commit -m "Backup: $(date '+%Y-%m-%d %H:%M:%S')"
echo "☁️ Pushing to GitHub..."
git push
echo "✅ Backup complete!"
```

Make it executable:
```bash
chmod +x backup.sh
```

Run it anytime:
```bash
./backup.sh
```

## 🔄 How to Restore Your Project

If your project folder gets deleted, you can restore it:

```bash
# Clone from GitHub
git clone https://github.com/YOUR_USERNAME/debridxtreme.git

# Go into the project
cd debridxtreme

# Verify all files are there
ls -la
```

## 📊 Verify Your Backup

After pushing to GitHub, verify:

1. Go to your repository on GitHub: `https://github.com/YOUR_USERNAME/debridxtreme`
2. Check that all files are visible
3. Check the commit count (should show at least 1 commit)
4. Browse through folders to ensure everything is there

## ⚠️ Important Files Backed Up

Your backup includes:
- ✅ All source code (Kotlin/Java files)
- ✅ Android manifest and configurations
- ✅ Gradle build files
- ✅ Resources (layouts, drawables, strings)
- ✅ Documentation and guides
- ✅ Project structure
- ❌ Build outputs (excluded via .gitignore)
- ❌ Gradle cache (excluded via .gitignore)
- ❌ IDE-specific files (excluded via .gitignore)

## 🆘 Troubleshooting

### Error: "remote origin already exists"
```bash
git remote remove origin
git remote add origin https://github.com/YOUR_USERNAME/debridxtreme.git
```

### Error: "failed to push"
```bash
# Pull first, then push
git pull origin main --allow-unrelated-histories
git push origin main
```

### Check remote status
```bash
git remote -v
```

## 🎉 Success Checklist

- [ ] Created GitHub repository
- [ ] Added remote origin
- [ ] Pushed code to GitHub
- [ ] Verified files on GitHub website
- [ ] Tested clone command works
- [ ] Saved access token/SSH key securely

---

**Your project is now backed up and safe!** Even if your entire project folder is deleted, you can restore it from GitHub anytime. 🎉

