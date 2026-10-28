# 5G Proxy Pro + Tailnet Relay

Android 폰의 **LTE/5G 회선을 SOCKS5 프록시로 공유**합니다. 같은 Wi-Fi에 있는 기기뿐 아니라 **Tailscale로 연결된 원격 기기**도 이 폰의 LTE 공인 IP로 나갈 수 있고, 원격으로 **IP를 바꿀 수**도 있습니다.

```
[클라이언트] ──Tailscale──▶ [Relay 앱 100.x.y.z:1080] ──loopback──▶ [5G Proxy Pro 127.0.0.1:1080] ──LTE──▶ 인터넷
[클라이언트] ──같은 Wi-Fi──────────────────────────────────▶ [5G Proxy Pro 192.168.x.x:1080] ──LTE──▶ 인터넷
```

| 앱 | 패키지 | 역할 |
|---|---|---|
| **5G Proxy Pro** | `com.tokyoxpa3.androidproxy` | SOCKS5 서버. 나가는 트래픽을 셀룰러(LTE/5G)에 고정 |
| **5G Proxy Tailnet Relay** | `com.tokyoxpa3.androidproxy.relay` | Tailscale 주소로 들어온 연결을 위 앱으로 전달 + IP 변경 API |

> **왜 앱이 두 개인가?** Android는 VPN 적용 여부를 앱 단위로 정합니다. 5G Proxy Pro를 Tailscale에서 **제외**하면 LTE로는 나가지만 Tailscale로 들어온 연결에 응답하지 못하고, **포함**하면 응답은 되지만 LTE로 나가는 것이 막힙니다(`EPERM`). 그래서 들어오는 쪽(Relay, Tailscale 포함)과 나가는 쪽(5G Proxy Pro, Tailscale 제외)을 나눴습니다.

---

## 1. 설치

GitHub Release에서 두 APK를 받아 설치하거나, 직접 빌드합니다.

```sh
# 필요: Android SDK, NDK 26.3.11579264, CMake 3.22.1
# 시스템 Java가 없으면 Android Studio 내장 JDK 사용
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r relay/build/outputs/apk/debug/relay-debug.apk
```

## 2. 폰 설정 (한 번만)

1. **Tailscale 앱 → 앱별 분할 터널링(Split tunneling)**
   - **5G Proxy Pro는 제외**합니다.
   - **5G Proxy Tailnet Relay는 제외하지 않습니다**(기본값).
2. **5G Proxy Pro**: 포트 `1080`(기본)으로 "Start 5G Proxy"를 누릅니다. 배터리 최적화 예외도 허용합니다.
3. **5G Proxy Tailnet Relay**
   - "Disable battery optimization"을 눌러 허용합니다. 허용하지 않으면 화면이 꺼졌을 때 Tailscale 연결이 끊길 수 있습니다.
   - "Set as default digital assistant"를 누르고 **기본 앱 → 디지털 어시스턴트 앱**(Samsung: "디바이스 지원 앱")에서 Relay를 선택합니다. IP 변경 기능에 필요합니다.
   - SOCKS 포트 `1080`, 제어 포트 `8081`을 확인하고 "Start relay"를 누릅니다.
   - 상태에 `Listening: 100.x.y.z`가 보이면 준비가 끝난 것입니다.
4. (선택) 5G Proxy Pro에 아이디/비밀번호를 설정했다면 Relay에도 같은 값을 입력합니다. IP 조회가 프록시를 거치기 때문입니다.

> Relay를 어시스턴트로 지정해 두면 재부팅·앱 업데이트·프로세스 종료 뒤에도 시스템이 다시 띄워 주고, 켜져 있던 Relay가 자동으로 다시 시작됩니다.

## 3. 프록시 사용

```sh
# Tailscale 경유 (원격)
curl -x socks5h://<폰의 Tailscale IP 또는 MagicDNS 이름>:1080 https://ifconfig.me

# 같은 Wi-Fi
curl -x socks5h://<폰의 Wi-Fi IP>:1080 https://ifconfig.me
```

두 경우 모두 폰의 LTE 공인 IP가 나오면 정상입니다.

## 4. 원격으로 IP 바꾸기

Relay가 Tailscale 주소의 `8081` 포트에서 제어 API를 엽니다. 비행기 모드를 켰다 끄면 통신사가 새 공인 IP를 배정합니다.

```sh
H=<폰의 Tailscale IP 또는 MagicDNS 이름>
curl -X POST http://$H:8081/rotate   # IP 변경 시작 (바로 202 응답, 진행 중이면 409)
curl http://$H:8081/status           # 결과 확인
curl http://$H:8081/ip               # 현재 LTE 공인 IP
```

