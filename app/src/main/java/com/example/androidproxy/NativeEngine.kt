package com.example.androidproxy

import android.util.Log

object NativeEngine {
    private const val TAG = "NativeEngine"
    private var libraryLoaded = false
    private var initialized = false
    
    // 唯一的 Socket Provider 定義：(Host, Port, IsUdp) -> FD
    var socketProvider: ((String, Int, Boolean) -> Int)? = null
    
    // 由 C++ 在 close(fd) 前調用，釋放 Java 端的強引用
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
    
    // C++ 會調用此方法 (對應 jni_bridge.c 中的 GetMethodID)
    fun createSocketFromNative(host: String, port: Int, isUdp: Boolean): Int {
        return socketProvider?.invoke(host, port, isUdp) ?: -1
    }

    fun notifySocketClosed(fd: Int) {
        onSocketClosed?.invoke(fd)
    }
    
    // JNI 方法定義
    external fun startSocks5Server(port: Int): String
    external fun stopSocks5Server(): String
    external fun setSocks5Auth(user: String, pass: String): String
    external fun testNative5G(fd: Int): String
}
