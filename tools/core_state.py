import subprocess, sys

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()
print("pid =", pid)

# 找出 worker 的 4 個大容量 epoll
import re as _re
epolls = []
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = _re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    fd = int(m.group(1))
    n = sh(f"run-as com.tokyoxpa3.androidproxy grep -c tfd /proc/{pid}/fdinfo/{fd}").strip()
    try:
        n = int(n)
    except ValueError:
        continue
    epolls.append((fd, n))
epolls.sort(key=lambda x: -x[1])
print("前 6 大 epoll 註冊數:", epolls[:6])

from collections import Counter
c = Counter()
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for line in sh(f"cat {tbl}").splitlines()[1:]:
        p = line.split()
        if len(p) >= 4:
            c[p[3]] += 1
states = {"01": "ESTAB", "02": "SYN_SENT", "03": "SYN_RECV", "06": "TIME_WAIT",
          "08": "CLOSE_WAIT", "0A": "LISTEN"}
print("TCP 狀態:", {states.get(k, k): v for k, v in c.items()})
