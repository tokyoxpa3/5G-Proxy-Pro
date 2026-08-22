# -*- coding: utf-8 -*-
"""UDP-in-TCP (SOCKS5 cmd 0x04) e2e test for 5G-Proxy-Pro.

Usage: python udptest.py <proxy_ip> <proxy_port> [atyp]
  atyp: 4 (default) = send to IPv4 resolver, 6 = send to IPv6 resolver (exercises the fixed heap path)
"""
import socket
import struct
import sys
import time

PROXY_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.178"
PROXY_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 1080
ATYP_MODE = sys.argv[3] if len(sys.argv) > 3 else "4"


def build_dns_query(qname: str, qid: int) -> bytes:
    parts = qname.split(".")
    qname_bytes = b"".join(bytes([len(p)]) + p.encode() for p in parts) + b"\x00"
    header = struct.pack(">HHHHHH", qid, 0x0100, 1, 0, 0, 0)  # RD=1, qdcount=1
    return header + qname_bytes + struct.pack(">HH", 1, 1)  # A IN


def socks5_udp_datagram(atyp: int, dst: bytes, port: int, payload: bytes) -> bytes:
    return b"\x00\x00\x00" + bytes([atyp]) + dst + struct.pack(">H", port) + payload


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed, got %d/%d bytes" % (len(buf), n))
        buf += chunk
    return buf


def recv_frame(sock: socket.socket) -> bytes:
    hdr = recv_exact(sock, 2)
    (length,) = struct.unpack(">H", hdr)
    return recv_exact(sock, length)


def main() -> int:
    failures = []

    s = socket.create_connection((PROXY_IP, PROXY_PORT), timeout=10)
    s.settimeout(15)

    # 1. greeting: 1 method, no-auth
    s.sendall(b"\x05\x01\x00")
    resp = recv_exact(s, 2)
    assert resp == b"\x05\x00", "unexpected method reply %r" % resp

    # 2. request with cmd=0x04 (UDP-in-TCP extension)
    s.sendall(bytes([0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0]))
    resp = recv_exact(s, 10)
    if resp[1] != 0x00:
        print("FAIL: UDP-in-TCP rejected, REP=0x%02x" % resp[1])
        return 1
    print("PASS: 0x04 handshake accepted")

    # 3. send a DNS query as a framed SOCKS5 UDP datagram
    if ATYP_MODE == "6":
        atyp = 0x04
        dst = socket.inet_pton(socket.AF_INET6, "2001:4860:4860::8888")
        label = "IPv6 resolver"
    else:
        atyp = 0x01
        dst = socket.inet_pton(socket.AF_INET, "8.8.8.8")
        label = "IPv4 resolver"
    payload = socks5_udp_datagram(atyp, dst, 53, build_dns_query("example.com", 0x1234))
    frame = struct.pack(">H", len(payload)) + payload
    s.sendall(frame)
    print("PASS: sent DNS query frame to %s (%d bytes)" % (label, len(payload)))

    # 4. read reply frame and sanity-check the SOCKS5 UDP header from the server
    try:
        reply = recv_frame(s)
    except (socket.timeout, ConnectionError) as e:
        print("FAIL: no reply frame (%s)" % e)
        return 1
    if len(reply) < 4:
        print("FAIL: reply too short: %r" % reply[:32])
        return 1
    rsv, frag, r_atyp = reply[0:2], reply[2], reply[3]
    if rsv != b"\x00\x00" or frag != 0:
        failures.append("RSV/FRAG not zero: %r/%r" % (rsv, frag))
    if r_atyp == 0x01:
        src = socket.inet_ntop(socket.AF_INET, reply[4:8])
        sport = struct.unpack(">H", reply[8:10])[0]
        dns = reply[10:]
    elif r_atyp == 0x04:
        src = socket.inet_ntop(socket.AF_INET6, reply[4:20])
        sport = struct.unpack(">H", reply[20:22])[0]
        dns = reply[22:]
    else:
        print("FAIL: bad reply ATYP 0x%02x: %r" % (r_atyp, reply[:32]))
        return 1
    print("PASS: reply frame ok, %d bytes, src=%s:%d ATYP=0x%02x" % (len(reply), src, sport, r_atyp))
    if r_atyp == 0x04:
        print("INFO: reply came from a native IPv6 source -> exercised the fixed heap path (old code wrote datagram[-2])")

    # 5. validate the DNS payload looks like a response (QR=1 and matching qid)
    if len(dns) >= 12:
        qid, flags = struct.unpack(">HH", dns[:4])
        if qid == 0x1234 and (flags & 0x8000):
            print("PASS: DNS response valid (qid=0x%04x, rcode=%d, answers=%d)" % (
                qid, flags & 0xF, struct.unpack(">H", dns[6:8])[0]))
        else:
            failures.append("DNS payload mismatch: qid=0x%04x flags=0x%04x" % (qid, flags))
    else:
        failures.append("DNS payload too short: %d bytes" % len(dns))

    # 6. two more exchanges to make sure the session keeps working (and buffer stays intact)
    for i in range(2):
        time.sleep(0.3)
        payload = socks5_udp_datagram(atyp, dst, 53, build_dns_query("example.org", 0x2000 + i))
        s.sendall(struct.pack(">H", len(payload)) + payload)
        reply = recv_frame(s)
        if len(reply) < 11:
            failures.append("exchange %d too short" % i)
        else:
            print("PASS: exchange %d ok (%d bytes)" % (i + 1, len(reply)))

    s.close()
    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
