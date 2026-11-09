package com.tokyoxpa3.androidproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CellularNetworkManager(private val context: Context) {
    
    private val connectivityManager: ConnectivityManager by lazy {
        try {
            val service = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            val cm = service as? ConnectivityManager
            if (cm == null) {
                val errorMsg = "Cannot cast service to ConnectivityManager. Service type: ${service?.javaClass?.name}"
                android.util.Log.e("CellularNetwork", errorMsg)
                throw IllegalStateException(errorMsg)
            }
            cm
        } catch (e: ClassCastException) {
            android.util.Log.e("CellularNetwork", "ClassCastException when getting ConnectivityManager", e)
            throw IllegalStateException("Cannot cast service to ConnectivityManager", e)
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Failed to initialize ConnectivityManager", e)
            throw e
        }
    }
    @Volatile
    private var cellularNetwork: Network? = null
    private var monitorCallback: ConnectivityManager.NetworkCallback? = null
    /** 目前 LinkProperties 的簽章：只用來判斷「同一個 Network 的位址是否真的變了」。 */
    @Volatile
    private var linkSignature: String? = null
    // awaitNetwork 的續體：startMonitoring 的 onAvailable 抵達時喚醒首次等待
    @Volatile
    private var pendingNetworkWaiter: ((Network?) -> Unit)? = null
    
    /**
     * 一次性取得目前的行動網路（電信端切換 5G IP 時系統通常會重建 Network 物件，
     * 因此每次都需要重新請求）。取得結果後會自動移除 callback，避免累積註冊。
     */
    suspend fun requestCellularNetwork(timeoutMs: Long = 10000L): Network? = try {
        // 已經有 pin 住的蜂巢網路時直接回傳。UI 顯示的 IP 必須與代理實際使用的
        // Network 一致 —— 重新 requestNetwork 可能拿到另一個 PDN（出口 IP 不同），
        // 實測就出現「畫面顯示的公網 IP 與代理實際出口完全不同」的脫節。
        cellularNetwork?.let { return it }
        var callback: ConnectivityManager.NetworkCallback? = null
        var unregistered = false
        fun unregisterOnce(cb: ConnectivityManager.NetworkCallback) {
            if (unregistered) return
            unregistered = true
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                android.util.Log.e("CellularNetwork", "Error unregistering network callback", e)
            }
        }
        
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                
                val resumed = AtomicBoolean(false)
                
                fun resumeOnce(network: Network?) {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resume(network)
                    }
                }
                
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        try {
                            cellularNetwork = network
                            resumeOnce(network)
                        } catch (e: Exception) {
                            android.util.Log.e("CellularNetwork", "Error in onAvailable callback", e)
                        }
                    }
                    
                    override fun onUnavailable() {
                        super.onUnavailable()
                        android.util.Log.w("CellularNetwork", "Network request unavailable")
                        try {
                            resumeOnce(null)
                        } catch (e: Exception) {
                            android.util.Log.e("CellularNetwork", "Error in onUnavailable callback", e)
                        }
                    }
                }
                
                try {
                    connectivityManager.requestNetwork(networkRequest, callback!!)
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Failed to request network", e)
                    continuation.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }
                
                continuation.invokeOnCancellation {
                    callback?.let { unregisterOnce(it) }
                }
            }
        }
        
        // 移除此一次性 callback（避免在 callback 執行期間直接 unregister）
        callback?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { unregisterOnce(cb) }
        }
        result
    } catch (e: Exception) {
        android.util.Log.e("CellularNetwork", "Exception in requestCellularNetwork", e)
        null
    }
    
    /**
     * 開始長期監控行動網路變更：
     *  - onAvailable：取得一個「與目前不同」的新網路物件（電信端切換 5G IP 時觸發）
     *  - onLost：目前鎖定的行動網路已失效
     * 註冊時系統會立即回報目前的網路，若與既有網路相同則不會觸發 onAvailable。
     */
    fun startMonitoring(
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        onLinkChanged: () -> Unit = {}
    ) {
        stopMonitoring()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                try {
                    cellularNetwork = network
                    // 記下初始簽章：onAvailable 之後系統通常還會再送一次
                    // onLinkPropertiesChanged，內容相同就不該觸發清理。
                    linkSignature = signatureOf(network)
                    pendingNetworkWaiter?.invoke(network)
                    onAvailable(network)
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Error in monitor onAvailable", e)
                }
            }

            /**
             * 同一個 Network 物件、只換了本地 IP（電信端重新 attach、PDN 位址變更）。
             *
             * 這種情況不會再觸發 onAvailable，但舊 TCP 連線的四元組已經失效，
             * 必須通知上層清理。只比對 IP 與介面名稱 —— DNS 或 MTU 變動不影響
             * 既有連線，納入比較會把正在連線中的遊戲／語音一起切斷。
             */
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                super.onLinkPropertiesChanged(network, lp)
                try {
                    val signature = signatureOf(lp)
                    val previous = linkSignature
                    linkSignature = signature
                    // previous == null 代表註冊後的第一次回報（初始值），不是變更。
                    if (previous != null && previous != signature && network == cellularNetwork) {
                        android.util.Log.w(
                            "CellularNetwork",
                            "行動網路位址已變更（$previous → $signature），通知上層清理舊出口"
                        )
                        onLinkChanged()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Error in onLinkPropertiesChanged", e)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                try {
                    if (network == cellularNetwork) {
                        cellularNetwork = null
                        onLost()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Error in monitor onLost", e)
                }
            }
        }
        monitorCallback = callback
        try {
            // [重建迴圈根因] 用 requestNetwork（非 registerNetworkCallback）：請求本身
            // 會讓系統持續為本 app 維持此蜂巢式 PDN，直到 stopMonitoring 解除註冊。
            // 舊寫法是「一次性 requestNetwork 拿到網路後立刻放掉」＋另一個 LISTEN
            // 監控，但 LISTEN 只被動觀察、不會 pin 住網路；在 WiFi 為預設網路的裝置上
            // 蜂巢式 PDN 會被系統收回 → onLost → 代理重建 → 新一輪 request → 再度收回，
            // 形成重建迴圈（實測 5 分鐘內重建 16 次、onLost 時有 163 條連線被切斷）。
            connectivityManager.requestNetwork(cellularRequest(), callback)
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Failed to register network monitor", e)
            monitorCallback = null
        }
    }

    private fun cellularRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    /**
     * 等待 [startMonitoring] 回報第一個可用行動網路；若已有網路立即回傳。
     * 逾時回傳 null，呼叫端據此判定「找不到行動網路」。
     */
    suspend fun awaitNetwork(timeoutMs: Long = 15000L): Network? {
        cellularNetwork?.let { return it }
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    fun resumeOnce(network: Network?) {
                        if (resumed.compareAndSet(false, true)) continuation.resume(network)
                    }
                    pendingNetworkWaiter = { network -> resumeOnce(network) }
                    continuation.invokeOnCancellation { pendingNetworkWaiter = null }
                    // [競態修復] onAvailable 可能恰在「上面的初始檢查」與「安裝 waiter」之間
                    // 抵達：此時 waiter 還沒裝好、不會被喚醒，awaitNetwork 會白白等到逾時，
                    // 讓 startProxy 誤判「找不到行動網路」。安裝完 waiter 後補一次檢查即可。
                    cellularNetwork?.let { resumeOnce(it) }
                }
            }
        } finally {
            pendingNetworkWaiter = null
        }
    }
    
    fun stopMonitoring() {
        try {
            monitorCallback?.let { cb ->
                connectivityManager.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Error unregistering network monitor", e)
        }
        monitorCallback = null
        pendingNetworkWaiter = null
        linkSignature = null
    }

    private fun signatureOf(network: Network): String? = try {
        connectivityManager.getLinkProperties(network)?.let { signatureOf(it) }
    } catch (e: Exception) {
        null
    }

    private fun signatureOf(lp: LinkProperties): String = LinkSignature.from(
        lp.linkAddresses.mapNotNull { la ->
            val addr = la.address ?: return@mapNotNull null
            // 排除 IPv6 隱私臨時位址（privacy extension，flags bit0 = IFA_F_TEMPORARY）。
            // 它會週期性輪換，但 IPv4 出口通常完全沒變；把它算進簽章會讓
            // 「只是換了臨時位址」被當成換 IP，進而全切所有連線、誤殺遊戲。
            // 真正走 IPv6 的連線若因此失效，由 native 的停滯偵測兜底。
            if (addr is java.net.Inet6Address && (la.flags and 0x01) != 0) null
            else addr.hostAddress ?: ""
        },
        lp.interfaceName
    )
    
    fun releaseCellularNetwork() {
        stopMonitoring()
        cellularNetwork = null
    }
    
    fun currentNetwork(): Network? = cellularNetwork
}
