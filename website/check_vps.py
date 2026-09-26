import os
import paramiko
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

print(f"Connecting to VPS {host}...")
ssh = paramiko.SSHClient()
# Known hosts only: an unknown or changed host key is refused instead of silently trusted.
# First time from a new machine, run `ssh <user>@<host>` once to record the server's key.
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect(host, port=22, username=user, password=pwd, key_filename=key_file, timeout=30)

def run(cmd):
    print(f"\n--- {cmd} ---")
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out:
        print(out)
    if err:
        print(f"[ERR] {err}")

run("pm2 status")
run("ss -tuln | grep -E ':(80|443|3000|5500)'")
run("ls -la /etc/nginx/sites-enabled/")
run("free -m")

ssh.close()
