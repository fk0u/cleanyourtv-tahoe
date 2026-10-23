#!/usr/bin/env python3
"""Run a shell command on the TV: tv.py 'cmd'  |  tv.py --screenshot out.png [KEY ...]"""
import os, sys, base64, pathlib
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
HOST = os.environ.get("TV_HOST") or sys.exit("set TV_HOST to the TV's IP, e.g. export TV_HOST=192.168.1.50")
PORT = int(os.environ.get("TV_PORT", 5555))
key = pathlib.Path.home() / ".android/adbkey"
if not key.exists():
    key.parent.mkdir(exist_ok=True); keygen(str(key))
signer = PythonRSASigner(open(str(key) + ".pub").read(), key.read_text())
dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=30)
dev.connect(rsa_keys=[signer], auth_timeout_s=60)
sh = lambda c, t=120: dev.shell(c, timeout_s=t, read_timeout_s=t)
a = sys.argv[1:]
if a and a[0] == "--screenshot":
    out, keys = a[1], a[2:]
    for k in keys: sh(f"input keyevent KEYCODE_{k}"); sh("sleep 0.6")
    sh("screencap -p /sdcard/s.png")
    data = base64.b64decode(sh("base64 /sdcard/s.png | tr -d '\\n'"))
    if not data: sys.exit("empty screenshot (protected content: Live TV/HDMI/DRM)")
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True); pathlib.Path(out).write_bytes(data); print(out)
elif a and a[0] in ("--install", "--push"):
    import hashlib
    def push(local, remote):  # base64 chunks through the shell; adb-shell push can hang
        data = pathlib.Path(local).read_bytes(); b64 = base64.b64encode(data).decode()
        sh("rm -f /data/local/tmp/i.b64")
        for i in range(0, len(b64), 60000):
            sh(f"echo -n '{b64[i:i+60000]}' >> /data/local/tmp/i.b64")
        sh(f"base64 -d /data/local/tmp/i.b64 > '{remote}'; rm /data/local/tmp/i.b64")
        assert sh(f"md5sum '{remote}'").split()[0] == hashlib.md5(data).hexdigest(), f"md5 mismatch: {local}"
    if a[0] == "--install":
        push(a[1], "/data/local/tmp/i.apk"); print(sh("pm install -r /data/local/tmp/i.apk"), end="")
    else:  # --push remote_dir file...
        sh(f"mkdir -p '{a[1]}'")
        for f in a[2:]: push(f, f"{a[1]}/{pathlib.Path(f).name}"); print("pushed", f)
else:
    print(sh(" ".join(a)), end="")
