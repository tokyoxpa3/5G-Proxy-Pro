# -*- coding: utf-8 -*-
"""負載進行中對 epoll 註冊表取證：找出「槽位已釋放但仍有註冊」的幽靈條目"""
import subprocess, sys, re, threading, time

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"
PROJ = r"D:\workspace\AndroidApp\test\5G-Proxy-Pro"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

# 背景：12 秒負載
t = threading.Thread(target=lambda: subprocess.run(
    [sys.executable, "-X", "utf8", r"tools\loadgen.py", "192.168.1.178", "1080", "12"],
    capture_output=True, cwd=PROJ), daemon=True)
t.start()
time.sleep(6)  # 負載熱區中段取樣

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()
print(f"取樣時 pid={pid}")

fd_map = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> (.+)$", line)
    if m:
        fd_map[int(m.group(1))] = m.group(2)

tcp = {}
for tbl_ in ("/proc/net/tcp", "/proc/net/tcp6"):
    for l in sh(f"cat {tbl_}").splitlines()[1:]:
        p = l.split()
        if len(p) >= 10:
            tcp[p[9]] = p[3]

ghosts = []
total_regs = 0
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{m.group(1)}")
    for l in out.splitlines():
        mm = re.match(r"\s*tfd:\s+(\d+)\s+events:\s*\S+\s+data:\s+(\S+)", l)
        if not mm:
            continue
        total_regs += 1
        v = int(mm.group(2), 16)
        slot = v & 0xFFFFFFFF
        gen = v >> 32
        if not (0 <= slot < 1088):
            ghosts.append(("IDX越界", mm.group(1), mm.group(2), None))
print(f"\n總註冊數={total_regs}，idx 越界幽靈={len(ghosts)}")

# 檢查每個註冊的 tfd 是否仍存在於行程 fd 表
orphan_tfd = 0
alive_tfd = 0
samples = []
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{m.group(1)}")
    for l in out.splitlines():
        mm = re.match(r"\s*tfd:\s+(\d+)\s+events:\s*\S+\s+data:\s+(\S+)", l)
        if not mm:
            continue
        tfd = int(mm.group(1))
        if tfd in fd_map:
            alive_tfd += 1
            if len(samples) < 15:
                samples.append((tfd, mm.group(2), fd_map[tfd]))
        else:
            orphan_tfd += 1

print(f"註冊的 tfd 中：仍開啟={alive_tfd} 已不存在（fd編號空窗或已關閉）={orphan_tfd}")
print("\n仍開啟的註冊樣本（tfd, stamp, 目前指向）:")
for s in samples:
    print("  ", s)
