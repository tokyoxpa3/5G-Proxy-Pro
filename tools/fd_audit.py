import subprocess, sys, re
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

r = subprocess.run([ADB, "-s", DEV, "shell", "logcat -d -s FdAudit:*"], capture_output=True, text=True)
created, closed = [], []
unknown = []
for line in r.stdout.splitlines():
    m = re.search(r"created fd=(\d+)", line)
    if m:
        created.append(int(m.group(1)))
        continue
    m = re.search(r"closed   fd=(\d+)", line)
    if m:
        closed.append(int(m.group(1)))
        continue
    if "UNKNOWN" in line:
        unknown.append(line.strip())

cset, clset = set(created), set(closed)
leaked = cset - clset
print(f"created={len(created)} closed={len(closed)} unknown_close_req={len(unknown)}")
print(f"created-but-never-closed: {len(leaked)} -> {sorted(leaked)[:20]}")
print(f"closed-but-never-created (reuse?): {sorted(clset - cset)[:10]}")
for u in unknown[:5]:
    print(u)