`/status` 응답 예시:

```json
{"state":"changed","old_ip":"175.223.11.208","new_ip":"175.223.19.22","attempts":1,"error":null,
 "started_at":1793113079490,"finished_at":1793113092554,"assistant_active":true}
```

| `state` | 의미 |
|---|---|
| `running` | 진행 중 |
| `changed` | 새 IP로 바뀜 |
| `unchanged` | 3번 시도했지만 통신사가 같은 IP를 줌 |
| `failed` | 실패. `error` 참고 |

### `/status`의 `network`, `data_usage`

`/status`는 위 항목에 더해 OS가 가진 네트워크 상태와 데이터 사용량을 함께 돌려줍니다. 조회할 때 추가 데이터는 쓰지 않습니다.

```json
"network": {"internet_ok":true,"cellular_internet_ok":true,"airplane_mode":false,
  "networks":[{"transport":"cellular","interface":"rmnet4","validated":true,"captive_portal":false,"metered":true,"suspended":false}, ...]},
"data_usage": {"today":{"total_mb":1.8,...},"this_month":{"total_mb":28.3,...},"last_30_days":{...}}
```

- `validated`: Android가 직접 검사해서 실제로 인터넷이 된다고 판단한 망입니다. 셀룰러가 있는데 `false`이면 데이터 소진으로 차단됐거나, 통신사 안내 페이지로 리다이렉트되는 중이거나(`captive_portal`), 신호가 약한 경우입니다.
- `cellular_internet_ok`: 셀룰러 망이 없으면(비행기 모드, 데이터 꺼짐) `null`입니다.
- `data_usage`: 폰 전체의 모바일 데이터 사용량입니다(설정 > 데이터 사용과 같은 값). `this_month`는 매달 1일부터 계산합니다. 설정에 지정한 사용 주기 시작일은 앱이 읽을 수 없습니다. 통계는 약 2시간 단위로 묶여서 최근 사용량은 늦게 반영될 수 있습니다.
- `data_usage`를 보려면 Relay에서 **"Allow usage access"**를 눌러 **사용 정보 접근**을 허용해야 합니다. Android 9 이하는 전화 권한도 허용해야 합니다. adb로 허용하려면 `adb shell appops set com.tokyoxpa3.androidproxy.relay GET_USAGE_STATS allow`를 실행합니다.

- 진행 중 10~30초 동안은 **Wi-Fi와 Tailscale도 끊깁니다.** 이때 API 호출이 실패하는 것은 정상이고, 폰이 혼자 끝까지 진행합니다. 다시 연결된 뒤 `/status`로 확인하세요. 실패하더라도 비행기 모드는 반드시 다시 끕니다.
- 진행 중이던 프록시 연결은 모두 끊깁니다. 프록시를 다시 시작할 필요는 없습니다.
- 제어 API에는 **인증이 없습니다.** tailnet 안의 모든 기기가 호출할 수 있으므로, 필요하면 Tailscale ACL로 접근을 제한하세요.

## 5. 문제 해결

| 증상 | 원인 / 조치 |
|---|---|
| Tailscale IP로 접속 시 **시간 초과** | Relay가 꺼져 있거나 Tailscale에서 제외돼 있음. Relay 상태에 `Listening: 100.x.y.z`가 있는지 확인 |
| Tailscale IP로 접속 시 **연결 거부** | 5G Proxy Pro가 꺼져 있음 |
| 5G Proxy Pro가 **시작되지 않음** (VPN 관련 오류) | 5G Proxy Pro가 Tailscale에 포함돼 있음 → 분할 터널링에서 제외 |
| `/rotate` → `failed`, `relay is not the default digital assistant` | 2-3단계의 디지털 어시스턴트 지정이 안 됨 |
| `/rotate` → `failed`, `airplane mode did not turn on` | 어시스턴트 지정을 다시 하고 Relay를 재시작. 계속되면 `adb logcat \| grep -i Airplane`로 확인 |
| `/rotate` → `unchanged` | 통신사가 같은 IP를 다시 배정함. 잠시 후 재시도 |
| 표준 SOCKS5 **UDP ASSOCIATE**가 Tailscale 경유로 안 됨 | Relay는 TCP만 전달함(제약). 같은 Wi-Fi에서는 동작하며, 앱 자체 UDP-in-TCP(`0x04`)는 Tailscale 경유로도 동작 |

---

검증 환경: Samsung Galaxy S8 (SM-G950F, Android 9), KT LTE.
원본 프로젝트: [tokyoxpa3/5G-Proxy-Pro](https://github.com/tokyoxpa3/5G-Proxy-Pro) · 라이선스: [LICENSE](LICENSE)
