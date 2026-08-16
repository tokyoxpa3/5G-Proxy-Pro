# 5G Proxy Pro (AndroidProxy)

一個將手機 **5G / 蜂巢式網路流量強制鎖定並透過 SOCKS5 伺服器分享**的 Android App。

使用 Android 的 `ConnectivityManager.requestNetwork()` 鎖定蜂巢式網路（TRANSPORT_CELLULAR），配合原生 C 實作的 SOCKS5 伺服器（epoll 多路複用），將經由 **5G 網路的 TCP/UDP 流量**透過代理分享給其他裝置（同 Wi-Fi 下的手機、電腦等）。

> This app locks your device to the cellular (5G/4G) connection and exposes a SOCKS5 proxy over Wi-Fi, so other devices can route their traffic through the phone's cellular network. The proxy engine is a native C (epoll-based) implementation stripped via JNI.

---

## 功能特色

- 🔒 **強制鎖定蜂巢式網路** — 透過 `NetworkRequest` 將 Socket 綁定到 5G 網路（`Network.bindSocket`），不受 Wi-Fi 干擾
- 🔑 **可選帳密認證**（RFC 1929）— 預設無認證（開放代理）；**帳號與密碼兩者都填寫**才會啟用認證，任一欄位留空即不啟用（避免空值放行任意輸入）
- 🧦 **完整 SOCKS5 代理** — 原生 C 引擎（epoll，可併發），支援 TCP（CONNECT）與 UDP（ASSOCIATE），IPv4 / IPv6 / 網域 (DOMAIN) 地址皆支援
- 🛰️ **只綁定 LAN 介面** — 監聽器僅綁定 Wi-Fi / 熱點分享 / USB 分享等區域網路位址（另有本機 loopback 供健康檢查），**絕不暴露在行動網路介面上**
- 🧵 **UDP-in-TCP** — 自訂擴充指令 `0x04`：UDP relay 資料以 frame 走同一條 TCP 連線，不受 UDP 壅塞/優先權影響（見下方協定規格）
- 🔁 **IPv6 自動 Fallback** — TCP 連線依序嘗試所有解析出的地址（IPv6/IPv4）
- ❤️ **20 秒循環心跳** — 防止 5G 掉線或進入省電模式
- 🛡️ **原生引擎健康檢查** — 每 10 秒驗證 SOCKS5 伺服器存活，異常自動重啟；內建停止旗標，不會與「使用者按下停止」競態造成殭屍監聽
- 📢 **真實狀態回報** — Service 透過 callback 回報實際狀態（啟動中/運行中/暫停/重建中/已停止/失敗），UI 不靠猜測
- 🔔 **通知權限** — Android 13+ 啟動前會請求 `POST_NOTIFICATIONS`，前景服務通知正常顯示
- 📊 **開發者測速工具** — 綁定 5G 網路的原始下載測速（多伺服器自動切換）
- 📶 顯示 Wi-Fi 內網 IP / 熱點分享 IP / 5G 公網 IP，附「刷新狀態」按鈕、上次退出原因（低記憶體、崩潰等）

---

## 系統需求（編譯環境）

| 項目 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK Platform | 34 (compileSdk) |
| Android NDK | **26.3.11579264** |
| CMake | **3.22.1** |
| Gradle (wrapper) | 8.4（自動下載） |
| Android Gradle Plugin | 8.1.2 |
| Kotlin | 1.9.20 |
| 最低 Android 版本 | Android 8.0 (API 26)，需支援蜂巢狀網路與 `requestNetwork()` |

> ⚠️ NDK 與 CMake 必須精確安裝 **26.3.11579264** 與 **3.22.1**，否則建置會失敗。

---

## 編譯教學

### 方法一：Android Studio（推薦）

