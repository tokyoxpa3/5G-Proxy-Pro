import subprocess, sys, re

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()

# 1) finalize log 中 C 端關閉的 inode（client 與 target）
fin_inodes = set()
for line in sh("logcat -d -s SimpleSocks5:*").splitlines():
    m = re.search(r"finalize .*cfd=-?\d+\(ino=(\d+)\) tfd=-?\d+\(ino=(\d+)\)", line)
    if m:
        fin_inodes.add(m.group(1))
        fin_inodes.add(m.group(2))
print(f"finalize 紀錄過的 inode 數: {len(fin_inodes)}")

# 2) 目前行程持有的 fd -> inode
fd_inode = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> socket:\[(\d+)\]", line)
    if m:
        fd_inode[int(m.group(1))] = m.group(2)

# 3) tcp 表
tcp = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for line in sh(f"cat {tbl}").splitlines()[1:]:
        p = line.split()
        if len(p) >= 10:
            tcp[p[9]] = p[3]  # state

cw_in_process = {ino for fd, ino in fd_inode.items() if tcp.get(ino) == "08"}
overlap = cw_in_process & fin_inodes
print(f"行程內 CLOSE_WAIT inode 數: {len(cw_in_process)}")
print(f"其中曾被 finalize close 過的: {len(overlap)}")
print("=> 若 > 0：C 的 close() 對這些 fd 無效（有其他引用/dup）")
print("=> 若 = 0：洩漏的是從未經 finalize 的 socket（敗者/其他來源）")

# 樣本對照
for i, ino in enumerate(sorted(overlap)[:5]):
    fds = [f for f, x in fd_inode.items() if x == ino]
    print(f"  例: inode={ino} 目前fd={fds}")
if not overlap:
    for i, (ino) in enumerate(sorted(cw_in_process)[:5]):
        fds = [f for f, x in fd_inode.items() if x == ino]
        print(f"  洩漏例: inode={ino} 目前fd={fds} state={tcp.get(ino)}")
