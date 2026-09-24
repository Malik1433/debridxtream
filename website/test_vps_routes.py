import paramiko
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('209.74.88.56', 22, 'root', '4d8s0U6B8vs7gRMYNm')

routes = ['/', '/link', '/admin', '/account', '/reseller/login', '/setup-guide', '/download', '/pricing']
for r in routes:
    _, out, _ = s.exec_command(f'curl -s -o /dev/null -w "%{{http_code}}" http://127.0.0.1:5501{r}')
    code = out.read().decode().strip()
    print(f"{r:20} -> HTTP {code}")

s.close()
