# 5G Proxy Pro (AndroidProxy)

[English](README.md) · [繁體中文](README.zh-TW.md) · **한국어**

[<img src="assets/f-droid-badge.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.tokyoxpa3.androidproxy/)
[<img src="assets/github-badge.png" alt="Get it on GitHub" height="80">](https://github.com/tokyoxpa3/5G-Proxy-Pro/releases/latest)

Android 폰의 **5G / 셀룰러 회선을 강제로 고정하고 SOCKS5 서버로 공유**하는 앱입니다.

Android의 `ConnectivityManager.requestNetwork()`로 셀룰러 네트워크(TRANSPORT_CELLULAR)를 고정하고, 네이티브 C로 구현한 SOCKS5 서버(epoll 멀티플렉싱)를 통해 **5G 회선을 경유하는 TCP/UDP 트래픽**을 다른 기기(같은 Wi-Fi의 폰, PC 등)에 프록시로 공유합니다.

> This app locks your device to the cellular (5G/4G) connection and exposes a SOCKS5 proxy over Wi-Fi, so other devices can route their traffic through the phone's cellular network. The proxy engine is a native C (epoll-based) implementation stripped via JNI.

---

## 주요 기능

- 🔒 **셀룰러 네트워크 강제 고정** — `NetworkRequest`로 소켓을 5G 네트워크에 바인딩(`Network.bindSocket`)하므로 Wi-Fi의 영향을 받지 않습니다
- 🔑 **선택적 ID/비밀번호 인증**(RFC 1929) — 기본값은 인증 없음(오픈 프록시). **아이디와 비밀번호를 모두 입력해야** 인증이 활성화되며, 한쪽이라도 비우면 비활성화됩니다(빈 값으로 아무 입력이나 통과하는 것을 방지)
- 🧦 **완전한 SOCKS5 프록시** — 네이티브 C 엔진(epoll, 동시 처리). TCP(CONNECT)와 UDP(ASSOCIATE)를 지원하고 IPv4 / IPv6 / 도메인(DOMAIN) 주소를 모두 처리합니다
- 🛰️ **LAN 인터페이스에만 바인딩** — 리스너는 Wi-Fi / 핫스팟 / USB 테더링 등 로컬 네트워크 주소에만 바인딩되며(헬스 체크용 로컬 loopback 별도), **셀룰러 인터페이스에는 절대 노출되지 않습니다**
- 🧵 **UDP-in-TCP** — 사용자 정의 확장 명령 `0x04`: UDP relay 데이터를 프레임으로 같은 TCP 연결에 실어 보내므로 UDP 혼잡/우선순위의 영향을 받지 않습니다(아래 프로토콜 규격 참고)
- 🔁 **IPv6 우선 Happy Eyeballs** — TCP 연결을 RFC 8305 방식으로 경쟁시킵니다. IPv6가 먼저 나가고 IPv4가 250ms 늦게 합류해 먼저 성공한 쪽이 이깁니다(이전에는 IPv4 우선 병렬 경쟁)
- ❤️ **20초 주기 하트비트** — 5G 연결이 끊기거나 절전 모드에 들어가는 것을 방지합니다
- 🛡️ **네이티브 엔진 헬스 체크** — 10초마다 SOCKS5 서버 생존을 확인하고 이상 시 자동 재시작합니다. 중지 플래그가 내장되어 있어 "사용자가 중지 버튼을 누르는" 동작과 경쟁해 좀비 리스너가 생기지 않습니다
- 📢 **실제 상태 보고** — 서비스가 콜백으로 실제 상태(시작 중/실행 중/일시정지/재구성 중/중지됨/실패)를 보고합니다. UI가 추측하지 않습니다
- 🔔 **알림 권한** — Android 13+에서는 시작 전에 `POST_NOTIFICATIONS`를 요청하며, 포그라운드 서비스 알림이 정상 표시됩니다
- 🔌 **선택적 부팅 시 자동 시작** — **기본값은 꺼짐**. 켜 두면 "종료 전에 프록시가 실행 중이었을 때"만 복원되며, 중지 버튼을 누르면 플래그가 지워지므로 중지가 조용히 뒤집히지 않습니다. 부팅 직후 네트워크가 아직 준비되지 않았으면 15초마다 최대 5회 재시도합니다
- 🧪 **내장 SOCKS5 자가 진단 + 원클릭 진단 보고서** — 전체 프록시 경로(TCP 연결 → SOCKS5 핸드셰이크 → 인증 → CONNECT → 데이터 회귀)를 단계별로 pass/fail로 표시해 고장 지점을 정확히 찾아내고, 진단 보고서를 원클릭으로 복사할 수 있습니다
- 📶 Wi-Fi 내부 IP / 핫스팟 IP / 5G 공인 IP 표시, "상태 새로고침" 버튼, 마지막 종료 사유(저메모리, 크래시 등)

---

## 시스템 요구 사항 (빌드 환경)

| 항목 | 버전 |
|---|---|
| JDK | 17+ |
| Android SDK Platform | 34 (compileSdk) |
| Android NDK | **26.3.11579264** |
| CMake | **3.22.1** |
| Gradle (wrapper) | 8.4 (자동 다운로드) |
| Android Gradle Plugin | 8.1.2 |
| Kotlin | 1.9.20 |
| 최소 Android 버전 | Android 8.0 (API 26). 셀룰러 네트워크와 `requestNetwork()` 지원 필요 |

> ⚠️ NDK와 CMake는 반드시 **26.3.11579264**와 **3.22.1**을 정확히 설치해야 하며, 그렇지 않으면 빌드가 실패합니다.

---

## 빌드 방법

### 방법 1: Android Studio (권장)

1. [Android Studio](https://developer.android.com/studio) 설치 (JDK 17 포함)
2. **SDK Manager**(Settings → Languages & Frameworks → Android SDK → SDK Tools)에서 설치:
   - **NDK (Side by side) → 26.3.11579264**
   - **CMake → 3.22.1**
3. Android Studio로 `AndroidProxy` 폴더를 열고 Gradle 동기화가 끝날 때까지 기다립니다
4. 메뉴 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. APK 위치: `app\build\outputs\apk\debug\app-debug.apk`

### 방법 2: 명령줄 (Android Studio 불필요)

```powershell
# 필요: JDK 17+, Android SDK, NDK 26.3.11579264, CMake 3.22.1
# 최초 1회 local.properties 생성 (SDK 경로 지정):
#   sdk.dir=C:\\Users\\사용자이름\\AppData\\Local\\Android\\Sdk

cd AndroidProxy

# Debug 빌드 (자동 서명 포함)
.\gradlew.bat assembleDebug

# Release 빌드 (참고: 현재 Debug 서명 사용, 아래 설명 참조)
.\gradlew.bat assembleRelease

# 연결된 폰에 설치
.\gradlew.bat installDebug
```

- **Windows**: `gradlew.bat`　**macOS / Linux**: `./gradlew`
- 산출물 위치:
  - Debug: `app/build/outputs/apk/debug/app-debug.apk`
  - Release: `app/build/outputs/apk/release/app-release.apk`

---

## 폰에 설치

1. 폰에서 "개발자 옵션" → "USB 디버깅" 켜기
2. USB로 연결하고 `adb devices`로 `device`가 표시되는지 확인
3. 설치:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. 설치 후 **시스템 연결 권한**(`FOREGROUND_SERVICE_CONNECTED_DEVICE`)이 필요합니다. 앱을 처음 실행하면 배터리 최적화 예외 허용을 안내하므로 안내대로 진행하는 것을 권장합니다(특히 Xiaomi/Samsung 사용자).

---

## 사용 방법

### 토폴로지 개요

**5G 회선을 쓰는 폰 한 대**를 "서버"로 쓰면 충분하고 두 번째 폰은 필요 없습니다. 이 폰에 5G-Proxy-Pro를 설치하면 그 자체가 **SOCKS5 서버**가 되고, 최종 출구는 항상 이 폰의 5G 회선입니다.

**클라이언트는 PC로 한정되지 않습니다**: SOCKS5를 지원하는 기기라면 무엇이든 됩니다. 폰/태블릿은 5G-Proxy-Client로 연결하고, PC는 NetRedirector로 대상 프로그램을 지정합니다.

```
클라이언트 (폰 / 태블릿의 5G-Proxy-Client, PC의 NetRedirector)
        │ SOCKS5
        ▼
  폰 (5G 프록시 서버)
        │ 5G 셀룰러 (강제)
        ▼
      인터넷
```

폰(서버)과 클라이언트를 연결하는 방법은 다음 세 가지 모두 가능합니다:

1. **같은 Wi-Fi에 연결**: 폰과 클라이언트를 같은 공유기에 연결해 같은 대역에 둡니다.
2. **폰에서 Wi-Fi 핫스팟 켜기**: 폰이 직접 AP가 되어 다른 기기를 연결하되, 직접 인터넷에 나가지 않고 SOCKS5를 통해 나갑니다.
3. **USB 테더링**: USB 케이블로 폰과 PC를 연결해 USB 테더링을 사용합니다. 단점은 한 대만 연결할 수 있다는 점입니다. 클라이언트 기기가 다시 서버가 되어 다른 기기를 계속 연결하는 구성도 가능하지만 설정이 번거롭습니다.

> 💡 폰과 클라이언트를 어떤 방식으로 연결하든 **SOCKS5 프록시로 나가는 트래픽은 전부 폰 자신의 5G 회선을 탑니다** — 5G-Proxy-Pro가 강제합니다. 폰 자체가 Wi-Fi로 인터넷을 쓰고 있어도 폰의 로컬 트래픽은 Wi-Fi를 우선 사용하지만, 프록시로 나가는 트래픽은 항상 5G를 탑니다.

### 사용 절차

1. 앱(5G Proxy Pro) 실행
2. 프록시 포트 입력 (기본값 `1080`)
3. (선택) "사용자"와 "비밀번호" 입력 — **둘 다 비우면 인증 없음(오픈 프록시)**, **둘 다 입력해야** 인증(RFC 1929)이 활성화되며 한쪽만 비우면 인증 없음입니다. **프록시 실행 중에 계정을 바꾸면 즉시 적용**되며(새 연결부터 적용, 기존 연결은 영향 없음) 중지 후 재시작할 필요가 없습니다
4. Android 13+에서는 첫 실행 시 **알림 권한**을 요청합니다. 허용해야 포그라운드 서비스 알림이 정상 표시됩니다(거부해도 동작하지만 앱이 안내합니다)
5. "🚀 Start 5G Proxy"를 누르고 상태가 "✅ 5G Proxy Running"으로 바뀔 때까지 기다립니다
6. 같은 Wi-Fi에 있는 다른 기기에서 SOCKS5 프록시를 설정합니다(예: 폰의 Wi-Fi 고급 설정, PC의 시스템 프록시 설정):

```
서버: 폰의 Wi-Fi IP (앱에 표시됨)
포트: 1080
프로토콜: SOCKS5
사용자 이름: <설정한 경우에만 입력>
비밀번호: <설정한 경우에만 입력>
```

> ⚠️ 계정을 설정하지 않으면 오픈 프록시가 되어 폰 주소에 접속할 수 있는 누구나 사용할 수 있습니다. 반드시 신뢰하는 로컬 네트워크에서만 사용하거나 계정으로 보호하세요!
>
> ℹ️ 프록시 리스너는 **LAN 인터페이스에만 바인딩**되며(Wi-Fi / 핫스팟 / USB 테더링, 헬스 체크용 로컬 loopback 별도), 셀룰러 인터페이스로는 접근할 수 없습니다. 사용 가능한 LAN 인터페이스가 없으면 시작이 실패하고 오류 메시지가 표시됩니다.

중지 방법: 앱으로 돌아가 "🛑 Stop Proxy Service"를 누릅니다. 중지하면 모든 리스너가 즉시 닫히고 포트가 완전히 해제되며, 시스템이나 헬스 체크가 몰래 다시 띄우지 않습니다(v1.4.0부터).

---

## 프로젝트 구조

```
AndroidProxy/
├── app/src/main/
│   ├── java/com/tokyoxpa3/androidproxy/
│   │   ├── DebugActivity.kt           # 메인 화면 + 자가 진단 + 핫스팟 IP 표시
│   │   ├── Socks5ProxyService.kt     # 포그라운드 서비스: 5G 고정, C 엔진 시작
│   │   ├── NativeEngine.kt           # JNI 브리지 (socketProvider 콜백)
│   │   ├── SelfTest.kt               # 내장 자가 진단 (전체 프록시 경로 경유)
│   │   ├── Socks5ClientProtocol.kt   # 자가 진단용 SOCKS5 클라이언트 프로토콜 (순수 함수)
│   │   ├── DnsCache.kt               # DNS 캐시 + single-flight (순수 조정 로직)
│   │   ├── HappyEyeballs.kt          # RFC 8305 연결 경쟁 (순수 결정 로직)
│   │   ├── IpLiteral.kt              # IP 리터럴 판별 (DNS 미발생)
│   │   ├── PowerPermissionHelper.kt  # 제조사별 배터리 최적화 예외 안내
│   │   └── network/
│   │       ├── CellularNetworkManager.kt  # requestNetwork() 고정 및 해제
│   │       ├── PublicIPChecker.kt         # 5G 공인 IP 조회
│   │       └── HotspotManager.kt          # 핫스팟 IP 감지
│   └── cpp/
│       ├── jni_bridge.c         # JNI 등록, Java↔C 호출 브리지
│       ├── simple-socks5.c      # epoll SOCKS5 엔진 (TCP/UDP)
│       ├── socks5_protocol.h    # 순수 프로토콜 파싱/캡슐화 선언 (TCP 핸드셰이크 + UDP 데이터그램, host 테스트 가능)
│       ├── socks5_protocol.c    # 순수 프로토콜 파싱/캡슐화 구현 (socket 의존성 없음)
│       └── CMakeLists.txt
```

### 트래픽 경로

```
[클라이언트] --SOCKS5--> [App: C 엔진] --JNI--> [Java: Network.bindSocket(5G)] --> [통신사 네트워크]
```

C 엔진이 클라이언트 연결을 받으면 JNI로 Java의 `createSocketBoundToNetwork()`를 호출해 셀룰러 네트워크 전용 소켓으로 실제 데이터를 전송합니다. 따라서 모든 트래픽이 Wi-Fi가 아닌 5G/셀룰러로 나갑니다.

---

## UDP-in-TCP 프로토콜 규격 (사용자 정의 확장 명령 0x04)

표준 SOCKS5의 UDP relay는 별도의 UDP 채널(UDP ASSOCIATE)을 사용합니다. 이 서버는 추가로 사용자 정의 명령 `0x04`("UDP ASSOCIATE over TCP")를 지원하며, UDP 데이터를 프레임 형태로 **같은 TCP 제어 연결**에 실어 보냅니다. 이렇게 하면 혼잡한 네트워크에서 UDP가 드롭되는 문제와 NAT/방화벽의 UDP 제약을 피할 수 있습니다.

### 핸드셰이크

```
Client → Server: {0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0}   (SOCKS5 request, cmd=0x04)
Server → Client: {0x05, 0x00, 0x00, 0x01, BND.ADDR, BND.PORT} (표준 성공 응답, BND 전부 0)
```

성공 응답 후 같은 TCP 연결의 양방향으로 프레임에 UDP 데이터그램을 실어 전송합니다.

서버가 `REP≠0`을 응답하면(0x04 미지원, 또는 **일시 거부** — 예: 연결 한도/UDP 슬롯 고갈, 5G UDP 소켓 생성 실패) RFC 1928에 따라 실패 응답을 보낸 뒤 **해당 TCP 연결을 즉시 닫습니다**. 따라서 클라이언트는 **반드시 새 연결을 열어** 표준 `0x03`(UDP ASSOCIATE)으로 폴백해야 합니다. 기존 연결을 계속 쓰면 EOF만 받게 되고, 그 실패가 네트워크 단절로 오인되어 클라이언트의 불필요한 자동 재연결을 유발합니다.

실패 응답의 REP 의미:

| REP | 의미 | 서버 측 상황 |
|---|---|---|
| `0x01` | general SOCKS server failure | 연결 수가 `MAX_CONCURRENT_CONNS`에 도달했거나 UDP 슬롯 고갈(**서버 리소스 고갈**, 대상 문제 아님) |
| `0x04` | host unreachable | 대상 도달 불가. 5G 출구 UDP 소켓 생성 실패 포함 |

클라이언트는 두 경우 모두 **서버 측 거부/혼잡**(프로토콜 계층)으로 분류하고 네트워크 단절로 취급하지 않는 것이 좋습니다.

### 프레임 형식 (양방향 동일)

```
+--------+--------+-----...------+
| len_hi | len_lo | SOCKS5 UDP datagram |
+--------+--------+---------------------+
```

- `len`: 16-bit **network order**. 뒤따르는 데이터그램의 총 바이트 수(길이 필드 자체 제외)
- 데이터그램은 표준 SOCKS5 UDP 헤더를 사용합니다:

```
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
```

- `RSV=0`, `FRAG=0`
- `ATYP=0x01`(IPv4, 헤더 10B), `ATYP=0x04`(IPv6, 헤더 22B), `ATYP=0x03`(도메인, 헤더 `4 + 1 + len + 2`, 가변 길이)
  - `ATYP=0x03`은 RFC 1928 §7에서 명시적으로 허용됩니다 — UDP request header의 `ATYP`는 §5의 TCP CONNECT와 동일한 정의(0x01/0x03/0x04)를 공유합니다. 5G-Proxy-Client에서 **Remote DNS**를 켜면 클라이언트에는 fake IP만 있고 실제 이름은 서버가 **자신의 출구**로 해석해야 하므로, 전송되는 프레임은 도메인 형식입니다. 0x01/0x04만 인식하면 이런 프레임이 조용히 버려집니다(QUIC 등 UDP 전멸)
  - 서버는 **셀룰러 네트워크**로 해석합니다(`Socks5ProxyService.resolveWithCache`: IP 리터럴 빠른 경로 + 공유 캐시 + `DnsResolver` 타임아웃 + 동시성 백프레셔). 시스템 `getaddrinfo`는 **사용하지 않습니다** — 기본 네트워크를 사용하므로 Pro 폰에서는 VPN으로 우회할 수 있습니다. 해석 결과는 60초 TTL로 캐시되며(`simple-socks5.c`의 `resolve_domain_sockaddr`), 해석에 실패하면 해당 프레임을 버리고 로그를 남깁니다
- 서버는 프레임을 받아 대상 주소를 해석하고 5G 소켓으로 `sendto`합니다. 5G 응답을 받으면 출발지 주소로 프레임을 만들어 클라이언트로 보냅니다(v4-mapped는 항상 ATYP=0x01로 출력)

### 구현 위치

- `app/src/main/cpp/simple-socks5.c` → `handle_udp_tcp_session()` (`handle_handshake`에서 cmd==0x04 분기)

### 클라이언트 연동

- 5G-Proxy-Client에서 "UDP relay를 TCP로(UDP-in-TCP)"를 켜면 이 규격을 사용합니다. 일반 SOCKS5 서버에 대해서는 자동으로 표준 규격으로 폴백하므로 양쪽 모두 호환됩니다.
- 클라이언트에서 **Remote DNS**를 켜면 UDP 프레임에 `ATYP=0x03`(도메인)이 실리므로 서버가 이를 해석해야 합니다 — 위 프레임 형식 3번 항목 참고.
- 표준 `0x03`으로 폴백할 때 클라이언트는 **연결을 새로 맺습니다**(이 서버는 `REP≠0` 응답 후 기존 연결을 닫습니다). 클라이언트는 이 실패를 프로토콜 계층으로 분류해 자동 재연결 워치독을 **트리거하지 않습니다**. 따라서 "UDP-in-TCP를 켜면 연결이 안 된다"는 클라이언트 구버전의 버그는 재시작 루프를 유발했습니다. 서버의 책임은 "`REP≠0`을 정직하게 응답하고 연결을 닫는 것"뿐입니다.

---

## 주의 사항

- **Release 빌드는 기본적으로 서명되지 않습니다**: APK를 직접 배포하려면 프로젝트 루트에 `keystore.properties`(형식은 아래 참조)를 만들고 정식 keystore를 준비하세요. 해당 파일이 없으면 Release 빌드는 서명되지 않은 APK를 출력하며, F-Droid 같은 제3자가 자체 키로 서명합니다.
- 앱은 "셀룰러 네트워크 고정" 상태에서만 동작합니다. 셀룰러 신호가 없으면(SIM 없음) 서비스가 시작되지 않습니다.
- **기기 내 VPN과 동시에 사용할 수 있습니다. 단, 이 앱이 VPN에서 제외되어 있어야 합니다**: 시작할 때 실제로 소켓을 셀룰러 출구에 바인딩(`Network.bindSocket`)해 보고, **바인딩에 성공해야 시작**하며 실패하면 이유와 함께 거부합니다. "시스템에 VPN이 있다"는 것 자체는 더 이상 시작 거부 사유가 아닙니다 — per-app VPN(분할 터널링)에서 이 앱을 포함하지 않으면 셀룰러 출구를 그대로 쓸 수 있습니다.
  - 이 앱이 VPN 터널에 포함되면 시스템이 `bindSocket`을 `EPERM`으로 거부합니다. 이때는 VPN의 "앱별/제외 목록"에서 **이 앱(패키지명 `com.tokyoxpa3.androidproxy`)을 제외**해야 하며, VPN을 끄는 것이 아닙니다 — VPN을 끄면 용도 자체를 포기하는 셈입니다.
  - 같은 기기 동시 사용 예: 이 앱이 Wi-Fi에 연결된 채 5G 프록시를 제공할 때, 같은 폰에서 5G-Proxy-Client를 하나 더 실행하고(서버 주소는 이 앱이 표시하는 "Wi-Fi 프록시" IP) 이 앱을 터널 밖으로 제외하면 폰 자체 트래픽도 5G로 나갑니다. 자세한 내용은 5G-Proxy-Client의 `TUTORIAL.md` 참고.
  - VPN에 의해 차단되면(bind 거부 + 셀룰러가 시스템 검증상 여전히 사용 가능) 헬스 체크가 `BLOCKED_BY_VPN`으로 표시하고 **의도적으로 자동 재구성하지 않습니다** — 재시작으로는 설정 문제가 해결되지 않고 재시작 루프만 반복됩니다. 실제 단절은 기존의 "3회 연속 실패 시 자동 재구성"을 그대로 따릅니다.
- 프로젝트에는 `local.properties`(SDK 경로)와 `.gradle/`, `build/`, `.cxx/` 같은 생성 디렉터리가 포함되어 있으며, 이들은 `.gitignore`로 제외되어 업로드되지 않습니다.

## 네트워크 사용 안내

프록시 서비스 자체는 **로컬 수신과 전달만 수행**하며 스스로 외부에 연결하지 않습니다. 다음 경우에 외부 연결이 발생합니다:

| 기능 | 대상 | 시점 |
|---|---|---|
| 네이티브 엔진 헬스 체크 | `connectivitycheck.gstatic.com` / `captive.apple.com` / `connect.rom.miui.com` (순서대로 폴백, 5G 경유) | 10초마다, 서버 생존 확인 |
| 공인 IP 표시 | `api.ipify.org` | "상태 새로고침"을 누를 때 |
| 자가 진단 | example.com (5G 프록시 경유) | "자가 진단"을 누를 때만 |

모든 프록시 트래픽(클라이언트가 SOCKS5로 전달하는 트래픽)은 고정된 셀룰러 네트워크 인터페이스로 나갑니다.

### Release 서명 설정 (직접 배포 시)

프로젝트 루트에 `keystore.properties`를 만듭니다(`.gitignore`에 등록되어 업로드되지 않음):

```properties
storeFile=C:/path/to/your-release.keystore
storePassword=키스토어 비밀번호
keyAlias=키 별칭
keyPassword=키 비밀번호
```

이후 `.\gradlew.bat assembleRelease`를 실행하면 정식 키로 서명됩니다.

---

## 라이선스

이 프로젝트는 **MIT License**로 배포됩니다. 자세한 내용은 [LICENSE](LICENSE)를 참고하세요.
