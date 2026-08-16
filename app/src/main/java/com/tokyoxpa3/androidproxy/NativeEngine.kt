package com.tokyoxpa3.androidproxy

import android.util.Log

object NativeEngine {
    private const val TAG = "NativeEngine"
    private var libraryLoaded = false
    private var initialized = false
    
    var socketProvider: ((String, Int, Boolean) -> Int)? = null
    var onSocketClosed: ((Int) -> Unit)? = null

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
    external fun testNative5G(fd: Int): String
}
