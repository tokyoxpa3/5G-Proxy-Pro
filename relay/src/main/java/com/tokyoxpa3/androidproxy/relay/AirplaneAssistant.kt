package com.tokyoxpa3.androidproxy.relay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log

/**
 * [IP 輪替] 以「預設數位助理」身分切換飛航模式。
 *
 * Android 4.2 起一般 App 無法寫入 AIRPLANE_MODE_ON；唯一不需 root 的公開途徑是
 * Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE —— 它只接受目前作用中的
 * VoiceInteractionService 透過 session.startVoiceActivity() 發出。
 * 使用者需在「設定 → 預設應用程式 → 數位助理應用程式」選擇本 App 一次。
 */
class AirplaneAssistant : VoiceInteractionService() {

    companion object {
        private const val TAG = "AirplaneAssistant"
        const val ARG_AIRPLANE = "airplane"

        // 系統綁定本服務（即本 App 為作用中的助理）時才非 null
        @Volatile private var active: AirplaneAssistant? = null

        val isActive: Boolean get() = active != null

        /** 送出切換請求；回 false 代表本 App 不是作用中的助理。 */
        fun requestAirplane(enabled: Boolean): Boolean {
            val svc = active ?: return false
            Handler(Looper.getMainLooper()).post {
                try {
                    svc.showSession(Bundle().apply { putBoolean(ARG_AIRPLANE, enabled) }, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "showSession failed", e)
                }
            }
            return true
        }

        fun isAirplaneOn(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    override fun onReady() {
        super.onReady()
        active = this
        Log.i(TAG, "assistant ready")
        // 系統在開機、App 被殺或更新後都會重新綁定作用中的助理 —— 借此把使用者
        // 先前開著的中繼自動拉回來，不必每次手動按 Start
        val enabled = getSharedPreferences("relay", Context.MODE_PRIVATE).getBoolean("relay_enabled", false)
        if (enabled && !RelayService.isRunning) {
            try {
                startForegroundService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_START))
                Log.i(TAG, "relay auto-started")
            } catch (e: Exception) {
                Log.w(TAG, "relay auto-start failed", e)
            }
        }
    }

    override fun onShutdown() {
        active = null
        super.onShutdown()
    }
}

class AirplaneSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AirplaneSession(this)
}

class AirplaneSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        // 設定頁通常數百毫秒內完成；逾時仍強制關閉，避免透明的 session 視窗殘留
        private const val HIDE_FALLBACK_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 使用者長按 Home 叫出助理時不帶參數：什麼都不做，直接關掉
        if (args == null || !args.containsKey(AirplaneAssistant.ARG_AIRPLANE)) {
            hide()
            return
        }
        val enabled = args.getBoolean(AirplaneAssistant.ARG_AIRPLANE)
        try {
            startVoiceActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
                    .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, enabled)
            )
        } catch (e: Exception) {
            Log.w("AirplaneSession", "startVoiceActivity failed", e)
            hide()
            return
        }
        // 不可在這裡立刻 hide()：設定頁 onCreate 會檢查 isVoiceInteractionRoot()，
        // session 已關閉時它只會靜默 finish、不切換（實機 SM-G950F 驗證）。
        // 等設定頁的 task 結束（onTaskFinished）再關。
        handler.postDelayed(hideRunnable, HIDE_FALLBACK_MS)
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        handler.removeCallbacks(hideRunnable)
        hide()
    }

    // 設定頁完成後會送 CompleteVoiceRequest；回覆它讓設定頁正常結束
    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        try { request.sendCompleteResult(null) } catch (e: Exception) {}
    }

    override fun onHide() {
        handler.removeCallbacks(hideRunnable)
        super.onHide()
    }
}

/** 系統要求助理 App 必須宣告辨識服務才會列入可選清單；本 App 不做語音辨識。 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) {}
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
