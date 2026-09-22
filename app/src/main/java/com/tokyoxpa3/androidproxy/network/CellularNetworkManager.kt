package com.tokyoxpa3.androidproxy.network

import android.content.Context
import android.net.ConnectivityManager
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
    // awaitNetwork 的續體：startMonitoring 的 onAvailable 抵達時喚醒首次等待
    @Volatile
    private var pendingNetworkWaiter: ((Network?) -> Unit)? = null
    
    /**
     * 一次性取得目前的行動網路（電信端切換 5G IP 時系統通常會重建 Network 物件，
     * 因此每次都需要重新請求）。取得結果後會自動移除 callback，避免累積註冊。
     */
    suspend fun requestCellularNetwork(timeoutMs: Long = 10000L): Network? = try {
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
    fun startMonitoring(onAvailable: (Network) -> Unit, onLost: () -> Unit) {
        stopMonitoring()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                try {
                    cellularNetwork = network
                    pendingNetworkWaiter?.invoke(network)
                    onAvailable(network)
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Error in monitor onAvailable", e)
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
    }
    
    fun releaseCellularNetwork() {
        stopMonitoring()
        cellularNetwork = null
    }
    
    fun currentNetwork(): Network? = cellularNetwork
}
