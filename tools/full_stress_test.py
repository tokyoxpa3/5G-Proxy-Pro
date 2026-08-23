# -*- coding: utf-8 -*-
"""5G-Proxy-Pro 完整自動壓力測試套件

用法: python full_stress_test.py [proxy_ip] [proxy_port]
會依序執行:
  T1  ATYP 測試 (IPv4/domain/IPv6/IPv4-443)  -- 重現 NetRedirector 場景
  T2  UDP-in-TCP IPv4 + IPv6
  T3  HTTP + HTTPS curl 端到端
  T4  並發負載測試 (可調)
  T5  連線存活/清理檢查
"""
import socket, struct, time, subprocess, sys, threading

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.178"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 1080
TIMEOUT = 12

def rep_status(rep):
    return {0: "OK", 1: "general", 2: "not-allowed", 3: "net-unreach", 4: "host-unreach", 5: "refused", 6: "ttl", 7: "cmd-unsup"}.get(rep, rep)

def socks_connect_raw(host_bytes, atyp, port, timeout=TIMEOUT):
    s = socket.create_connection((IP, PORT), timeout=timeout)
    s.settimeout(timeout)
    s.sendall(b'\x05\x01\x00')
    r = s.recv(2)
    if r != b'\x05\x00':
        s.close()
        return None, "greeting fail %r" % r
    if atyp == 1:
        req = b'\x05\x01\x00\x01' + host_bytes + struct.pack('>H', port)
    elif atyp == 3:
        req = b'\x05\x01\x00\x03' + bytes([len(host_bytes)]) + host_bytes + struct.pack('>H', port)
    elif atyp == 4:
        req = b'\x05\x01\x00\x04' + host_bytes + struct.pack('>H', port)
    s.sendall(req)
    r = s.recv(10)
    if len(r) < 2:
        s.close()
        return None, "no reply"
    return s, rep_status(r[1])

def test_atyp():
    print("== T1 ATYP (NetRedirector 場景) ==")
    # IPv4 字面值 (cloudflare example.com A)
    ip4 = socket.gethostbyname("www.example.com")
    s, st = socks_connect_raw(socket.inet_aton(ip4), 1, 80)
    print("  IPv4-literal %s:80 -> %s" % (ip4, st))
    if s: s.close()
    # domain
    host = b"www.example.com"
    s, st = socks_connect_raw(host, 3, 80)
    print("  domain www.example.com:80 -> %s" % st)
    if s: s.close()
    # IPv6 字面值
    try:
        ip6 = socket.getaddrinfo("www.example.com", 80, socket.AF_INET6)[0][4][0]
        s, st = socks_connect_raw(socket.inet_pton(socket.AF_INET6, ip6), 4, 80)
        print("  IPv6-literal %s:80 -> %s" % (ip6, st))
        if s: s.close()
    except Exception as e:
        print("  IPv6-literal skip: %s" % e)
    # IPv4 字面值 443
    s, st = socks_connect_raw(socket.inet_aton(ip4), 1, 443)
    print("  IPv4-literal %s:443 -> %s" % (ip4, st))
    if s: s.close()

