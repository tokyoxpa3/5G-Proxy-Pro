import subprocess, sys, re

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()
print("pid =", pid)

# 1) 所有 worker epoll：tfd -> data stamp 清單
regs = {}   # efd -> list of (tfd, data)
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    efd = int(m.group(1))
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{efd}")
    lst = []
    for l in out.splitlines():
        mm = re.match(r"\s*tfd:\s+(\d+)\s+events:\s*\S+\s+data:\s+(\S+)", l)
        if mm:
            lst.append((int(mm.group(1)), mm.group(2)))
    regs[efd] = lst

# 找出 4 個大 worker
workers = {e: l for e, l in regs.items() if len(l) > 5}
print("worker epolls:", {e: len(l) for e, l in workers.items()})

# 2) 行程 fd -> 目標
fd_map = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> (.+)$", line)
    if m:
        fd_map[int(m.group(1))] = m.group(2)

# 3) 每個 worker 的註冊中，抽樣列出（tfd, stamp, 目前fd指向）
for efd, lst in sorted(workers.items()):
    print(f"\n=== epoll {efd}（{len(lst)} 筆註冊）===")
    for tfd, data in lst[:12]:
        tgt = fd_map.get(tfd, "<已關閉的編號>")
        # 解碼 stamp
        try:
            v = int(data, 16)
            slot = v & 0xFFFFFFFF
            gen = v >> 32
            dec = f"slot={slot} gen={gen}"
        except ValueError:
            dec = "?"
        print(f"  tfd={tfd:5d} stamp={data:>14s} ({dec}) 現指向: {tgt}")
