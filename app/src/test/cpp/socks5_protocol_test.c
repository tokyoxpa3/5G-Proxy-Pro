/*
 * socks5_protocol 的 host 端單元測試（自含斷言，無測試框架依賴）。
 *
 * 編譯與執行見 app/build.gradle 的 cProtocolTest task：
 *   cc -std=c11 -Wall -Wextra -I src/main/cpp \
 *      -o build/tmp/cproto/socks5_protocol_test \
 *      src/main/cpp/socks5_protocol.c src/test/cpp/socks5_protocol_test.c
 */
#include "socks5_protocol.h"

#include <stdio.h>
#include <string.h>

static int g_failures = 0;

#define CHECK(cond) do {                                          \
    if (!(cond)) {                                                \
        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
        g_failures++;                                             \
    }                                                             \
} while (0)

static void test_method_offered(void) {
    const unsigned char methods[] = {0x00, 0x02};
    CHECK(socks5_method_offered(methods, 2, 0x00) == 1);
    CHECK(socks5_method_offered(methods, 2, 0x02) == 1);
    CHECK(socks5_method_offered(methods, 2, 0x01) == 0); /* 未提供 */
    CHECK(socks5_method_offered(methods, 0, 0x00) == 0); /* 空方法清單 */
    CHECK(socks5_method_offered(methods, 1, 0x02) == 0); /* 只看前 nmethods 個 */
}

static void test_check_credentials(void) {
    const unsigned char user[] = {'a', 'l', 'i', 'c', 'e'};
    const unsigned char pass[] = {'s', 'e', 'c', 'r', 'e', 't'};
    CHECK(socks5_check_credentials(user, 5, pass, 6, "alice", "secret") == 1);
    CHECK(socks5_check_credentials(user, 5, pass, 6, "alice", "wrong") == 0);
    CHECK(socks5_check_credentials(user, 5, pass, 6, "bob", "secret") == 0);
    /* 長度不符：內容前綴相同也須拒絕 */
    CHECK(socks5_check_credentials(user, 4, pass, 6, "alice", "secret") == 0);
    CHECK(socks5_check_credentials(user, 5, pass, 5, "alice", "secret") == 0);
    /* 空密碼：僅在 expected 為空時相符（do_auth_check 允許 plen==0） */
    CHECK(socks5_check_credentials(user, 5, pass, 0, "alice", "") == 1);
    CHECK(socks5_check_credentials(user, 5, pass, 0, "alice", "secret") == 0);
}

static void test_validate_request_header(void) {
    unsigned char req[4];
    /* 合法：IPv4 / DOMAIN / IPv6 */
    req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01;
    CHECK(socks5_validate_request_header(req) == 0);
    req[3] = 0x03;
    CHECK(socks5_validate_request_header(req) == 0);
    req[3] = 0x04;
    CHECK(socks5_validate_request_header(req) == 0);
    /* VER 錯誤 */
    req[0] = 0x04; req[3] = 0x01;
    CHECK(socks5_validate_request_header(req) != 0);
    /* RSV 非零（RFC 1928 違規，commit 30ebb59 修正的來源） */
    req[0] = 0x05; req[2] = 0x01; req[3] = 0x01;
    CHECK(socks5_validate_request_header(req) != 0);
    /* ATYP 不支援 */
    req[2] = 0x00; req[3] = 0x02;
    CHECK(socks5_validate_request_header(req) != 0);
    req[3] = 0x00;
    CHECK(socks5_validate_request_header(req) != 0);
    req[3] = 0x09;
    CHECK(socks5_validate_request_header(req) != 0);
}

static void test_is_supported_cmd(void) {
    CHECK(socks5_is_supported_cmd(0x01) == 1);
    CHECK(socks5_is_supported_cmd(0x03) == 1);
    CHECK(socks5_is_supported_cmd(0x04) == 1);
    CHECK(socks5_is_supported_cmd(0x02) == 0); /* BIND 不支援 */
    CHECK(socks5_is_supported_cmd(0x00) == 0);
    CHECK(socks5_is_supported_cmd(0xFF) == 0);
}

int main(void) {
    test_method_offered();
    test_check_credentials();
    test_validate_request_header();
    test_is_supported_cmd();
    if (g_failures == 0) {
        printf("socks5_protocol_test: ALL PASS\n");
        return 0;
    }
    printf("socks5_protocol_test: %d FAILURE(S)\n", g_failures);
    return 1;
}
