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

/* ============ UDP datagram 表頭解析 / 封裝 / 位址正規化 ============
 * 這是先前在三、四處重複貼上的協定邏輯（standard UDP ASSOCIATE 與
 * UDP-in-TCP 的入向/出向），現在收斂至 socks5_udp_parse / socks5_udp_encode /
 * socks5_addr_normalize 三個純函式。以下鎖住其邊界行為，防止協定漂移。 */

static void test_udp_parse(void) {
    unsigned char atyp; const unsigned char *addr; unsigned char port[2];

    /* 合法 IPv4：RSV(2)=0 FRAG=0 ATYP=0x01 addr(4) port(2) data("hi") */
    unsigned char v4[12] = {0,0,0, 0x01, 1,2,3,4, 0,53, 'h','i'};
    CHECK(socks5_udp_parse(v4, 12, &atyp, &addr, port) == 10);
    CHECK(atyp == 0x01);
    CHECK(addr[0] == 1 && addr[1] == 2 && addr[2] == 3 && addr[3] == 4);
    CHECK(port[0] == 0 && port[1] == 53); /* port 53 = 0x0035 網路序 */

    /* 合法 IPv6 */
    unsigned char v6[24] = {0,0,0, 0x04};
    for (int i = 0; i < 16; i++) v6[4+i] = (unsigned char)(i+1);
    v6[20] = 0x01; v6[21] = 0xBB; /* port 443 */
    v6[22] = 'x'; v6[23] = 'y';
    CHECK(socks5_udp_parse(v6, 24, &atyp, &addr, port) == 22);
    CHECK(atyp == 0x04);
    CHECK(addr[0] == 1 && addr[15] == 16);
    CHECK(port[0] == 0x01 && port[1] == 0xBB);

    /* 長度不足以容納表頭：IPv4 表頭需 10 bytes */
    CHECK(socks5_udp_parse(v4, 9, &atyp, &addr, port) == -1);
    CHECK(socks5_udp_parse(v4, 4, &atyp, &addr, port) == -1);

    /* RSV 非零：RFC 1928 違規（先前 UDP 路徑漏查，現收斂與 TCP 一致） */
    unsigned char rsv_bad[12] = {0,1,0, 0x01, 1,2,3,4, 0,53, 'h','i'};
    CHECK(socks5_udp_parse(rsv_bad, 12, &atyp, &addr, port) == -1);

    /* FRAG 非零 */
    unsigned char frag_bad[12] = {0,0,1, 0x01, 1,2,3,4, 0,53, 'h','i'};
    CHECK(socks5_udp_parse(frag_bad, 12, &atyp, &addr, port) == -1);

    /* ATYP=DOMAIN：UDP datagram 依 RFC 不允許，須拒絕 */
    unsigned char dom[12] = {0,0,0, 0x03, 3,'a','b','c', 0,53, 0};
    CHECK(socks5_udp_parse(dom, 12, &atyp, &addr, port) == -1);

    /* ATYP 不支援（0x00 / 0xFF） */
    unsigned char bad_atyp[12] = {0,0,0, 0x00, 1,2,3,4, 0,53, 'h','i'};
    CHECK(socks5_udp_parse(bad_atyp, 12, &atyp, &addr, port) == -1);
    bad_atyp[3] = 0xFF;
    CHECK(socks5_udp_parse(bad_atyp, 12, &atyp, &addr, port) == -1);

    /* 總長 < 4：連 ATYP 都讀不到 */
    unsigned char tiny[3] = {0,0,0};
    CHECK(socks5_udp_parse(tiny, 3, &atyp, &addr, port) == -1);
}

static void test_udp_encode(void) {
    unsigned char out[22];

    /* IPv4：回傳 10，RSV/FRAG=0，ATYP=0x01，addr+port 正確 */
    unsigned char a4[4] = {10,0,0,1};
    unsigned char p443[2] = {0x01, 0xBB}; /* 443 網路序 */
    int n = socks5_udp_encode(out, 0, a4, p443);
    CHECK(n == 10);
    CHECK(out[0] == 0 && out[1] == 0 && out[2] == 0); /* RSV + FRAG */
    CHECK(out[3] == 0x01);
    CHECK(out[4] == 10 && out[7] == 1);
    CHECK(out[8] == 0x01 && out[9] == 0xBB);

    /* IPv6：回傳 22，ATYP=0x04 */
    unsigned char a6[16]; for (int i = 0; i < 16; i++) a6[i] = (unsigned char)(i+1);
    n = socks5_udp_encode(out, 1, a6, p443);
    CHECK(n == 22);
    CHECK(out[3] == 0x04);
    CHECK(out[4] == 1 && out[19] == 16);
    CHECK(out[20] == 0x01 && out[21] == 0xBB);
}

