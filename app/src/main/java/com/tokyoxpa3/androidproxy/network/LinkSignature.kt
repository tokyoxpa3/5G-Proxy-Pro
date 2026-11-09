package com.tokyoxpa3.androidproxy.network

/**
 * 行動網路「身分」的簽章（純函式，可用 JVM 單元測試）。
 *
 * 只用來回答一個問題：**同一個 [android.net.Network] 物件的出口位址是不是換了？**
 *
 * 刻意只納入 IP 位址與介面名稱：
 *  - 若把 DNS / MTU / 路由納入，電信端只是換 DNS 伺服器或系統調整 MTU，
 *    就會被誤判成「換 IP」，進而切斷正在連線中的遊戲與語音。這類變更對
 *    既有 TCP 連線的四元組毫無影響，不該觸發任何清理。
 *  - 位址先排序再串接：系統回報的 linkAddresses 順序不保證穩定，直接
 *    join 會產生「內容相同但字串不同」的假變更，導致每次回呼都誤切。
 */
object LinkSignature {

    fun from(addresses: List<String>, interfaceName: String?): String {
        val normalized = addresses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(",")
        return "${interfaceName ?: "-"}|$normalized"
    }
}
