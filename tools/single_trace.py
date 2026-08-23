import subprocess, sys, re

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"D:\workspace\AndroidApp\test\android-sdk-windows\platform-tools\adb.exe"
DEV = "192.168.1.178:44515"

def sh(cmd):
    return subprocess.run([ADB, "-s", DEV, "shell", cmd], capture_output=True, text=True).stdout

pid = sh("pidof com.tokyoxpa3.androidproxy").strip()

targets = {"1176119": "tfd=255 (測試conn,slot1086)", "1182877": "tfd=6 (健康檢查?,slot1087)",
           "1182896": "cfd=230", "1185068": "cfd=228"}

fd_inode = {}
for line in sh(f"run-as com.tokyoxpa3.androidproxy ls -l /proc/{pid}/fd").splitlines():
    m = re.search(r"(\d+) -> socket:\[(\d+)\]", line)
    if m:
        fd_inode[int(m.group(1))] = m.group(2)

tcp = {}
for tbl in ("/proc/net/tcp", "/proc/net/tcp6"):
    for l in sh(f"cat {tbl}").splitlines()[1:]:
        p = l.split()
        if len(p) >= 10:
            tcp[p[9]] = p[3]

print(f"pid={pid} 目前 socket fd 數: {len(fd_inode)}")
for ino, label in targets.items():
    holders = [f for f, i in fd_inode.items() if i == ino]
    st = tcp.get(ino)
    print(f"{label}: inode={ino} {'仍開啟! fds=' + str(holders) + ' state=' + str(st) if holders else '已關閉 ✓'}")

# 順便統計目前 CLOSE_WAIT
cw = sum(1 for i in fd_inode.values() if tcp.get(i) == '08')
print(f"\n目前行程內 CLOSE_WAIT 總數: {cw}")
