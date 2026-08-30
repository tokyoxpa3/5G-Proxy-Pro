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
