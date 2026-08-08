#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

extern JavaVM *g_jvm;

// 修正後的簽名：增加 is_udp
int request_java_5g_socket(const char *host, int port, int is_udp);
void release_java_socket(int fd);

#ifdef __cplusplus
}
#endif

#endif // JNI_BRIDGE_H