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

static pthread_t g_server_thread;
static int g_server_running = 0;

typedef struct { int port; } ServerArgs;

static void *server_thread_func(void *arg) {
    ServerArgs *args = (ServerArgs *)arg;
    socks5_server_main_dynamic(args->port);
    free(args);
    g_server_running = 0;
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

JNIEXPORT jstring JNICALL native_start_socks5_server(JNIEnv *env, jobject thiz, jint port) {
    if (g_server_running) return (*env)->NewStringUTF(env, "Already running");
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

JNIEXPORT jstring JNICALL native_test_native_5g(JNIEnv *env, jobject thiz, jint fd) {
    return (*env)->NewStringUTF(env, "OK");
}

static const JNINativeMethod gMethods[] = {
    {"nativeRegisterInstance", "()V", (void *)native_register_instance},
    {"startSocks5Server", "(I)Ljava/lang/String;", (void *)native_start_socks5_server},
    {"stopSocks5Server", "()Ljava/lang/String;", (void *)native_stop_socks5_server},
    {"setSocks5Auth", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void *)native_set_socks5_auth},
    {"testNative5G", "(I)Ljava/lang/String;", (void *)native_test_native_5g},
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