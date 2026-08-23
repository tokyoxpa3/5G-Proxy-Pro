import subprocess, sys

ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"
INODES = ["879093", "904314", "919402", "921886"]

def sh(cmd):
    r = subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True)
    return r.stdout

# 讀取 tcp/tcp6 表，建立 inode -> (state, local, remote)
rows = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for line in sh("cat %s" % tbl).splitlines()[1:]:
        p = line.split()
        if len(p) < 10:
            continue
        rows[p[9]] = (tbl[-1], p[3], p[1], p[2])  # v4/v6, state, local, remote

states = {"01": "ESTAB", "02": "SYN_SENT", "03": "SYN_RECV", "04": "FIN_W1",
          "05": "FIN_W2", "06": "TIME_WAIT", "07": "CLOSE", "08": "CLOSE_WAIT",
          "0A": "LISTEN"}

for ino in INODES:
    if ino in rows:
        v, st, loc, rem = rows[ino]
        print(f"inode {ino}: {v} {states.get(st, st):10s} local={loc} remote={rem}")
    else:
        print(f"inode {ino}: NOT IN TCP TABLES")

# 統計此 uid 所有 socket 的狀態分布
from collections import Counter
c = Counter()
for ino, (v, st, loc, rem) in rows.items():
    c[states.get(st, st)] += 1
print("\n全域 TCP 狀態統計:", dict(c))
