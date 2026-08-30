#ifndef SOCKS5_PROTOCOL_H
#define SOCKS5_PROTOCOL_H

/*
 * SOCKS5 協定解析的純函式（零 Android / JNI / socket 依賴）。
 *
 * 從 simple-socks5.c 抽離，目的是讓協定層的驗證邏輯（方法協商、RFC 1929 帳密、
 * request 表頭 / RSV / ATYP 合法性、指令支援與否）能被 host 端單元測試鎖住，
 * 避免像「漏查 RSV」（commit 30ebb59）這類協定回歸再次發生。
 */

#ifdef __cplusplus
extern "C" {
#endif

/* 判斷 desired_method 是否出現在 methods[0..nmethods-1]，是回傳 1、否回傳 0。 */
int socks5_method_offered(const unsigned char *methods, int nmethods, int desired_method);

/* RFC 1929 帳密比對：user/pass 為客戶端送來的位元組與長度，
 * expected_* 為服務端設定（NUL 結尾字串）。完全相符回傳 1、否則回傳 0。 */
int socks5_check_credentials(const unsigned char *user, unsigned char ulen,
                             const unsigned char *pass, unsigned char plen,
                             const char *expected_user, const char *expected_pass);

/* 驗證 SOCKS5 request 表頭：VER=0x05、RSV=0x00、ATYP∈{0x01,0x03,0x04}。
 * req 至少 4 bytes（[VER,CMD,RSV,ATYP]）。合法回傳 0、違規回傳 -1。 */
int socks5_validate_request_header(const unsigned char *req);

/* cmd 是否為本伺服器支援的指令：0x01 CONNECT(TCP)、0x03 UDP ASSOCIATE、
 * 0x04 UDP-in-TCP(自訂擴充)。是回傳 1、否回傳 0。 */
int socks5_is_supported_cmd(int cmd);

#ifdef __cplusplus
}
#endif

#endif /* SOCKS5_PROTOCOL_H */
