import paramiko
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')


host = "209.74.88.56"
user = "root"
pwd = "4d8s0U6B8vs7gRMYNm"

print(f"Connecting to VPS {host}...")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, port=22, username=user, password=pwd, timeout=30)

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
