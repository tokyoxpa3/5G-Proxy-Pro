package com.tokyoxpa3.androidproxy.network

/**
 * 5G 出口的健康狀態。
 *
 * 分界線只有一條：**要不要重建、重建幾次**。
 *
 * - [BLOCKED_BY_VPN]：被 VPN 納入隧道，是設定問題。重建一萬次也不會好，而且會把
 *   既有連線整批切斷，所以**絕不重建**。
 * - [NETWORK_DEAD]：網路本體真的沒了（caps 消失、失去 INTERNET、bind 被拒）。
 *   重建可能拿到新的 PDN，值得重建。
 * - [CELLULAR_STALLED]：**bind 過得去、但探測收不到任何位元組**。這一態是為了
 *   issue #8 才拆出來的 —— 實測那台 Galaxy S23（One UI 9）可以 bind、可以完成
 *   TCP 三方握手、DNS 也解析得出來，但應用層資料一個位元組都回不來。這種情況
 *   重建代理**修不了**（問題在資料平面，不在引擎），卻每 45 秒把所有在途連線
 *   砍一次，讓症狀更糟。因此只允許有限次數的重建，之後停手並明確回報。
 *
 * 誤判方向決定代價：把「真斷線」判成 [CELLULAR_STALLED] 只會少重建幾次（仍會重建
 * 上限次數）；把「資料平面卡住」判成 [NETWORK_DEAD] 則會無限重建、把連線全部砍光。
 */
enum class NetworkHealthStatus { OK, BLOCKED_BY_VPN, CELLULAR_STALLED, NETWORK_DEAD }

/**
 * 健康檢查的判定（純函式，不依賴 Android，可用 JVM 單元測試）。
 *
 * 判定順序刻意如此：
 *
 * 1. **網路本體沒了**（caps == null 或失去 INTERNET）→ [NetworkHealthStatus.NETWORK_DEAD]。
 *    這與 VPN 無關：VPN 是在蜂巢式之上再疊一層虛擬網路，不會讓底層的 Network
 *    物件消失或失去 INTERNET capability。舊版「caps 異常就怪 VPN」的推論是錯的，
 *    實測症狀是：Client 的 VPN 一開，5G 真的斷線也會被判成 BLOCKED_BY_VPN，
 *    於是永遠不重建、只能手動重啟。
 *
 * 2. **bindSocket 探測** —— 這才是 VPN 真正會破壞的那一步
 *    （實機 log: `Binding socket to network 176 failed: EPERM`）。
 *    bind 失敗 + VPN 運作中 + 蜂巢式仍被系統驗證可用（VALIDATED）
 *    → [NetworkHealthStatus.BLOCKED_BY_VPN]，其餘一律 NETWORK_DEAD。
 *    `cellularValidated` 這個附加條件是必要的：只憑「有沒有 VPN」判斷，會把真實
 *    斷線誤判成 VPN 阻擋；而蜂巢式若連系統驗證都過不了，不管有沒有 VPN 都該重建。
 *
 * 3. **bind 過得去、探測也過** → 出口是通的 → [NetworkHealthStatus.OK]。
 *
 * 4. **bind 過得去、探測收不到位元組** → [NetworkHealthStatus.CELLULAR_STALLED]。
 *    舊版這一步直接歸成 NETWORK_DEAD，於是無限重建。但 bind 成功代表系統肯把這個
 *    網路給本 App、fwmark 與路由都正常；連線建得起來卻沒有資料回流，問題在下游
 *    （PDN 的資料平面、路徑 MTU、或中間設備），重建 native 引擎不會改變它。
 *
 * 注意：這裡判的是「**本 App 能不能用蜂巢式出口**」，不是「系統裡有沒有 VPN」。
 * 兩者差很多 —— per-app VPN（split tunneling）沒包含本 App 時，VPN 存在但完全不影響。
 */
object NetworkHealthClassifier {

    fun classify(
        hasInternetCapability: Boolean,
        cellularValidated: Boolean,
        bindSucceeded: Boolean,
        vpnActive: Boolean,
        probeSucceeded: Boolean
    ): NetworkHealthStatus {
        if (!hasInternetCapability) return NetworkHealthStatus.NETWORK_DEAD

        if (!bindSucceeded) {
            return if (vpnActive && cellularValidated) {
                NetworkHealthStatus.BLOCKED_BY_VPN
            } else {
                NetworkHealthStatus.NETWORK_DEAD
            }
        }

        return if (probeSucceeded) NetworkHealthStatus.OK else NetworkHealthStatus.CELLULAR_STALLED
    }
}
