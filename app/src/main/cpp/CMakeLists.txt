cmake_minimum_required(VERSION 3.10.2)
project(androidproxy)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# 包含 jni_bridge.c 和 simple-socks5.c
set(SOURCE_FILES
    jni_bridge.c
    simple-socks5.c
)

add_library(androidproxy SHARED
    ${SOURCE_FILES}
)

find_library(log-lib log)

target_link_libraries(androidproxy
    ${log-lib}
    atomic
    m
)