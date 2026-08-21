cmake_minimum_required(VERSION 3.10.2)
project(androidproxy)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# 設定編譯標誌
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -latomic")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--build-id=none")

# 修正：移除重複的 native-lib.cpp
# 只編譯 jni_bridge.c (JNI 入口) 和 simple-socks5.c (代理核心)
set(SOURCE_FILES
    src/main/cpp/jni_bridge.c
    src/main/cpp/simple-socks5.c
)

add_library(androidproxy SHARED
    ${SOURCE_FILES}
)

find_library(log-lib log)

# 連結必要的庫
target_link_libraries(androidproxy
    ${log-lib}
    atomic
    m
)