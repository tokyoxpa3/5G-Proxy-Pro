package com.tokyoxpa3.androidproxy

import android.util.Log

object NativeEngine {
    private const val TAG = "NativeEngine"
    @Volatile private var libraryLoaded = false
    @Volatile private var initialized = false
    
    // 由 Java 執行緒寫入、native worker 執行緒讀取（createSocketFromNative /
    // notifySocketClosed）：必須 @Volatile 保證跨執行緒可見性，否則重建後 native 端
    // 可能仍看到舊的 provider，或呼叫到已 teardown 的 callback。
    @Volatile var socketProvider: ((String, Int, Boolean) -> Int)? = null
    @Volatile var onSocketClosed: ((Int) -> Unit)? = null

    init {
        try {
            Log.d(TAG, "Loading native library: androidproxy")
            System.loadLibrary("androidproxy")
            libraryLoaded = true
            Log.d(TAG, "✅ Native library loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load native library: ${e.message}")
            libraryLoaded = false
        }
    }
    
    fun isLibraryLoaded(): Boolean = libraryLoaded
    
    fun registerInstance() {
        if (!initialized && socketProvider != null) {
            Log.d(TAG, "Registering NativeEngine instance with C++ layer")
            nativeRegisterInstance()
            initialized = true
        }
    }
    
    private external fun nativeRegisterInstance()
    
    fun createSocketFromNative(host: String, port: Int, isUdp: Boolean): Int {
        return socketProvider?.invoke(host, port, isUdp) ?: -1
    }

    fun notifySocketClosed(fd: Int) {
        onSocketClosed?.invoke(fd)
    }
    
    external fun startSocks5Server(port: Int, bindAddrs: Array<String>): String
    external fun stopSocks5Server(): String
    external fun setSocks5Auth(user: String, pass: String): String
    external fun isSocks5ServerRunning(): Boolean
    external fun getSocks5Stats(): String
    external fun getTrafficBytes(): LongArray

    // [自檢/診斷] 安全讀取 native 統計；程式庫未載入時回傳說明字串
    fun safeGetStats(): String {
        return if (libraryLoaded) {
            try { getSocks5Stats() } catch (e: Exception) { "stats unavailable" }
        } else {
            "native library not loaded"
        }
    }

    // [流量統計] 安全讀取 tx/rx 累計位元組（[上傳, 下載]）；程式庫未載入或
    // 讀取失敗回傳 null，由 UI 顯示佔位。
    fun safeGetTrafficBytes(): LongArray? {
        return if (libraryLoaded) {
            try {
                val arr = getTrafficBytes()
                if (arr.size >= 2) arr else null
            } catch (e: Exception) { null }
        } else null
    }
}
