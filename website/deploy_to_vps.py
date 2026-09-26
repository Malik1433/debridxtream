import os
import tarfile
import paramiko
import time
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

host = os.environ.get("VPS_HOST", "209.74.88.56")
user = os.environ.get("VPS_USER", "root")
# Credentials never live in this file: the repository is public. Prefer an SSH key
# (VPS_KEY_FILE, or the default ~/.ssh keys / agent); VPS_PASSWORD is a fallback only.
pwd = os.environ.get("VPS_PASSWORD") or None
key_file = os.environ.get("VPS_KEY_FILE") or None

project_dir = os.path.dirname(os.path.abspath(__file__))
archive_path = os.path.join(project_dir, "dxplay_deploy.tar.gz")

ignore_names = {
    "node_modules",
    ".next",
    ".git",
    "dxplay_deploy.tar.gz",
    "tsconfig.tsbuildinfo",
    ".gemini",
    "check_vps.py"
}

def filter_tar(tarinfo):
    name = os.path.basename(tarinfo.name)
    if name in ignore_names:
        return None
    if name.endswith(".log"):
        return None
    return tarinfo

print(f"\n📦 Step 1: Packaging DX Play website from {project_dir}...", flush=True)
if os.path.exists(archive_path):
    os.remove(archive_path)

with tarfile.open(archive_path, "w:gz") as tar:
    for item in os.listdir(project_dir):
        if item in ignore_names:
            continue
        item_path = os.path.join(project_dir, item)
        tar.add(item_path, arcname=item, filter=filter_tar)

archive_size_mb = os.path.getsize(archive_path) / (1024 * 1024)
print(f"✅ Bundle ready: {archive_size_mb:.2f} MB", flush=True)

print(f"\n🔌 Step 2: Connecting to VPS ({host})...", flush=True)
ssh = paramiko.SSHClient()
# Known hosts only: an unknown or changed host key is refused instead of silently trusted.
# First time from a new machine, run `ssh <user>@<host>` once to record the server's key.
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect(host, port=22, username=user, password=pwd, key_filename=key_file, timeout=30)

def run_cmd(cmd, timeout=600):
    print(f"\n[SERVER] {cmd}", flush=True)
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    while not stdout.channel.exit_status_ready():
        if stdout.channel.recv_ready():
            chunk = stdout.channel.recv(2048).decode('utf-8', errors='replace')
            sys.stdout.write(chunk)
            sys.stdout.flush()
        time.sleep(0.2)
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out:
        sys.stdout.write(out)
        sys.stdout.flush()
    if err:
        sys.stderr.write(f"\n[STDERR] {err}\n")
        sys.stderr.flush()
    exit_status = stdout.channel.recv_exit_status()
    if exit_status != 0:
        raise Exception(f"Command failed with code {exit_status}")
    return out

try:
    print("\n📂 Step 3: Preparing /var/www/dxplay directory on VPS...", flush=True)
    run_cmd("mkdir -p /var/www/dxplay")

    print("\n🚀 Step 4: Uploading bundle to VPS...", flush=True)
    sftp = ssh.open_sftp()
    remote_tar = "/var/www/dxplay/dxplay_deploy.tar.gz"

    def progress_callback(transferred, total):
        percent = (transferred / total) * 100
        sys.stdout.write(f"\rUploading: {percent:.1f}% ({transferred/(1024*1024):.2f}/{total/(1024*1024):.2f} MB)")
        sys.stdout.flush()

    sftp.put(archive_path, remote_tar, callback=progress_callback)
    print("\n✅ Upload complete!", flush=True)
    sftp.close()

    print("\n📂 Step 5: Extracting files...", flush=True)
    run_cmd("cd /var/www/dxplay && tar -xzf dxplay_deploy.tar.gz && rm -f dxplay_deploy.tar.gz")

    print("\n📦 Step 6: Installing Node.js dependencies...", flush=True)
    run_cmd("cd /var/www/dxplay && npm install --production=false")

    print("\n🔨 Step 7: Building Next.js application...", flush=True)
    run_cmd("cd /var/www/dxplay && npm run build")

    print("\n⚡ Step 8: Starting DX Play on PM2 (Port 5501)...", flush=True)
    run_cmd("pm2 delete dxplay-website 2>/dev/null || true")
    run_cmd("cd /var/www/dxplay && pm2 start deployment/ecosystem.config.js")
    run_cmd("pm2 save")

    print("\n🌐 Step 9: Configuring Nginx for dxplay.xyz...", flush=True)
    run_cmd("cp /var/www/dxplay/deployment/nginx-dxplay.conf /etc/nginx/sites-available/dxplay.conf")
    run_cmd("ln -sf /etc/nginx/sites-available/dxplay.conf /etc/nginx/sites-enabled/dxplay.conf")
    run_cmd("nginx -t")
    run_cmd("systemctl reload nginx")

    print("\n🩺 Step 10: Running Health Checks on VPS...", flush=True)
    run_cmd("pm2 status")
    run_cmd("curl -I http://127.0.0.1:5501/")
    run_cmd("curl -I -H 'Host: dxplay.xyz' http://127.0.0.1:80/")
    
    print("\n🩺 Verifying Existing Site (tvonnet.xyz) Remains 100% Operational...", flush=True)
    run_cmd("curl -I -H 'Host: www.tvonnet.xyz' http://127.0.0.1:80/")

    print("\n🎉 ========================================================", flush=True)
    print("🎉 DX PLAY DEPLOYMENT ON VPS COMPLETED SUCCESSFULLY!", flush=True)
    print(f"🎉 Both websites are now live side-by-side on VPS {host}!", flush=True)
    print("🎉 ========================================================\n", flush=True)

finally:
    ssh.close()
    if os.path.exists(archive_path):
        os.remove(archive_path)
