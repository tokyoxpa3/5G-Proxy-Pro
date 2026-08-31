#ifndef SOCKS5_PROTOCOL_H
#define SOCKS5_PROTOCOL_H

#include <stddef.h> /* size_t */

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

/* ============ UDP relay datagram（SOCKS5 UDP / UDP-in-TCP）============
 * 標準 UDP ASSOCIATE 與 UDP-in-TCP 的 datagram 皆為
 *   [RSV(2)=0][FRAG(1)=0][ATYP][DST.ADDR][DST.PORT][DATA]
 * 下列函式只做位元組層級的解析/封裝/位址正規化，零 socket 依賴，
 * 可於 host（Linux gcc / MinGW）單元測試，防範「三、四處重複貼上、
 * 改一處漏一處」的協定漂移。 */

/* ATYP 位址型別常數（與 simple-socks5.c 內聯用的魔數一致）。 */
#define SOCKS5_ATYP_IPV4   0x01
#define SOCKS5_ATYP_DOMAIN 0x03
#define SOCKS5_ATYP_IPV6   0x04

/* 解析 UDP datagram 表頭。dgram 為完整 datagram（含 DATA 尾），len 為其總長。
 * 驗證 RSV=0、FRAG=0、ATYP∈{IPv4,IPv6}，且長度足以容納表頭（不含 DATA）。
 * 成功回傳表頭長度（payload 起始偏移，>0）；違規回傳 -1。
 * 輸出 *atyp（0x01/0x04）、*addr（指向 dgram 內的位址位元組，IPv4=4B/IPv6=16B）、
 * port[2]（原始 2 位元組網路序，直接 memcpy 進 sin_port 即可）。
 * ATYP=DOMAIN 依 RFC 1928 不允許於 UDP datagram，回 -1。 */
int socks5_udp_parse(const unsigned char *dgram, size_t len,
                     unsigned char *atyp, const unsigned char **addr, unsigned char port[2]);

/* 封裝 UDP datagram 表頭：寫 RSV(2)=0、FRAG(1)=0、ATYP、位址、port 至 out，
 * 回傳表頭長度（IPv4=10、IPv6=22）。is_v6=0 時 addr 為 4B，否則 16B。
 * out 至少需 22 bytes（呼叫端自行保證）；port[2] 為原始 2 位元組網路序。 */
int socks5_udp_encode(unsigned char *out, int is_v6, const unsigned char *addr, const unsigned char port[2]);

/* 位址正規化：把 IPv4（4B）或 IPv6（16B）位址轉成 16B 的規範表示。
 * v4 以 v4-mapped（::ffff:a.b.c.d）形式表示，使「v4 與 v4-mapped v6」可直接
 * memcmp 相等（UDP relay 來源驗證的關鍵：雙棧 socket 收到的 IPv4 來源會以
 * v4-mapped 形式呈現）。is_v6=0 時 src 為 4B，否則 16B。 */
void socks5_addr_normalize(const unsigned char *src, int is_v6, unsigned char *out16);

/* SOCKS5 回覆封裝：寫 [VER=0x05][REP][RSV=0x00][ATYP][ADDR][PORT] 至 out，
 * 回傳表頭長度（IPv4=10、IPv6=22）。rep 為回覆碼（0x00 成功 / 0x04 等失敗）。
 * is_v6=0 時 addr 為 4B，否則 16B；port[2] 為 2 位元組網路序。 */
int socks5_encode_reply(unsigned char *out, unsigned char rep, int is_v6,
                        const unsigned char *addr, const unsigned char port[2]);

/* UDP-in-TCP frame 長度欄解析與邊界驗證：len_field 為 2 位元組網路序長度。
 * 4 <= dlen <= max_len 回傳 dlen，否則回傳 -1（<4 裝不下 RSV(2)+FRAG(1)+ATYP(1)
 * 的合法表頭；>max_len 會爆緩衝）。 */
int socks5_udp_tcp_frame_len(const unsigned char len_field[2], int max_len);

/* SOCKS5 request 位址欄位長度：atyp=0x01 → 4、0x04 → 16、0x03 → first_byte
 * （domain 長度，為 0 回傳 -1）。atyp 不合法回傳 -1。供握手讀取 addr 用。 */
int socks5_request_addr_len(unsigned char atyp, unsigned char first_byte);

#ifdef __cplusplus
}
#endif

#endif /* SOCKS5_PROTOCOL_H */
