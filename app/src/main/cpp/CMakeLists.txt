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

# [item9] Release 最佳化：-O3 + 函式/資料區段分離 + 連結時清除未使用程式碼
# 以 generator expression 限定只在 Release 生效，Debug 維持可除錯的 -O0
target_compile_options(androidproxy PRIVATE
    $<$<CONFIG:Release>:-O3;-ffunction-sections;-fdata-sections>
)
target_link_options(androidproxy PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections>
)