static void test_addr_normalize(void) {
    unsigned char na[16], nb[16];

    /* v4 正規化為 ::ffff:a.b.c.d */
    unsigned char v4[4] = {1,2,3,4};
    socks5_addr_normalize(v4, 0, na);
    unsigned char expect[16] = {0};
    expect[10] = 0xff; expect[11] = 0xff;
    expect[12] = 1; expect[13] = 2; expect[14] = 3; expect[15] = 4;
    CHECK(memcmp(na, expect, 16) == 0);

    /* v6 原樣複製 */
    unsigned char v6[16]; for (int i = 0; i < 16; i++) v6[i] = (unsigned char)(i);
    socks5_addr_normalize(v6, 1, nb);
    CHECK(memcmp(nb, v6, 16) == 0);

    /* 關鍵：v4 與其 v4-mapped v6 表示正規化後相等（UDP relay 來源驗證的核心） */
    socks5_addr_normalize(v4, 0, na);
    socks5_addr_normalize(expect, 1, nb); /* expect 即 ::ffff:1.2.3.4 */
    CHECK(memcmp(na, nb, 16) == 0);
}

/* ============ SOCKS5 回覆封裝 / frame 長度 / request 位址長度 ============ */

static void test_encode_reply(void) {
    unsigned char out[22];

    /* 失敗回覆 REP=0x04，IPv4 0.0.0.0:0 */
    unsigned char a4[4] = {0,0,0,0};
    unsigned char z2[2] = {0,0};
    int n = socks5_encode_reply(out, 0x04, 0, a4, z2);
    CHECK(n == 10);
    CHECK(out[0] == 0x05 && out[1] == 0x04 && out[2] == 0x00);
    CHECK(out[3] == 0x01);
    CHECK(out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] == 0);
    CHECK(out[8] == 0 && out[9] == 0);

    /* 成功回覆 REP=0x00，IPv4，非零位址/埠 */
    unsigned char a4b[4] = {192,168,1,5};
    unsigned char p4[2] = {0x04, 0x38}; /* 1080 */
    n = socks5_encode_reply(out, 0x00, 0, a4b, p4);
    CHECK(n == 10);
    CHECK(out[1] == 0x00);
    CHECK(out[4] == 192 && out[5] == 168 && out[6] == 1 && out[7] == 5);
    CHECK(out[8] == 0x04 && out[9] == 0x38);

    /* 成功回覆 REP=0x00，IPv6 */
    unsigned char a6[16]; for (int i = 0; i < 16; i++) a6[i] = (unsigned char)(i+1);
    n = socks5_encode_reply(out, 0x00, 1, a6, p4);
    CHECK(n == 22);
    CHECK(out[0] == 0x05 && out[1] == 0x00 && out[2] == 0x00);
    CHECK(out[3] == 0x04);
    CHECK(out[4] == 1 && out[19] == 16);
    CHECK(out[20] == 0x04 && out[21] == 0x38);
}

static void test_udp_tcp_frame_len(void) {
    unsigned char h[2];

    /* 合法：4..max_len 之間 */
    h[0] = 0x00; h[1] = 0x04;
    CHECK(socks5_udp_tcp_frame_len(h, 65536) == 4);
    h[0] = 0x00; h[1] = 0x0A;
    CHECK(socks5_udp_tcp_frame_len(h, 65536) == 10);
    h[0] = 0xFF; h[1] = 0xFF; /* 0xFFFF = 65535 */
    CHECK(socks5_udp_tcp_frame_len(h, 65536) == 65535);

    /* 太小：< 4（裝不下 RSV(2)+FRAG(1)+ATYP(1)） */
    h[0] = 0x00; h[1] = 0x00;
    CHECK(socks5_udp_tcp_frame_len(h, 65536) == -1);
    h[0] = 0x00; h[1] = 0x03;
    CHECK(socks5_udp_tcp_frame_len(h, 65536) == -1);

    /* 超過 max_len：邊界（== 合法、> 違規） */
    h[0] = 0x00; h[1] = 0x04;
    CHECK(socks5_udp_tcp_frame_len(h, 4) == 4);
    h[0] = 0x00; h[1] = 0x05;
    CHECK(socks5_udp_tcp_frame_len(h, 4) == -1);
    h[0] = 0xFF; h[1] = 0xFF;
    CHECK(socks5_udp_tcp_frame_len(h, 65535) == 65535);
    CHECK(socks5_udp_tcp_frame_len(h, 65534) == -1);
}

static void test_request_addr_len(void) {
    CHECK(socks5_request_addr_len(0x01, 0) == 4);
    CHECK(socks5_request_addr_len(0x04, 0) == 16);
    CHECK(socks5_request_addr_len(0x03, 1) == 1);
    CHECK(socks5_request_addr_len(0x03, 255) == 255);
    CHECK(socks5_request_addr_len(0x03, 0) == -1); /* domain 長度 0 違規 */
    CHECK(socks5_request_addr_len(0x00, 0) == -1);
    CHECK(socks5_request_addr_len(0x02, 0) == -1);
    CHECK(socks5_request_addr_len(0xFF, 0) == -1);
}

int main(void) {
    test_method_offered();
    test_check_credentials();
    test_validate_request_header();
    test_is_supported_cmd();
    test_udp_parse();
    test_udp_encode();
    test_addr_normalize();
    test_encode_reply();
    test_udp_tcp_frame_len();
    test_request_addr_len();
    if (g_failures == 0) {
        printf("socks5_protocol_test: ALL PASS\n");
        return 0;
    }
    printf("socks5_protocol_test: %d FAILURE(S)\n", g_failures);
    return 1;
}
