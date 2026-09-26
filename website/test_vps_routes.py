import os
import paramiko
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

s = paramiko.SSHClient()
# Credentials come from the environment (see deploy_to_vps.py) - the repository is public.
s.load_system_host_keys()
s.set_missing_host_key_policy(paramiko.RejectPolicy())
s.connect(os.environ.get("VPS_HOST", "209.74.88.56"), 22, os.environ.get("VPS_USER", "root"),
          os.environ.get("VPS_PASSWORD") or None, key_filename=os.environ.get("VPS_KEY_FILE") or None)

routes = ['/', '/link', '/admin', '/account', '/reseller/login', '/setup-guide', '/download', '/pricing']
for r in routes:
    _, out, _ = s.exec_command(f'curl -s -o /dev/null -w "%{{http_code}}" http://127.0.0.1:5501{r}')
    code = out.read().decode().strip()
    print(f"{r:20} -> HTTP {code}")

s.close()
