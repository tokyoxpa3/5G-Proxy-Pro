# -*- coding: utf-8 -*-
"""長時間連線品質監控：每 30 秒測一次 IPv4/IPv6/domain，結果寫入 CSV。

用法: python monitor.py [proxy_ip] [proxy_port] [間隔秒數]
停止: Ctrl+C（CSV 會保留）
"""
import socket, struct, time, csv, sys, os, datetime

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.178"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 1080
INTERVAL = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0

CSV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "monitor_log_%s.csv" % datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))

def test_once(host_bytes, atyp, port):
    t0 = time.time()
    s = socket.create_connection((IP, PORT), timeout=5)
    s.settimeout(8)
    try:
        s.sendall(b'\x05\x01\x00')
        r = s.recv(2)
        if r != b'\x05\x00':
            return "greet-fail", round(time.time() - t0, 1)
        if atyp == 1:
            req = b'\x05\x01\x00\x01' + host_bytes + struct.pack('>H', port)
        elif atyp == 3:
            req = b'\x05\x01\x00\x03' + bytes([len(host_bytes)]) + host_bytes + struct.pack('>H', port)
        else:
            req = b'\x05\x01\x00\x04' + host_bytes + struct.pack('>H', port)
        s.sendall(req)
        r = s.recv(10)
        return ("REP=%d" % r[1]) if len(r) >= 2 else "no-reply", round(time.time() - t0, 1)
    finally:
        s.close()

def resolve(name, af):
    gi = socket.getaddrinfo(name, 80, af)[0][4]
    if af == socket.AF_INET6:
        return socket.inet_pton(socket.AF_INET6, gi[0])
    return socket.inet_aton(gi[0])

print("monitor -> proxy %s:%d every %.0fs, CSV: %s" % (IP, PORT, INTERVAL, CSV_PATH))
f = open(CSV_PATH, "w", newline="", encoding="utf-8")
w = csv.writer(f)
w.writerow(["time", "ipv4", "ipv4_sec", "ipv6", "ipv6_sec", "domain", "domain_sec"])

try:
    while True:
        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        row = [ts]
        try:
            ip4 = resolve("www.example.com", socket.AF_INET)
            rep, sec = test_once(ip4, 1, 80)
        except Exception as e:
            rep, sec = "ERR:" + str(e)[:30], round(time.time() - t0, 1) if 't0' in dir() else 0
        row += [rep, sec]
        print("[%s] IPv4: %-12s (%ss)" % (ts, rep, sec), end="")
        try:
            ip6 = resolve("www.example.com", socket.AF_INET6)
            rep6, sec6 = test_once(ip6, 4, 80)
        except Exception as e:
            rep6, sec6 = "ERR:" + str(e)[:30], 0
        row += [rep6, sec6]
        print(" | IPv6: %-12s (%ss)" % (rep6, sec6), end="")
        try:
            repd, secd = test_once(b"www.example.com", 3, 80)
        except Exception as e:
            repd, secd = "ERR:" + str(e)[:30], 0
        row += [repd, secd]
        w.writerow(row)
        f.flush()
        print(" | domain: %s (%ss)" % (repd, secd))
        time.sleep(INTERVAL)
except KeyboardInterrupt:
    print("stopped, CSV saved:", CSV_PATH)
finally:
    f.close()
