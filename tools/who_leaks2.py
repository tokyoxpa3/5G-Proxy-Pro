import subprocess, sys, re

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()

# 所有 worker epoll 的註冊 fd 集合
reg_fds = set()
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> .*eventpoll", line)
    if not m:
        continue
    efd = m.group(1)
    out = sh(f"run-as com.tokyoxpa3.androidproxy cat /proc/{pid}/fdinfo/{efd}")
    for l in out.splitlines():
        mm = re.match(r"\s*tfd:\s+(\d+)", l)
        if mm:
            reg_fds.add(int(mm.group(1)))

# 行程內 CLOSE_WAIT 的 fd
fd_state = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> socket:\[(\d+)\]", line)
    if m:
        fd_state[int(m.group(1))] = m.group(2)
tcp = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for l in sh(f"cat {tbl}").splitlines()[1:]:
        p = l.split()
        if len(p) >= 10:
            tcp[p[9]] = p[3]
cw = {f for f, ino in fd_state.items() if tcp.get(ino) == "08"}

was_reg = cw & reg_fds
never_reg = cw - reg_fds
print(f"CLOSE_WAIT fd 總數: {len(cw)}")
print(f"  曾在 epoll 註冊（=曾經的 target 勝者）: {len(was_reg)}")
print(f"  從未註冊（敗者/DNS/App 自身流量等）: {len(never_reg)}")
print("從未註冊樣本:", sorted(never_reg)[:15])
print("曾註冊樣本:", sorted(was_reg)[:10])
