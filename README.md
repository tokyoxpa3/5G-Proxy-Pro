# 5G Proxy Pro (AndroidProxy)

**English** · [繁體中文](README.zh-TW.md) · [한국어](README.ko.md)

[<img src="assets/f-droid-badge.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.tokyoxpa3.androidproxy/)
[<img src="assets/github-badge.png" alt="Get it on GitHub" height="80">](https://github.com/tokyoxpa3/5G-Proxy-Pro/releases/latest)

An Android app that **locks your device to the 5G / cellular connection and shares it as a SOCKS5 proxy**.

It uses Android's `ConnectivityManager.requestNetwork()` to pin the cellular network (TRANSPORT_CELLULAR), and a native C SOCKS5 server (epoll multiplexing) to share **TCP/UDP traffic egressing over 5G** with other devices — phones on the same Wi-Fi, computers, and so on.

---

## Features

- 🔒 **Forced cellular lock** — binds sockets to the 5G network via `NetworkRequest` (`Network.bindSocket`), unaffected by Wi-Fi
- 🔑 **Optional username/password auth** (RFC 1929) — no auth by default (open proxy); auth is enabled **only when both** username and password are filled in. Leaving either field empty disables it, so blank values can never let arbitrary input through
- 🧦 **Full SOCKS5 proxy** — native C engine (epoll, concurrent); supports TCP (CONNECT) and UDP (ASSOCIATE), and IPv4 / IPv6 / DOMAIN addresses
- 🛰️ **Binds LAN interfaces only** — listeners bind only to local network addresses (Wi-Fi / hotspot / USB tethering), plus a local loopback for health checks. **Never exposed on the cellular interface**
- 🧵 **UDP-in-TCP** — custom extension command `0x04`: UDP relay payload rides the same TCP connection as frames, immune to UDP congestion and priority handling (see the protocol spec below)
- 🔁 **IPv6-first Happy Eyeballs** — TCP connections race per RFC 8305: IPv6 goes first, IPv4 joins 250 ms later, first success wins (previously a parallel race with an IPv4 preference)
- ❤️ **20-second heartbeat** — keeps the 5G link from dropping or falling into power save
- 🛡️ **Native engine health check** — verifies the SOCKS5 server every 10 seconds and restarts it on failure. A built-in stop flag prevents a race with "user pressed stop" from leaving zombie listeners
- 📢 **Real status reporting** — the service reports its actual state via callback (starting / running / paused / restarting / stopped / failed). The UI never guesses
- 🔔 **Notification permission** — on Android 13+ the app requests `POST_NOTIFICATIONS` before starting, so the foreground service notification displays properly
- 🔌 **Optional auto-start after reboot** — **off by default**. When enabled, the proxy is restored only if it was still running when the device shut down; pressing Stop clears that flag, so a stop is never silently undone. If the network is not ready yet at boot it retries every 15 seconds (up to 5 times) instead of giving up
- 🧪 **Built-in SOCKS5 self-test + one-tap diagnostic report** — walks the full proxy path (TCP connect → SOCKS5 handshake → auth → CONNECT → data return) and marks each step pass/fail to pinpoint the failing stage; the diagnostic report copies with one tap
- 📶 Shows the Wi-Fi LAN IP / hotspot IP / 5G public IP, with a "Refresh status" button and the last exit reason (low memory, crash, etc.)

---

## Build requirements

| Item | Version |
|---|---|
| JDK | 17+ |
| Android SDK Platform | 34 (compileSdk) |
| Android NDK | **26.3.11579264** |
| CMake | **3.22.1** |
| Gradle (wrapper) | 8.4 (downloaded automatically) |
| Android Gradle Plugin | 8.1.2 |
| Kotlin | 1.9.20 |
| Minimum Android version | Android 8.0 (API 26); requires cellular network and `requestNetwork()` support |

> ⚠️ NDK and CMake must be installed at exactly **26.3.11579264** and **3.22.1**, otherwise the build fails.

---

## Building

### Option 1: Android Studio (recommended)

1. Install [Android Studio](https://developer.android.com/studio) (bundles JDK 17)
2. In **SDK Manager** (Settings → Languages & Frameworks → Android SDK → SDK Tools) install:
   - **NDK (Side by side) → 26.3.11579264**
   - **CMake → 3.22.1**
3. Open the `AndroidProxy` folder in Android Studio and wait for Gradle sync to finish
4. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. APK location: `app\build\outputs\apk\debug\app-debug.apk`

### Option 2: Command line (no Android Studio needed)

```powershell
# Requires: JDK 17+, Android SDK, NDK 26.3.11579264, CMake 3.22.1
# Create local.properties once, pointing at your SDK:
#   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk

cd AndroidProxy

# Debug build (auto-signed)
.\gradlew.bat assembleDebug

# Release build (note: currently debug-signed, see below)
.\gradlew.bat assembleRelease

# Install to a connected device
.\gradlew.bat installDebug
```

- **Windows**: `gradlew.bat`　**macOS / Linux**: `./gradlew`
- Output locations:
  - Debug: `app/build/outputs/apk/debug/app-debug.apk`
  - Release: `app/build/outputs/apk/release/app-release.apk`

---

## Installing on a phone

1. On the phone, enable **Developer options** → **USB debugging**
2. Connect over USB and confirm `adb devices` lists a `device`
3. Install:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. After installing, the app needs the **connected-device foreground service** permission (`FOREGROUND_SERVICE_CONNECTED_DEVICE`). On first launch it prompts you to allow the battery optimization exemption — follow the prompt (especially on Xiaomi/Samsung devices).

---

## Usage

### Topology

One phone **with a 5G connection** is enough as the "server" — no second phone required. Install 5G-Proxy-Pro on that phone and it becomes a **SOCKS5 server** itself; the final egress is always that phone's own 5G SIM.

**Clients are not limited to computers**: any SOCKS5-capable device works — phones/tablets via 5G-Proxy-Client, computers via NetRedirector to target specific programs.

```
Client (5G-Proxy-Client on phone/tablet, NetRedirector on PC)
        │ SOCKS5
        ▼
  Phone (5G proxy server)
        │ 5G cellular (forced)
        ▼
      Internet
```

All three of the following ways to connect the phone (server) and clients work:

1. **Same Wi-Fi**: the phone and clients connect to the same router, on the same LAN.
2. **Phone Wi-Fi hotspot**: the phone acts as the AP and other devices connect to it — but they don't go online directly, they go through SOCKS5.
3. **USB tethering**: connect the phone to a computer over USB and use USB tethering. Downside: only one device can connect. You can chain further (make the client device a server for others), but the configuration is more involved.

> 💡 Whichever way the phone and clients are connected, **all traffic leaving through the SOCKS5 proxy uses the phone's own 5G connection** — 5G-Proxy-Pro enforces this. If the phone itself is online over Wi-Fi, its local traffic prefers Wi-Fi, but proxied traffic always goes over 5G.

### Steps

1. Open the app (5G Proxy Pro)
2. Enter the proxy port (default `1080`)
3. (Optional) enter a username and password — **both empty = no auth (open proxy)**; **both filled** enables auth (RFC 1929), and leaving either empty means no auth. **Changing credentials while the proxy is running applies immediately** (new connections only; existing connections are unaffected) with no stop/restart needed
4. On Android 13+ the first launch requests **notification permission**; allow it so the foreground service notification displays (denying still works, and the app will tell you)
5. Tap "🚀 Start 5G Proxy" and wait for the status to show "✅ 5G Proxy Running"
6. Configure a SOCKS5 proxy on the other devices on the same Wi-Fi (e.g. the phone's advanced Wi-Fi settings, or the system proxy settings on a computer):

```
Server: the phone's Wi-Fi IP (shown in the app)
Port: 1080
Protocol: SOCKS5
Username: <only if you set one>
Password: <only if you set one>
```

> ⚠️ Without credentials this is an open proxy: anyone who can reach the phone's address can use it. Only run it on a trusted LAN, or protect it with credentials!
>
> ℹ️ The proxy listeners **bind LAN interfaces only** (Wi-Fi / hotspot / USB tethering, plus local loopback for health checks) and cannot be reached over the cellular interface. If the device has no usable LAN interface, startup fails with an error message.

To stop: go back to the app and tap "🛑 Stop Proxy Service". All listeners close immediately, the port is fully released, and neither the system nor the health check will quietly restart it (since v1.4.0).

---

## Project layout

```
AndroidProxy/
├── app/src/main/
│   ├── java/com/tokyoxpa3/androidproxy/
│   │   ├── DebugActivity.kt           # Main screen + self-test + hotspot IP display
│   │   ├── Socks5ProxyService.kt     # Foreground service: lock 5G, start the C engine
│   │   ├── NativeEngine.kt           # JNI bridge (socketProvider callbacks)
│   │   ├── SelfTest.kt               # Built-in self-test (walks the full proxy path)
│   │   ├── Socks5ClientProtocol.kt   # SOCKS5 client protocol for self-test (pure functions)
│   │   ├── DnsCache.kt               # DNS cache + single-flight (pure coordination logic)
│   │   ├── HappyEyeballs.kt          # RFC 8305 connection racing (pure decision logic)
│   │   ├── IpLiteral.kt              # IP literal detection (never triggers DNS)
│   │   ├── PowerPermissionHelper.kt  # Per-vendor battery optimization guidance
│   │   └── network/
│   │       ├── CellularNetworkManager.kt  # requestNetwork() acquire/release
│   │       ├── PublicIPChecker.kt         # 5G public IP lookup
│   │       └── HotspotManager.kt          # Hotspot IP detection
│   └── cpp/
│       ├── jni_bridge.c         # JNI registration, Java↔C bridge
│       ├── simple-socks5.c      # epoll SOCKS5 engine (TCP/UDP)
│       ├── socks5_protocol.h    # Pure protocol parse/build declarations (TCP handshake + UDP datagram, host-testable)
│       ├── socks5_protocol.c    # Pure protocol parse/build implementation (zero socket dependencies)
│       └── CMakeLists.txt
```

### Traffic path

```
[Client] --SOCKS5--> [App: C engine] --JNI--> [Java: Network.bindSocket(5G)] --> [Carrier network]
```

When the C engine accepts a client connection it calls Java's `createSocketBoundToNetwork()` over JNI and uses a socket dedicated to the cellular network for the actual data transfer, guaranteeing that all traffic goes over 5G/cellular rather than Wi-Fi.

---

## UDP-in-TCP protocol spec (custom extension command 0x04)

Standard SOCKS5 UDP relay uses a separate UDP channel (UDP ASSOCIATE); this server additionally supports a custom command `0x04` ("UDP ASSOCIATE over TCP") where UDP payloads ride **the same TCP control connection** as frames. This avoids UDP being dropped on congested networks and sidesteps NAT/firewall problems with UDP.

### Handshake

```
Client → Server: {0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0}   (SOCKS5 request, cmd=0x04)
Server → Client: {0x05, 0x00, 0x00, 0x01, BND.ADDR, BND.PORT} (standard success reply, BND all zero)
```

After a successful reply, both directions of the same TCP connection carry UDP datagrams as frames.

If the server replies `REP≠0` (0x04 unsupported, or **temporarily refusing** — e.g. connection or UDP slot exhaustion, or failure to create the 5G UDP socket), it sends the RFC 1928 failure reply and then **closes that TCP connection immediately**. The client **must therefore open a new connection** before falling back to standard `0x03` (UDP ASSOCIATE); reusing the original connection only yields EOF, and that failure gets misread as a network drop, triggering unnecessary automatic reconnects on the client.

REP semantics for failure replies:

| REP | Meaning | Server-side situation |
|---|---|---|
| `0x01` | general SOCKS server failure | `MAX_CONCURRENT_CONNS` reached, or UDP slots exhausted (**server resource exhaustion**, not a target problem) |
| `0x04` | host unreachable | Target unreachable, including failure to create the 5G egress UDP socket |

Clients should classify both as **server-side refusal/busy** (protocol layer), not as a network-layer disconnect.

### Frame format (identical in both directions)

```
+--------+--------+-----...------+
| len_hi | len_lo | SOCKS5 UDP datagram |
+--------+--------+---------------------+
```

- `len`: 16-bit **network order**, the total byte count of the following datagram (excluding the length field itself)
- The datagram uses the standard SOCKS5 UDP header:

```
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
```

- `RSV=0`, `FRAG=0`
- `ATYP=0x01` (IPv4, 10-byte header), `ATYP=0x04` (IPv6, 22-byte header), or `ATYP=0x03` (domain, header `4 + 1 + len + 2`, variable length)
  - `ATYP=0x03` is explicitly allowed by RFC 1928 §7 — the `ATYP` in the UDP request header shares the same definition (0x01/0x03/0x04) as §5 TCP CONNECT. With **Remote DNS** enabled in 5G-Proxy-Client the client only holds a fake IP, and the real name must be resolved by the server **using its own egress**, so the frames it sends are domain-typed; accepting only 0x01/0x04 would silently drop them, killing all UDP including QUIC
  - The server resolves via the **cellular network** (`Socks5ProxyService.resolveWithCache`: IP-literal fast path + shared cache + `DnsResolver` timeout + concurrency backpressure) and **never uses the system `getaddrinfo`** — that uses the default network, which on a Pro phone may route back through the VPN. Results are cached with a 60-second TTL (`resolve_domain_sockaddr` in `simple-socks5.c`); on resolution failure the frame is dropped and logged
- On receiving a frame the server resolves the target address and `sendto`s it over the 5G socket; replies from 5G are wrapped as frames using the source address and sent back to the client (v4-mapped is always emitted as ATYP=0x01)

### Implementation

- `app/src/main/cpp/simple-socks5.c` → `handle_udp_tcp_session()` (`handle_handshake` dispatches cmd==0x04)

### Client pairing

- 5G-Proxy-Client uses this protocol when "UDP relay over TCP (UDP-in-TCP)" is checked; against a plain SOCKS5 server it falls back to the standard protocol automatically, so both interoperate.
- With **Remote DNS** enabled on the client, UDP frames carry `ATYP=0x03` (domain) and require server-side resolution — see point 3 under Frame format above.
- When falling back to standard `0x03` the client **opens a new connection** (this server closes the original after replying `REP≠0`) and classifies that failure as protocol-layer, **without** triggering its auto-reconnect watchdog. So "can't connect once UDP-in-TCP is checked" was a client-side bug in older versions that caused a restart loop; the server's only responsibility is to **honestly reply `REP≠0` and close**.

---

## Notes

- **Release builds are unsigned by default**: to publish an APK yourself, create `keystore.properties` in the project root (format below) and prepare a real keystore. Without that file the release build emits an unsigned APK, for third parties such as F-Droid to sign with their own key.
- The app only works while the cellular network is locked; without cellular signal (no SIM) the service cannot start.
- **It can coexist with an on-device VPN, provided this app is excluded from it**: at startup it actually attempts to bind a socket to the cellular egress (`Network.bindSocket`) and **only starts if the bind succeeds**, refusing with a reason otherwise. "There is a VPN on the system" is no longer a reason to refuse startup by itself — when per-app VPN (split tunneling) does not include this app, the cellular egress is fully usable.
  - If this app is inside the VPN tunnel, the system rejects `bindSocket` with `EPERM`. In that case **exclude this app** (package `com.tokyoxpa3.androidproxy`) in the VPN's per-app/exclusion list, rather than turning the VPN off — turning it off means giving up the entire point.
  - Same-device example: while this app serves a 5G proxy over Wi-Fi, also run 5G-Proxy-Client on the same phone (server address = the "Wi-Fi proxy" IP shown by this app) and exclude this app from the tunnel, and the phone's own traffic goes over 5G too. See `TUTORIAL.md` in 5G-Proxy-Client.
  - When blocked by a VPN (bind rejected while cellular is still system-validated as available) the health check reports `BLOCKED_BY_VPN` and **deliberately does not auto-rebuild** — restarting cannot fix a configuration problem and only causes a restart loop; genuine disconnects still follow the existing "auto-rebuild after 3 consecutive failures".
- The project contains `local.properties` (SDK path) and generated directories such as `.gradle/`, `build/`, `.cxx/`; these are covered by `.gitignore` and never uploaded.

## Network usage

The proxy service itself **only listens and forwards locally** and never initiates outbound connections on its own; outbound connections occur in the following cases:

| Feature | Destination | When |
|---|---|---|
| Native engine health check | `connectivitycheck.gstatic.com` / `captive.apple.com` / `connect.rom.miui.com` (fallback in order, over 5G) | Every 10 seconds, to verify the server is alive |
| Public IP display | `api.ipify.org` | When you tap "Refresh status" |
| Self-test | example.com (through the 5G proxy) | Only when you tap "Self Test" |

All proxied traffic (what clients relay over SOCKS5) egresses through the locked cellular interface.

### Release signing (when publishing yourself)

Create `keystore.properties` in the project root (gitignored, never uploaded):

```properties
storeFile=C:/path/to/your-release.keystore
storePassword=<your keystore password>
keyAlias=<your key alias>
keyPassword=<your key password>
```

After that, `.\gradlew.bat assembleRelease` signs with the real key.

---

## License

This project is released under the **MIT License**; see [LICENSE](LICENSE).
