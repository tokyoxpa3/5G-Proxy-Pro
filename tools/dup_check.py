import subprocess, sys, re
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()

# fd -> inode
fd_inode = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> socket:\[(\d+)\]", line)
    if m:
        fd_inode[int(m.group(1))] = m.group(2)

# worker epoll 註冊集
reg_fds = set()
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{m.group(1)}")
    for l in out.splitlines():
        mm = re.match(r"\s*tfd:\s+(\d+)", l)
        if mm:
            reg_fds.add(int(mm.group(1)))

tcp = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for l in sh(f"cat {tbl}").splitlines()[1:]:
        p = l.split()
        if len(p) >= 10:
            tcp[p[9]] = p[3]

cw = {f: ino for f, ino in fd_inode.items() if tcp.get(ino) == "08"}
ghost_winners = {f: ino for f, ino in cw.items() if f in reg_fds}

print(f"CLOSE_WAIT={len(cw)}  其中幽靈勝者(仍在epoll註冊)={len(ghost_winners)}")

# inode -> 出現在哪幾個 fd（偵測 dup）
by_ino = defaultdict(list)
for f, ino in fd_inode.items():
    by_ino[ino].append(f)

dups = {ino: fds for ino, fds in by_ino.items() if len(fds) > 1}
print(f"\n共用同一 inode 的 fd 群組數（dup 證據）: {len(dups)}")
for ino, fds in list(dups.items())[:10]:
    print(f"  inode={ino} -> fds={fds}")

# 抽 5 個幽靈勝者，看它們的 inode 是否也出現在別的 fd 上
print("\n幽靈勝者樣本:")
for f in sorted(ghost_winners)[:8]:
    ino = ghost_winners[f]
    others = [x for x in by_ino.get(ino, []) if x != f]
    print(f"  fd={f:5d} inode={ino} 其他持有同inode的fd={others if others else '無'}")
