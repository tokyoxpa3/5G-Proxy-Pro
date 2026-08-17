# NativeEngine 的 native 方法由 JNI_OnLoad / RegisterNatives 註冊（jni_bridge.c），
# R8 無法追蹤其引用，必須保留整個類別避免方法被移除或混淆。
-keep class com.tokyoxpa3.androidproxy.NativeEngine { *; }

# JNI 橋接依賴的欄位（socketProvider / onSocketClosed）透過回呼使用，一併保留
-keepclassmembers class com.tokyoxpa3.androidproxy.NativeEngine {
    *;
}