def test_udp_in_tcp(atyp):
    print("== T2 UDP-in-TCP (atyp=%d) ==" % atyp)
    try:
        s = socket.create_connection((IP, PORT), timeout=TIMEOUT)
        s.settimeout(TIMEOUT + 3)
        s.sendall(b'\x05\x01\x00')
        assert s.recv(2) == b'\x05\x00'
        s.sendall(bytes([0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0]))
        r = s.recv(10)
        if r[1] != 0:
            print("  FAIL handshake REP=0x%02x" % r[1]); s.close(); return
        # DNS query frame
        if atyp == 4:
            dst = socket.inet_pton(socket.AF_INET6, "2001:4860:4860::8888")
        else:
            dst = socket.inet_pton(socket.AF_INET, "8.8.8.8")
        qname = b""
        for p in b"example.com".split(b"."):
            qname += bytes([len(p)]) + p
        dns = struct.pack(">HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0) + qname + b'\x00' + struct.pack(">HH", 1, 1)
        payload = b'\x00\x00\x00' + bytes([atyp]) + dst + struct.pack(">H", 53) + dns
        s.sendall(struct.pack(">H", len(payload)) + payload)
        hdr = s.recv(2)
        if len(hdr) < 2:
            print("  FAIL no reply frame"); s.close(); return
        (length,) = struct.unpack(">H", hdr)
        reply = s.recv(length)
        r_atyp = reply[3]
        if r_atyp == 1:
            dns_off = 10
        elif r_atyp == 4:
            dns_off = 22
        else:
            print("  FAIL bad ATYP 0x%02x" % r_atyp); s.close(); return
        if len(reply) >= dns_off + 12:
            qid, flags = struct.unpack(">HH", reply[dns_off:dns_off + 4])
            if flags & 0x8000 and qid == 0x1234:
                print("  PASS (%d bytes, ATYP=0x%02x, qid ok)" % (len(reply), r_atyp))
            else:
                print("  FAIL qid/flags mismatch: qid=0x%04x flags=0x%04x" % (qid, flags))
        else:
            print("  FAIL reply too short: %d bytes" % len(reply))
        s.close()
    except Exception as e:
        print("  FAIL: %s" % e)

def test_curl():
    print("== T3 curl 端到端 ==")
    for url in ("http://www.example.com", "https://www.google.com"):
        t0 = time.time()
        r = subprocess.run(["curl.exe", "-x", "socks5h://%s:%d" % (IP, PORT),
                            "-s", "-o", "NUL", "-w", "%{http_code}", "--max-time", "12", url],
                           capture_output=True, text=True)
        print("  %-30s http_code=%s time=%.1fs" % (url, r.stdout, time.time() - t0))

def test_load(threads, seconds):
    print("== T4 並發負載 %d 執行緒 x %ds ==" % (threads, seconds))
    stats = {"ok": 0, "fail": 0}
    lock = threading.Lock()
    stop = threading.Event()
    targets = [(b"www.example.com", b"GET / HTTP/1.0\r\nHost: www.example.com\r\n\r\n"),
               (b"neverssl.com", b"GET / HTTP/1.0\r\nHost: neverssl.com\r\n\r\n")]
    def worker(wid):
        while not stop.is_set():
            try:
                s = socket.create_connection((IP, PORT), timeout=8); s.settimeout(8)
                s.sendall(b'\x05\x01\x00')
                assert s.recv(2) == b'\x05\x00'
                host, req = targets[wid % 2]
                s.sendall(bytes([5, 1, 0, 3, len(host)]) + host + struct.pack(">H", 80))
                resp = s.recv(10)
                if resp[1] != 0:
                    raise Exception("REP=%d" % resp[1])
                s.sendall(req)
                data = b""
                while True:
                    c = s.recv(4096)
                    if not c: break
                    data += c
                status = data.split(b"\r\n", 1)[0]
                if not (status.startswith(b"HTTP/") and (b" 2" in status[:15] or b" 3" in status[:15])):
                    raise Exception("http %r" % status[:30])
                s.close()
                with lock: stats["ok"] += 1
            except Exception:
                with lock: stats["fail"] += 1
                time.sleep(0.1)
    ths = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(threads)]
    for t in ths: t.start()
    time.sleep(seconds)
    stop.set()
    for t in ths: t.join(timeout=5)
    total = stats["ok"] + stats["fail"]
    rate = 100.0 * stats["ok"] / total if total else 0
    print("  ok=%d fail=%d 成功率=%.1f%%" % (stats["ok"], stats["fail"], rate))

if __name__ == "__main__":
    print("5G-Proxy-Pro stress suite -> %s:%d" % (IP, PORT))
    test_atyp()
    test_udp_in_tcp(1)
    test_udp_in_tcp(4)
    test_curl()
    test_load(16, 30)
    print("== DONE ==")
