# -*- coding: utf-8 -*-
"""H2 stress: mix of normal SOCKS5 CONNECT loops and 'connect then immediately
RST' connections to hammer the handoff/worker destroy race window."""
import socket
import struct
import threading
import time
import collections

PROXY = ("192.168.1.178", 1080)
DURATION = 35.0
NORMAL_THREADS = 6
RST_THREADS = 6

stats = collections.Counter()
lock = threading.Lock()
stop = threading.Event()


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c:
            raise ConnectionError("closed")
        buf += c
    return buf


def normal_worker():
    while not stop.is_set():
        try:
            s = socket.create_connection(PROXY, timeout=8)
            s.settimeout(10)
            s.sendall(b"\x05\x01\x00")
            assert recv_exact(s, 2) == b"\x05\x00"
            h = b"api.ipify.org"
            s.sendall(bytes([5, 1, 0, 3, len(h)]) + h + struct.pack(">H", 80))
            resp = recv_exact(s, 10)
            if resp[1] != 0:
                with lock:
                    stats["rep_0x%02x" % resp[1]] += 1
                s.close()
                continue
            s.sendall(b"GET / HTTP/1.0\r\nHost: api.ipify.org\r\n\r\n")
            data = b""
            while True:
                c = s.recv(4096)
                if not c:
                    break
                data += c
            s.close()
            with lock:
                stats["ok" if b"200" in data.split(b"\r\n", 1)[0] else "http_fail"] += 1
        except Exception as e:
            with lock:
                stats[type(e).__name__] += 1
            time.sleep(0.05)


def rst_worker():
    """SOCKS CONNECT 成功後立即 hard-close（SO_LINGER 0 = RST），
    製造 handoff 進行中 client 就斷線的競態窗口"""
    while not stop.is_set():
        try:
            s = socket.create_connection(PROXY, timeout=5)
            s.settimeout(5)
            s.sendall(b"\x05\x01\x00")
            assert recv_exact(s, 2) == b"\x05\x00"
            h = b"example.com"
            s.sendall(bytes([5, 1, 0, 3, len(h)]) + h + struct.pack(">H", 80))
            resp = recv_exact(s, 10)
            # 收到 success 回覆後立即 RST（不等回應、不用 FIN）
            s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            s.close()
            with lock:
                stats["rst_sent"] += 1
        except Exception as e:
            with lock:
                stats["rst_" + type(e).__name__] += 1
            time.sleep(0.05)


threads = [threading.Thread(target=normal_worker, daemon=True) for _ in range(NORMAL_THREADS)]
threads += [threading.Thread(target=rst_worker, daemon=True) for _ in range(RST_THREADS)]
for t in threads:
    t.start()
t0 = time.time()
while time.time() - t0 < DURATION:
    time.sleep(5)
    print("[%4.1fs] %s" % (time.time() - t0, dict(stats)))
stop.set()
for t in threads:
    t.join(timeout=15)
print("FINAL:", dict(stats))
