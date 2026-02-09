#!/bin/bash

# DebridXtreme GitHub Backup Script
# This script will backup your code to GitHub

echo "════════════════════════════════════════"
echo "  DebridXtreme GitHub Backup Script"
echo "════════════════════════════════════════"
echo ""

cd /home/alik_iving_room/debxtrem

# Clean build directories before backup
rm -rf app/build .gradle build 2>/dev/null

# Check if git is initialized
if [ ! -d ".git" ]; then
    echo "❌ Git not initialized in this directory!"
    exit 1
fi

# Check for changes
echo "📋 Checking for changes..."
if git diff-index --quiet HEAD --; then
    echo "✅ No changes to backup"
else
    echo "📦 Found changes. Adding files..."
    git add .
    
    # Get backup message or use default
    if [ -z "$1" ]; then
        BACKUP_MSG="Backup: $(date '+%Y-%m-%d %H:%M:%S')"
    else
        BACKUP_MSG="$1"
    fi
    
    echo "💾 Committing changes..."
    git commit -m "$BACKUP_MSG"
    
    echo "☁️ Pushing to GitHub..."
    if GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no" git push; then
        echo ""
        echo "════════════════════════════════════════"
        echo "✅ Backup successful!"
        echo "════════════════════════════════════════"
        echo ""
        echo "Your code is now safely backed up on GitHub!"
        echo "Last backup: $(date '+%Y-%m-%d %H:%M:%S')"
    else
        echo ""
        echo "════════════════════════════════════════"
        echo "⚠️ Push failed!"
        echo "════════════════════════════════════════"
        echo ""
        echo "This might be your first push. Please run:"
        echo ""
        echo "git remote add origin https://github.com/YOUR_USERNAME/debridxtreme.git"
        echo "git push -u origin main"
        echo ""
        echo "See GITHUB_BACKUP_GUIDE.md for detailed instructions."
    fi
fi

echo ""
echo "📊 Repository Status:"
git log --oneline -5
echo ""
echo "📁 Remote:"
git remote -v

