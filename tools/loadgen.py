# -*- coding: utf-8 -*-
"""Load generator: concurrent SOCKS5 CONNECT loops with domain names (exercises
handshake pool + DNS + TCP relay). Usage: python loadgen.py <ip> <port> <threads> <seconds>
"""
import socket
import struct
import sys
import threading
import time

PROXY_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.178"
PROXY_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 1080
THREADS = int(sys.argv[3]) if len(sys.argv) > 3 else 8
DURATION = float(sys.argv[4]) if len(sys.argv) > 4 else 20.0

stats = {"ok": 0, "fail": 0}
lock = threading.Lock()
stop_flag = threading.Event()

# 輪替目標：避免單一目標的 rate-limit（429）污染測試結果
# （google 會對非瀏覽器 UA 回 403，故不採用）
TARGETS = [
    (b"www.example.com", b"GET / HTTP/1.0\r\nHost: www.example.com\r\n\r\n"),
    (b"neverssl.com", b"GET / HTTP/1.0\r\nHost: neverssl.com\r\n\r\n"),
]


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def worker(wid):
    while not stop_flag.is_set():
        try:
            s = socket.create_connection((PROXY_IP, PROXY_PORT), timeout=8)
            s.settimeout(8)
            s.sendall(b"\x05\x01\x00")
            assert recv_exact(s, 2) == b"\x05\x00"
            # CONNECT by DOMAIN: proxy side does DNS + IPv6/IPv4 fallback
            host, req = TARGETS[wid % len(TARGETS)]
            sreq = bytes([5, 1, 0, 3, len(host)]) + host + struct.pack(">H", 80)
            s.sendall(sreq)
            resp = recv_exact(s, 10)
            if resp[1] != 0:
                raise ConnectionError("REP=%d" % resp[1])
            s.sendall(req)
            data = b""
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
            status = data.split(b"\r\n", 1)[0]
            # 2xx/3xx 都算成功（429/5xx 視為目標端問題）
            if not (status.startswith(b"HTTP/") and (b" 2" in status[:15] or b" 3" in status[:15])):
                raise ConnectionError("http fail: %r" % status[:40])
            s.close()
            with lock:
                stats["ok"] += 1
        except Exception as e:
            with lock:
                stats["fail"] += 1
                stats.setdefault("last_err", str(e))
            time.sleep(0.1)


def main():
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(THREADS)]
    for t in threads:
        t.start()
    t0 = time.time()
    while time.time() - t0 < DURATION:
        time.sleep(2)
        print("[%4.1fs] ok=%d fail=%d" % (time.time() - t0, stats["ok"], stats["fail"]))
    stop_flag.set()
    for t in threads:
        t.join(timeout=10)
    print("FINAL ok=%d fail=%d last_err=%s" % (stats["ok"], stats["fail"], stats.get("last_err", "-")))


if __name__ == "__main__":
    main()
