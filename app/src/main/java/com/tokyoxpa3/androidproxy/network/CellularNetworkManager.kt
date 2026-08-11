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
    private var cellularNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    suspend fun requestCellularNetwork(timeoutMs: Long = 10000L): Network? = try {
        withTimeoutOrNull(timeoutMs) {
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
                
                networkCallback = object : ConnectivityManager.NetworkCallback() {
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
                    connectivityManager.requestNetwork(networkRequest, networkCallback!!)
                } catch (e: Exception) {
                    android.util.Log.e("CellularNetwork", "Failed to request network", e)
                    continuation.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }
                
                continuation.invokeOnCancellation {
                    try {
                        networkCallback?.let { 
                            connectivityManager.unregisterNetworkCallback(it) 
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CellularNetwork", "Error unregistering network callback", e)
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("CellularNetwork", "Exception in requestCellularNetwork", e)
        null
    }
    
    fun releaseCellularNetwork() {
        try {
            networkCallback?.let { callback ->
                connectivityManager.unregisterNetworkCallback(callback)
                networkCallback = null
            }
            cellularNetwork = null
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Error releasing cellular network", e)
        }
    }
    
    fun hasCellularNetwork(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            hasCellular && hasInternet
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Error checking cellular network", e)
            false
        }
    }
    
    fun getCurrentNetworkInfo(): String {
        return try {
            val targetNetwork = cellularNetwork ?: connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(targetNetwork)
            buildString {
                append("Active Network (Target): ${targetNetwork?.hashCode() ?: "None"}\n")
                append("Is Using Locked Cellular: ${cellularNetwork != null}\n")
                append("Has Cellular: ${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true}\n")
                append("Has WiFi: ${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}\n")
                append("Has Internet: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}\n")
                append("Downstream Bandwidth (Est): ${capabilities?.linkDownstreamBandwidthKbps ?: 0} kbps\n")
                append("Upstream Bandwidth (Est): ${capabilities?.linkUpstreamBandwidthKbps ?: 0} kbps\n")
            }
        } catch (e: Exception) {
            android.util.Log.e("CellularNetwork", "Error getting network info", e)
            "Error: ${e.message}"
        }
    }
}