1. 安裝 [Android Studio](https://developer.android.com/studio)（內含 JDK 17）
2. **SDK Manager**（Settings → Languages & Frameworks → Android SDK → SDK Tools）安裝：
   - **NDK (Side by side) → 26.3.11579264**
   - **CMake → 3.22.1**
3. 用 Android Studio 開啟 `AndroidProxy` 資料夾，等待 Gradle 同步完成
4. 選單 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. APK 位置：`app\build\outputs\apk\debug\app-debug.apk`

### 方法二：命令列（無需 Android Studio）

```powershell
# 前置需求：JDK 17+、Android SDK、NDK 26.3.11579264、CMake 3.22.1
# 首次需建立 local.properties（指向你的 SDK 路徑）：
#   sdk.dir=C:\\Users\\你的使用者\\AppData\\Local\\Android\\Sdk

cd AndroidProxy

# Debug 版（含自動簽名）
.\gradlew.bat assembleDebug

# Release 版（註：目前使用 Debug 簽署，見下方說明）
.\gradlew.bat assembleRelease

# 安裝到已連接的手機
.\gradlew.bat installDebug
```

- **Windows**：`gradlew.bat`　**macOS / Linux**：`./gradlew`
- 產出位置：
  - Debug：`app/build/outputs/apk/debug/app-debug.apk`
  - Release：`app/build/outputs/apk/release/app-release.apk`

---

## 安裝到手機

1. 手機開啟「開發者選項」→ 開啟「USB 偵錯」
2. 連接 USB，執行 `adb devices` 確認出現 `device`
3. 安裝：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. 安裝完畢後，需要 **使用系統連接受權**（`FOREGROUND_SERVICE_CONNECTED_DEVICE`），App 首次執行會提示允許電池最佳化白名單，建議照提示操作（尤其小米/三星使用者）。

---

## 使用方式

1. 開啟 App（5G Proxy Pro）
2. 輸入代理連接埠（預設 `1080`）
3. （可選）輸入「使用者」與「密碼」——**兩者都留空 = 無認證（開放代理）**；**兩者都填寫**才會啟用認證（RFC 1929），任一欄位留空即為無認證。**代理運行中修改帳密會即時套用**（新連線生效，已連線的舊連線不受影響），不需停止重啟
4. Android 13+ 首次啟動會請求**通知權限**，允許後前景服務通知才能正常顯示（拒絕仍可運行，App 會提示）
5. 點「🚀 Start 5G Proxy」，等待狀態顯示「✅ 5G Proxy Running」
6. 在同一個 Wi-Fi 下的其他裝置設定 SOCKS5 代理（例如手機 Wi-Fi 進階設定，或電腦系統 Proxy 設定）：

```
伺服器：手機的 Wi-Fi IP（App 會顯示）
連接埠：1080
通訊協定：SOCKS5
使用者名稱：<有設定才需要填>
密碼：<有設定才需要填>
```

> ⚠️ 若未設定帳密即為開放代理，任何能連到手機位址的人都能使用。請務必只在信任的區域網路使用，或設定帳密保護！
>
> ℹ️ 代理監聽器**只綁定 LAN 介面**（Wi-Fi / 熱點 / USB 分享，另有本機 loopback 供健康檢查），無法從行動網路介面存取；若裝置沒有任何可用 LAN 介面，啟動會失敗並顯示錯誤訊息。

停止方式：回到 App 點「🛑 Stop Proxy Service」。停止後所有監聽器會立即關閉、Port 完全釋放，且不會被系統或健康檢查悄悄重啟（v1.4.0 起）。

---

## 專案架構

```
AndroidProxy/
├── app/src/main/
│   ├── java/com/tokyoxpa3/androidproxy/
│   │   ├── DebugActivity.kt          # 主介面 + 測速 + 熱點 IP 顯示
│   │   ├── Socks5ProxyService.kt    # 前台服務：鎖定 5G、啟動 C 引擎
│   │   ├── NativeEngine.kt          # JNI 橋接（socketProvider 回呼）
│   │   ├── PowerPermissionHelper.kt # 各品牌電池最佳化白名單引導
│   │   └── network/
│   │       ├── CellularNetworkManager.kt  # requestNetwork() 鎖定與釋放
│   │       ├── PublicIPChecker.kt         # 5G 公網 IP 查詢
│   │       └── HotspotManager.kt          # 熱點分享 IP 偵測
│   └── cpp/
│       ├── jni_bridge.c       # JNI 註冊、Java↔C 呼叫橋樑
│       ├── simple-socks5.c    # epoll SOCKS5 引擎（TCP/UDP）
│       └── CMakeLists.txt
```

### 流量路徑

```
[客戶端] --SOCKS5--> [App: C 引擎簡單] --JNI--> [Java: Network.bindSocket(5G)] --> [電信網路]
```

C 引擎收到客戶端連線後，透過 JNI 呼叫 Java `createSocketBoundToNetwork()`，使用電子網路專屬的 Socket 進行真正的資料傳輸，確保所有流量走 5G/蜂巢狀，而非 Wi-Fi。

---

## UDP-in-TCP 協定規格（自訂擴充指令 0x04）

標準 SOCKS5 的 UDP relay 走獨立的 UDP 通道（UDP ASSOCIATE）；本伺服器另支援自訂指令 `0x04`「UDP ASSOCIATE over TCP」，UDP 資料以 frame 形式走**同一條 TCP 控制連線**，避免 UDP 在壅塞網路上被丟棄、以及 NAT/防火牆對 UDP 的影響。

### 握手

```
Client → Server: {0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0}   （SOCKS5 request，cmd=0x04）
Server → Client: {0x05, 0x00, 0x00, 0x01, BND.ADDR, BND.PORT}（標準成功回覆，BND 全 0）
```

成功回覆後，同一條 TCP 連線雙向以 frame 承載 UDP datagram。若伺服器不支援 0x04，會回覆 REP≠0，客戶端應在同一條連線退回標準 `0x03`（UDP ASSOCIATE）。

### Frame 格式（雙向相同）

```
+--------+--------+-----...------+
| len_hi | len_lo | SOCKS5 UDP datagram |
+--------+--------+---------------------+
```

- `len`：16-bit **network order**，為後方 datagram 的總位元組數（不含長度欄本身）
- datagram 採用標準 SOCKS5 UDP header：

```
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
```

- `RSV=0`、`FRAG=0`
- `ATYP=0x01`（IPv4，表頭 10B）或 `ATYP=0x04`（IPv6，表頭 22B）；不支援網域 ATYP=0x03
- 伺服器收到 frame 後解析目標位址，以 5G socket `sendto`；收到 5G 回覆後以來源位址封裝為 frame 送回客戶端（v4-mapped 一律輸出 ATYP=0x01）

### 實作位置

- `app/src/main/cpp/simple-socks5.c` → `handle_udp_tcp_session()`（`handle_handshake` 分派 cmd==0x04）

### 搭配客戶端

- 5G-Proxy-Client 勾選「UDP relay 走 TCP（UDP-in-TCP）」即使用本協定；對一般 SOCKS5 伺服器會自動退回標準協定，兩者皆可互通。

---

## 注意事項

- **Release 版預設不簽名**：若要自行發布 APK，請在專案根目錄建立 `keystore.properties`（格式見下方），並準備一個正式 keystore；若沒有該檔案，Release 版會輸出未簽名的 APK，供 F-Droid 等第三方以自身金鑰簽署。
- App 僅於「鎖定蜂巢狀網路」的狀態下運作，若沒有蜂巢狀訊號（無 SIM 卡）服務將無法啟動。
- 代理監聽器只綁定 LAN 介面與本機 loopback；若同時想從 VPN 隧道存取代理，請改用熱點分享或 USB 分享。
- 專案包含 `local.properties`（SDK 路徑）與 `.gradle/`、`build/`、`.cxx/` 等產生目錄，這些已被 `.gitignore` 忽略，不會上傳。
- 測速功能使用多個公網測速伺服器（Linode 新加坡/東京、OVH 等），某些電信運營商可能對國際頻寬限制而測速偏低。

## 網路使用說明

代理服務本身**只做本機監聽與轉送**，不會自行對外連線；以下情況會產生對外連線：

| 功能 | 目的地 | 時機 |
|---|---|---|
| 原生引擎健康檢查 | `connectivitycheck.gstatic.com`（透過 5G） | 每 10 秒，驗證伺服器存活 |
| 公網 IP 顯示 | `api.ipify.org` | 按下「刷新狀態」時 |
| 開發者測速（僅開發用途） | Linode 新加坡/東京、OVH 等測速伺服器 | 僅在點擊測速按鈕時 |

所有代理流量（客戶端透過 SOCKS5 轉送）都經由鎖定的蜂巢式網路介面輸出。

### Release 簽名設定（自行發布時）

在專案根目錄建立 `keystore.properties`（已被 `.gitignore` 忽略，不會上傳）：

```properties
storeFile=C:/path/to/your-release.keystore
storePassword=你的儲存庫密碼
keyAlias=你的金鑰別名
keyPassword=你的金鑰密碼
```

之後執行 `.\gradlew.bat assembleRelease` 即會以正式金鑰簽名。

---

## 授權

本專案以 **MIT License** 授權釋出，詳見 [LICENSE](LICENSE)。