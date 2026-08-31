#include "socks5_protocol.h"

#include <string.h>

int socks5_method_offered(const unsigned char *methods, int nmethods, int desired_method) {
    for (int i = 0; i < nmethods; i++) {
        if (methods[i] == desired_method) return 1;
    }
    return 0;
}

int socks5_check_credentials(const unsigned char *user, unsigned char ulen,
                             const unsigned char *pass, unsigned char plen,
                             const char *expected_user, const char *expected_pass) {
    // 帳號與密碼都必須完全相符（啟用認證時兩欄皆非空，因此不允許空值放行）。
    // 註：此為 LAN 代理、非對外服務，比對未做恆時（timing-safe），與舊實作一致。
    int user_ok = (ulen == (unsigned char)strlen(expected_user)) &&
                  memcmp(user, expected_user, ulen) == 0;
    int pass_ok = (plen == (unsigned char)strlen(expected_pass)) &&
                  memcmp(pass, expected_pass, plen) == 0;
    return user_ok && pass_ok;
}

int socks5_validate_request_header(const unsigned char *req) {
    // req[0]=VER, req[1]=CMD, req[2]=RSV, req[3]=ATYP
    if (req[0] != 0x05) return -1;  // 必須是 SOCKS5
    if (req[2] != 0x00) return -1;  // RFC 1928：RSV 欄位必須為 0x00
    int atyp = req[3];
    if (atyp != 0x01 && atyp != 0x03 && atyp != 0x04) return -1;  // 不支援的地址類型
    return 0;
}

int socks5_is_supported_cmd(int cmd) {
    return cmd == 0x01 || cmd == 0x03 || cmd == 0x04;
}

int socks5_udp_parse(const unsigned char *dgram, size_t len,
                     unsigned char *atyp, const unsigned char **addr, unsigned char port[2]) {
    if (len < 4) return -1; /* 至少 RSV(2)+FRAG(1)+ATYP(1) */
    if (dgram[0] != 0 || dgram[1] != 0) return -1; /* RSV 須為 0（RFC 1928） */
    if (dgram[2] != 0) return -1;                 /* FRAG 須為 0 */
    unsigned char t = dgram[3];
    if (t == SOCKS5_ATYP_IPV4) {
        if (len < 10) return -1; /* 表頭 4+addr(4)+port(2) */
        if (atyp) *atyp = t;
        if (addr) *addr = dgram + 4;
        if (port) memcpy(port, dgram + 8, 2);
        return 10;
    }
    if (t == SOCKS5_ATYP_IPV6) {
        if (len < 22) return -1; /* 表頭 4+addr(16)+port(2) */
        if (atyp) *atyp = t;
        if (addr) *addr = dgram + 4;
        if (port) memcpy(port, dgram + 20, 2);
        return 22;
    }
    return -1; /* DOMAIN 或其他 ATYP：UDP datagram 不允許 */
}

/* 寫入 [ATYP][ADDR][PORT] 至 p（p[0]=ATYP），回傳 7（v4）或 19（v6）bytes。
 * UDP datagram 表頭與 SOCKS5 回覆共用此段（差異只在前面 3 bytes），
 * 收斂在此避免兩處各自貼上造成位址/埠偏移漂移。 */
static int encode_addr_port(unsigned char *p, int is_v6, const unsigned char *addr, const unsigned char port[2]) {
    if (!is_v6) {
        p[0] = SOCKS5_ATYP_IPV4;
        memcpy(p + 1, addr, 4);
        memcpy(p + 5, port, 2);
        return 7;
    }
    p[0] = SOCKS5_ATYP_IPV6;
    memcpy(p + 1, addr, 16);
    memcpy(p + 17, port, 2);
    return 19;
}

int socks5_udp_encode(unsigned char *out, int is_v6, const unsigned char *addr, const unsigned char port[2]) {
    out[0] = 0; out[1] = 0; out[2] = 0; /* RSV(2) + FRAG(1) */
    return 3 + encode_addr_port(out + 3, is_v6, addr, port);
}

int socks5_encode_reply(unsigned char *out, unsigned char rep, int is_v6,
                        const unsigned char *addr, const unsigned char port[2]) {
    out[0] = 0x05; /* VER */
    out[1] = rep;
    out[2] = 0x00; /* RSV */
    return 3 + encode_addr_port(out + 3, is_v6, addr, port);
}

int socks5_udp_tcp_frame_len(const unsigned char len_field[2], int max_len) {
    int dlen = (len_field[0] << 8) | len_field[1];
    if (dlen < 4 || dlen > max_len) return -1; /* 協定違規：裝不下表頭或爆緩衝 */
    return dlen;
}

int socks5_request_addr_len(unsigned char atyp, unsigned char first_byte) {
    if (atyp == SOCKS5_ATYP_IPV4) return 4;
    if (atyp == SOCKS5_ATYP_IPV6) return 16;
    if (atyp == SOCKS5_ATYP_DOMAIN) return first_byte >= 1 ? (int)first_byte : -1;
    return -1;
}

void socks5_addr_normalize(const unsigned char *src, int is_v6, unsigned char *out16) {
    memset(out16, 0, 16);
    if (!is_v6) {
        out16[10] = 0xff; out16[11] = 0xff; /* ::ffff:a.b.c.d */
        memcpy(out16 + 12, src, 4);
    } else {
        memcpy(out16, src, 16);
    }
}
