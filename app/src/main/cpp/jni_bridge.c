#include "jni_bridge.h"
#include <jni.h>
#include <android/log.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>

#define TAG "JNI_BRIDGE"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JavaVM *g_jvm = NULL;
jobject g_native_engine_instance = NULL;

// 快取 MethodID 避免反覆查詢 (效能關鍵)
static jmethodID g_mid_createSocket = NULL;
static jmethodID g_mid_notifyClosed = NULL;

extern int socks5_server_main_dynamic(int port);
extern void socks5_server_quit(void);
extern void socks5_server_set_auth(const char *user, const char *pass);
extern void socks5_server_set_bind_addrs(const char **addrs, int count);
extern int socks5_server_is_running(void);
extern int socks5_server_get_stats(char *out, size_t out_len);

static pthread_t g_server_thread;
static int g_server_running = 0;

typedef struct { int port; } ServerArgs;

static void *server_thread_func(void *arg) {
    ServerArgs *args = (ServerArgs *)arg;
    if (socks5_server_main_dynamic(args->port) != 0) g_server_running = 0;
    free(args);
    return NULL;
}

// 供 Worker 線程使用：永久綁定 JVM
void jni_attach_thread() {
    if (!g_jvm) return;
    JNIEnv *env;
    (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
}

void jni_detach_thread() {
    if (!g_jvm) return;
    (*g_jvm)->DetachCurrentThread(g_jvm);
}

static JNIEnv *get_jni_env(int *should_detach) {
    JNIEnv *env = NULL;
    *should_detach = 0;
    if (!g_jvm) return NULL;
    int res = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return NULL;
        *should_detach = 1;
    }
    return env;
}

int request_java_5g_socket(const char *host, int port, int is_udp) {
    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env || !g_native_engine_instance || !g_mid_createSocket) return -1;

    jstring jhost = (*env)->NewStringUTF(env, host);
    jint fd = (*env)->CallIntMethod(env, g_native_engine_instance, g_mid_createSocket, jhost, (jint)port, (jboolean)is_udp);

    // 異常防護
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        fd = -1; 
    }

    (*env)->DeleteLocalRef(env, jhost);
    if (should_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
    return (int)fd;
}

void release_java_socket(int fd) {
    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env || !g_native_engine_instance || !g_mid_notifyClosed) return;
    
    (*env)->CallVoidMethod(env, g_native_engine_instance, g_mid_notifyClosed, (jint)fd);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (should_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT void JNICALL native_register_instance(JNIEnv *env, jobject thiz) {
    (*env)->GetJavaVM(env, &g_jvm);
    if (g_native_engine_instance) (*env)->DeleteGlobalRef(env, g_native_engine_instance);
    g_native_engine_instance = (*env)->NewGlobalRef(env, thiz);
    
    // 初始化 MethodID 快取
    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_mid_createSocket = (*env)->GetMethodID(env, cls, "createSocketFromNative", "(Ljava/lang/String;IZ)I");
    g_mid_notifyClosed = (*env)->GetMethodID(env, cls, "notifySocketClosed", "(I)V");
}

JNIEXPORT jstring JNICALL native_start_socks5_server(JNIEnv *env, jobject thiz, jint port, jobjectArray jAddrs) {
    if (g_server_running) return (*env)->NewStringUTF(env, "Already running");

    // 從 Java 複製綁定位址到 C 層靜態緩衝區（socks5_server_set_bind_addrs 會自行複製內容）
    const char *tmp[32];
    jstring jsArr[32];
    int count = 0;
    if (jAddrs) {
        jsize len = (*env)->GetArrayLength(env, jAddrs);
        if (len > 32) len = 32;
        for (jsize i = 0; i < len; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, jAddrs, i);
            if (!js) continue;
            const char *c = (*env)->GetStringUTFChars(env, js, NULL);
            if (c) {
                jsArr[count] = js;
                tmp[count] = c;
                count++;
            } else {
                (*env)->DeleteLocalRef(env, js);
            }
        }
    }
    socks5_server_set_bind_addrs(tmp, count);
    for (int i = 0; i < count; i++) {
        (*env)->ReleaseStringUTFChars(env, jsArr[i], tmp[i]);
        (*env)->DeleteLocalRef(env, jsArr[i]);
    }

    ServerArgs *args = malloc(sizeof(ServerArgs));
    args->port = (int)port;
    g_server_running = 1;
    pthread_create(&g_server_thread, NULL, server_thread_func, args);
    return (*env)->NewStringUTF(env, "Started");
}

JNIEXPORT jstring JNICALL native_stop_socks5_server(JNIEnv *env, jobject thiz) {
    if (!g_server_running) return (*env)->NewStringUTF(env, "Not running");
    socks5_server_quit();
    pthread_join(g_server_thread, NULL);
    g_server_running = 0;
    return (*env)->NewStringUTF(env, "Stopped");
}

JNIEXPORT jstring JNICALL native_set_socks5_auth(JNIEnv *env, jobject thiz, jstring user, jstring pass) {
    const char *cuser = user ? (*env)->GetStringUTFChars(env, user, NULL) : NULL;
    const char *cpass = pass ? (*env)->GetStringUTFChars(env, pass, NULL) : NULL;
    socks5_server_set_auth(cuser ? cuser : "", cpass ? cpass : "");
    if (cuser) (*env)->ReleaseStringUTFChars(env, user, cuser);
    if (cpass) (*env)->ReleaseStringUTFChars(env, pass, cpass);
    return (*env)->NewStringUTF(env, "OK");
}

// [item6] 零成本健康檢查：直接讀取 C 層的 atomic 運行旗標
JNIEXPORT jboolean JNICALL native_is_socks5_server_running(JNIEnv *env, jobject thiz) {
    return (jboolean)(socks5_server_is_running() ? 1 : 0);
}

// [自檢/診斷] 讀取 native 引擎即時統計（App 內「複製診斷報告」用）
JNIEXPORT jstring JNICALL native_get_socks5_stats(JNIEnv *env, jobject thiz) {
    char buf[512];
    socks5_server_get_stats(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}

static const JNINativeMethod gMethods[] = {
    {"nativeRegisterInstance", "()V", (void *)native_register_instance},
    {"startSocks5Server", "(I[Ljava/lang/String;)Ljava/lang/String;", (void *)native_start_socks5_server},
    {"stopSocks5Server", "()Ljava/lang/String;", (void *)native_stop_socks5_server},
    {"setSocks5Auth", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void *)native_set_socks5_auth},
    {"isSocks5ServerRunning", "()Z", (void *)native_is_socks5_server_running},
    {"getSocks5Stats", "()Ljava/lang/String;", (void *)native_get_socks5_stats},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/tokyoxpa3/androidproxy/NativeEngine");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return JNI_ERR; }
    (*env)->RegisterNatives(env, cls, gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
    return JNI_VERSION_1_6;
}