# 5G Proxy Pro (AndroidProxy)

一個將手機 **5G / 蜂巢式網路流量強制鎖定並透過 SOCKS5 伺服器分享**的 Android App。

使用 Android 的 `ConnectivityManager.requestNetwork()` 鎖定蜂巢式網路（TRANSPORT_CELLULAR），配合原生 C 實作的 SOCKS5 伺服器（epoll 多路複用），將經由 **5G 網路的 TCP/UDP 流量**透過代理分享給其他裝置（同 Wi-Fi 下的手機、電腦等）。

> This app locks your device to the cellular (5G/4G) connection and exposes a SOCKS5 proxy over Wi-Fi, so other devices can route their traffic through the phone's cellular network. The proxy engine is a native C (epoll-based) implementation stripped via JNI.

---

## 功能特色

- 🔒 **強制鎖定蜂巢式網路** — 透過 `NetworkRequest` 將 Socket 綁定到 5G 網路（`Network.bindSocket`），不受 Wi-Fi 干擾
- 🧦 **完整 SOCKS5 代理** — 原生 C 引擎（epoll，可併發），支援 TCP（CONNECT）與 UDP（ASSOCIATE），IPv4 / IPv6 / 網域 (DOMAIN) 地址皆支援
- 🔁 **IPv6 自動 Fallback** — TCP 連線依序嘗試所有解析出的地址（IPv6/IPv4）
- ❤️ **20 秒循環心跳** — 防止 5G 掉線或進入省電模式
- 🛡️ **原生引擎健康檢查** — 每 10 秒驗證 SOCKS5 伺服器存活，異常自動重啟
- 📊 **開發者測速工具** — 綁定 5G 網路的原始下載測速（多伺服器自動切換）
- 📶 顯示 Wi-Fi 內網 IP / 5G 公網 IP、上次退出原因（低記憶體、崩潰等）

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

4. 安裝完畢後，需要 **使用系統連接受權**（`FOREGROUND_SERVICE_DATA_SYNC`），App 首次執行會提示允許電池最佳化白名單，建議照提示操作（尤其小米/三星使用者）。

---

## 使用方式

1. 開啟 App（5G Proxy Pro）
2. 輸入代理連接埠（預設 `1080`）
3. 點「🚀 Start 5G Proxy」，等待狀態顯示「✅ 5G Proxy Running」
4. 在同一個 Wi-Fi 下的其他裝置設定 SOCKS5 代理（例如手機 Wi-Fi 進階設定，或電腦系統 Proxy 設定）：

```
伺服器：手機的 Wi-Fi IP（App 會顯示）
連接埠：1080
通訊協定：SOCKS5
```

> SOCKS5 代理本身不需要驗證（開放代理）。請務必只在信任的區域網路使用！

停止方式：回到 App 點「🛑 Stop Proxy Service」，或關閉服務。

---

## 專案架構

```
AndroidProxy/
├── app/src/main/
│   ├── java/com/example/androidproxy/
│   │   ├── DebugActivity.kt          # 主介面 + 測速
│   │   ├── Socks5ProxyService.kt    # 前台服務：鎖定 5G、啟動 C 引擎
│   │   ├── NativeEngine.kt          # JNI 橋接（socketProvider 回呼）
│   │   ├── PowerPermissionHelper.kt # 各品牌電池最佳化白名單引導
│   │   └── network/
│   │       ├── CellularNetworkManager.kt  # requestNetwork() 鎖定與釋放
│   │       └── PublicIPChecker.kt         # 5G 公網 IP 查詢
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

## 注意事項

- **Release 版目前使用 Debug 簽署**（`app/build.gradle` 中 `signingConfig signingConfigs.debug`），目的在讓 Google 認證的測試機可安裝。要上架商店需改為正式簽署設定。
- App 僅於「鎖定蜂巢狀網路」的狀態下運作，若沒有蜂巢狀訊號（無 SIM 卡）服務將無法啟動。
- 專案包含 `local.properties`（SDK 路徑）與 `.gradle/`、`build/`、`.cxx/` 等產生目錄，這些已被 `.gitignore` 忽略，不會上傳。
- 測速功能使用多個公網測速伺服器（Linode 新加坡/東京、OVH 等），某些電信運營商可能對國際頻寬限制而測速偏低。

---

## 授權

本專案目前未指定授權條款（All Rights Reserved）。如需開源發布，建議在 GitHub 上加入 LICENSE 檔案（如 MIT）。