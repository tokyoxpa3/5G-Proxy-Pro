// Host 測試 shim：在無 Android NDK 的 Linux C 工具鏈上，提供 <android/log.h> 的
// 最小替代。epoll 引擎（simple-socks5.c）透過 __android_log_print 記錄事件與
// 生命週期統計；host 整合測試把它重導到 stderr，使同一個原始檔能在 CI 的
// 純 Linux gcc 下編譯執行，無需任何 Android 依賴。
#ifndef HOST_SHIM_ANDROID_LOG_H
#define HOST_SHIM_ANDROID_LOG_H

#include <stdio.h>

#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_VERBOSE 2

#define __android_log_print(prio, tag, ...)             \
    do {                                                \
        fprintf(stderr, "[%s] ", (tag));                \
        fprintf(stderr, __VA_ARGS__);                   \
        fprintf(stderr, "\n");                          \
    } while (0)

#endif /* HOST_SHIM_ANDROID_LOG_H */
