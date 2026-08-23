import subprocess, sys, re
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()
print("pid =", pid)

# 1) inode -> (state, local, remote)
tcp = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for line in sh(f"cat {tbl}").splitlines()[1:]:
        p = line.split()
        if len(p) >= 10:
            tcp[p[9]] = (p[3], p[1], p[2])

def dec_addr(a):
    ip, port = a.split(":")
    port = int(port, 16)
    b = bytes.fromhex(ip)
    if len(b) == 4:
        return ".".join(str(x) for x in reversed(b)), port
    # v4-mapped?
    if b[:12] == bytes.fromhex("0000000000000000ffff0000"):
        return ".".join(str(x) for x in reversed(b[12:])), port
    return "v6", port

# 2) 此行程所有 socket fd -> inode
fd_inode = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> socket:\[(\d+)\]", line)
    if m:
        fd_inode[int(m.group(1))] = m.group(2)

# 3) 找出 CLOSE_WAIT 的 fd
cw_fds = []
for fd, ino in fd_inode.items():
    if ino in tcp and tcp[ino][0] == "08":
        cw_fds.append((fd, ino))
print(f"行程持有的 CLOSE_WAIT fd 數: {len(cw_fds)} (總 fd/socket={len(fd_inode)})")

# 4) worker epoll 註冊表：fd -> data stamp
worker_epolls = []
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if m:
        efd = int(m.group(1))
        n = sh(f"run-as com.tokyoxpa3.androidproxy grep -c tfd /proc/{pid}/fdinfo/{efd}").strip()
        try:
            if int(n) > 50:
                worker_epolls.append(efd)
        except ValueError:
            pass
print("worker epolls:", worker_epolls)

reg = {}   # registered target fd -> data stamp
for efd in worker_epolls:
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{efd}")
    for line in out.splitlines():
        m = re.match(r"\s*tfd:\s+(\d+)\s+events:\s*\S+\s+data:\s+(\S+)", line)
        if m:
            reg.setdefault(int(m.group(1)), []).append((efd, m.group(2)))

# 5) 抽樣 12 條 CLOSE_WAIT：是否註冊於 epoll？位址為何？
sample = cw_fds[:12]
in_epoll = 0
for fd, ino in sample:
    st, loc, rem = tcp[ino]
    la, lp = dec_addr(loc)
    ra, rp = dec_addr(rem)
    e = reg.get(fd)
    if e:
        in_epoll += 1
    print(f"fd={fd:5d} CW local={la}:{lp} remote={ra}:{rp} epoll_reg={'YES '+str(e) if e else 'no'}")
print(f"\n抽樣中在 epoll 註冊的比例: {in_epoll}/{len(sample)}")

# 6) 全部 CLOSE_WAIT 的遠端分類
c = Counter()
for fd, ino in cw_fds:
    _, loc, rem = tcp[ino]
    ra, rp = dec_addr(rem)
    c[rp] += 1
print("CLOSE_WAIT 遠端 port 分布:", dict(c.most_common(8)))
