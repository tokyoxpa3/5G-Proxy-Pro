package com.tokyoxpa3.androidproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * Relay → 5G Proxy Pro 的單一用途窄介面：通知「換 IP 已完成，請清掉舊出口連線」。
 *
 * ## 為什麼需要它
 *
 * Pro 自己會在 `ConnectivityManager.NetworkCallback.onAvailable()` 收到新 Network 時
 * 清理既有連線（見 [Socks5ProxyService.resetStaleConnections]）。但實測有些 ROM 在
 * 短飛航後會**復用同一個 Network 物件、只換 PDN 的 IP**：`onAvailable` 不會再觸發，
 * 自動清理就沒有觸發點。Relay 是唯一知道「我剛剛做了一輪輪替」的人，由它補這一刀。
 *
 * ## 為什麼是 ContentProvider 而不是 broadcast
 *
 * - signature permission 有安裝順序陷阱（Relay 先裝時拿不到後宣告的權限）；
 * - `ContentProvider.call()` 可以用 `Binder.getCallingUid()` 可靠地驗證呼叫者是誰，
 *   與同專案的 OmniADB-Host `RelayBridgeProvider` 同一套做法。
 *
 * 只開放一個固定 method，不提供任何 arbitrary command，也不讀寫資料。
 */
class RotationBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_IP_ROTATED) return null
        if (!isRelayCaller()) {
            Log.w(TAG, "rejected ip_rotation notice from uid=${Binder.getCallingUid()}")
            return null
        }
        val reset = Socks5ProxyService.instance?.resetStaleConnections("relay_notice") ?: 0
        Log.i(TAG, "ip rotation notice accepted; reset=$reset TCP egress socket(s)")
        return Bundle().apply { putInt(KEY_RESET, reset) }
    }

    /** 呼叫端 UID 必須真的屬於 Tailnet Relay；同 UID 共用（sharedUserId）也算通過。 */
    private fun isRelayCaller(): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
                ?.contains(RELAY_PACKAGE) == true
        } catch (e: Exception) {
            false
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "RotationBridge"
        const val AUTHORITY = "com.tokyoxpa3.androidproxy.rotation"
        const val METHOD_IP_ROTATED = "ip_rotation_completed"
        const val KEY_RESET = "reset_connections"
        private const val RELAY_PACKAGE = "com.tokyoxpa3.tailnetrelay"
    }
}
