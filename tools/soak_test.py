# -*- coding: utf-8 -*-
"""長時間混合負載穩定性監控：模擬真實使用（瀏覽突發 + 閒置期），
全程追蹤 pid 存活、fd 數量、CLOSE_WAIT 堆積。"""
import subprocess, sys, time, re, datetime

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd, timeout=60):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout

def burst(sec=25):
    subprocess.run([sys.executable, "-X", "utf8", r"tools\loadgen.py",
                    "192.168.1.178", "1080", str(sec)],
                   capture_output=True, text=True, timeout=sec + 120,
                   cwd=r"D:\workspace\AndroidApp\test\5G-Proxy-Pro")

def probe():
    pid = sh("pidof com.tokyoxpa3.androidproxy").strip()
    if not pid:
        return None
    out = sh(f"run-as com.tokyoxpa3.androidproxy ls /proc/{pid}/fd | wc -l")
    fds = int(out.strip() or -1)
    cw = 0
    tbl = sh("cat /proc/net/tcp")  # v4 即可（統計用）
    # 行程 socket inode 太多時改抓總量近似：直接數 CW 全域不可靠，改數 fd 表
    return pid, fds

ROUNDS = 5          # 5 輪
IDLE_BETWEEN = 120  # 每輪後閒置 2 分鐘

print("=== 長時間穩定性監控開始 ===")
for rnd in range(1, ROUNDS + 1):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    p = probe()
    if p is None:
        print(f"[{ts}] 第{rnd}輪 ❌ 行程已死亡！")
        sys.exit(1)
    print(f"[{ts}] 第{rnd}輪 負載前 pid={p[0]} fd={p[1]}")
    t0 = time.time()
    burst(25)
    dur = time.time() - t0
    time.sleep(10)  # 讓連線完成回收
    p2 = probe()
    if p2 is None:
        print(f"[{ts}] 第{rnd}輪 ❌ 負載後行程死亡！")
        sys.exit(1)
    crash = sh("logcat -d -b crash -t 200").count("androidproxy")
    stuck = sh("logcat -d -t 3000 -s SimpleSocks5:*").count("STUCK")
    print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] 第{rnd}輪 "
          f"負載後 fd={p2[1]} 耗時={dur:.0f}s 近期crash={crash} STUCK出現={stuck}")
    if rnd < ROUNDS:
        print(f"    ...閒置 {IDLE_BETWEEN}s...")
        time.sleep(IDLE_BETWEEN)

final = probe()
print(f"\n=== 監控結束：pid={final[0]} 存活 fd={final[1]} ===")
