/* udp_conn 純函式單元測試：鎖住 UDP epoll worker 的世代驗證、fd 角色解碼與逾時語意。 */
#include "udp_conn.h"

#include <stdio.h>

static int g_failures = 0;

#define CHECK(cond) do { \
    if (!(cond)) { \
        printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
        g_failures++; \
    } \
} while (0)

static void test_bad_slot(void) {
    CHECK(udp_event_bad_slot(0, 1088) == 0);
    CHECK(udp_event_bad_slot(1087, 1088) == 0);
    CHECK(udp_event_bad_slot(-1, 1088) == 1);
    CHECK(udp_event_bad_slot(1088, 1088) == 1);
    CHECK(udp_event_bad_slot(5000, 1088) == 1);
}

static void test_decode(void) {
    uint32_t sidx, egen;
    udp_fd_role_t role;

    /* Client TCP 角色 (無旗標) */
    udp_event_decode(((uint64_t)7u << 32) | 123u, &sidx, &egen, &role);
    CHECK(sidx == 123 && egen == 7 && role == UDP_ROLE_CLIENT);

    /* Local UDP 角色 (帶 LOCAL 旗標) */
    udp_event_decode(((uint64_t)7u << 32) | 123u | UDP_EV_ROLE_LOCAL_FLAG, &sidx, &egen, &role);
    CHECK(sidx == 123 && egen == 7 && role == UDP_ROLE_LOCAL);

    /* Remote 5G UDP 角色 (帶 REMOTE 旗標) */
    udp_event_decode(((uint64_t)7u << 32) | 123u | UDP_EV_ROLE_REMOTE_FLAG, &sidx, &egen, &role);
    CHECK(sidx == 123 && egen == 7 && role == UDP_ROLE_REMOTE);

    /* 邊界數值：大 slot、大 gen */
    udp_event_decode(((uint64_t)0x12345678u << 32) | 1087u | UDP_EV_ROLE_REMOTE_FLAG, &sidx, &egen, &role);
    CHECK(sidx == 1087 && egen == 0x12345678u && role == UDP_ROLE_REMOTE);
}

static void test_check_slot(void) {
    /* 正常有效事件 -> PROCESS */
    udp_event_disp_t d = udp_event_check_slot(10, 10, UDP_CONN_MAGIC, 1, 0, 1);
    CHECK(d == UDP_EV_PROCESS);

    /* 槽位未啟用 -> INACTIVE */
    d = udp_event_check_slot(10, 10, UDP_CONN_MAGIC, 0, 0, 1);
    CHECK(d == UDP_EV_INACTIVE);

    /* 世代不符 -> STALE */
    d = udp_event_check_slot(9, 10, UDP_CONN_MAGIC, 1, 0, 1);
    CHECK(d == UDP_EV_STALE);

    /* Magic 毒化 -> STALE */
    d = udp_event_check_slot(10, 10, 0, 1, 0, 1);
    CHECK(d == UDP_EV_STALE);

    /* Closed -> NOT_READY */
    d = udp_event_check_slot(10, 10, UDP_CONN_MAGIC, 1, 1, 1);
    CHECK(d == UDP_EV_NOT_READY);

    /* 未完成註冊 -> NOT_READY */
    d = udp_event_check_slot(10, 10, UDP_CONN_MAGIC, 1, 0, 0);
    CHECK(d == UDP_EV_NOT_READY);
}

static void test_idle_expired(void) {
    CHECK(udp_conn_idle_expired(100, 0, 60) == 0);
    CHECK(udp_conn_idle_expired(100, 50, 60) == 0); /* 50s idle <= 60s */
    CHECK(udp_conn_idle_expired(100, 40, 60) == 0); /* 60s idle <= 60s */
    CHECK(udp_conn_idle_expired(100, 39, 60) == 1); /* 61s idle > 60s */
}

int main(void) {
    test_bad_slot();
    test_decode();
    test_check_slot();
    test_idle_expired();
    if (g_failures == 0) {
        printf("udp_conn_test: ALL PASS\n");
        return 0;
    }
    printf("udp_conn_test: %d FAILED\n", g_failures);
    return 1;
}
