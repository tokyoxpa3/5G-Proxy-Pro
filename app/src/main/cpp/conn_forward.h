#ifndef CONN_FORWARD_H
#define CONN_FORWARD_H

#include <stdint.h>
#include <time.h>

/*
 * 連線資料轉發（data-forwarding）狀態機的純決策邏輯（零 socket / epoll / JNI 依賴）。
 *
 * 從 simple-socks5.c 的 worker 事件迴圈抽離，鎖住兩處最易回歸的判斷：
 *  1. 事件興趣遮罩（level-triggered epoll 何時該 arm / 何時該 disarm）——
 *     判斷錯誤會造成熱迴圈（對端已半關閉仍持續 arm EPOLLRDHUP/EPOLLIN）或漏收事件。
 *  2. 半關閉（FIN）grace 期——決定了 CLOSE_WAIT 連線何時被回收，判斷錯誤會讓
 *     fd 堆積（等不到 keep-alive target 的 FIN）或過早切斷最後一批回應。
 *
 * 抽象事件位元刻意不 include epoll 標頭，使本模組能在 host（MinGW / Linux gcc）
 * 直接單元測試；simple-socks5.c 端以 map_fwd_to_epoll 做機械映射。
 */

/* 抽象事件興趣位元：對應 epoll 的 EPOLLIN / EPOLLOUT / EPOLLRDHUP 語意。
 * 低 8 位元僅用 3 個位元，與 conn_state.h 的 CONN_EV_TARGET_FLAG（bit31）錯開。 */
#define CONN_FWD_RDHUP (1u << 0)
#define CONN_FWD_IN    (1u << 1)
#define CONN_FWD_OUT   (1u << 2)

/* 半關閉 grace 期（秒）：對端送 FIN 後再保留此秒數，讓緩衝中的最後一批
 * 資料排空。HTTP keep-alive 的 target 不會發 FIN，若無限期等待會堆積
 * 數百條 CLOSE_WAIT 耗盡 fd。 */
#define CONN_FWD_GRACE_SEC 2

/* 計算 client / target 兩個 fd 各自應 arm 的事件興趣遮罩。
 * 這是 update_conn_events 的決策核心，直接決定 level-triggered epoll 是否
 * 進入熱迴圈（對端已半關閉時仍持續 arm RDHUP/IN 會反覆觸發）。
 * 純函式、只讀不寫；輸出為 CONN_FWD_* 位元組合。
 *   closed       連線已進入關閉流程 → 輸出全 0（不 arm 任何事件）
 *   client_eof   客戶端已 FIN（半關閉，停止再讀 client → client 不需 IN）
 *   target_eof   目標端已 FIN（半關閉，停止再讀 target → target 不需 RDHUP/IN）
 *   c2t_pending  緩衝尚有 client→target 資料待送出（非 0）
 *   t2c_pending  緩衝尚有 target→client 資料待送出（非 0）
 * 輸出 *client_ev / *target_ev（可為 NULL 表示該側不需要）。 */
void conn_forward_interest(int closed, int client_eof, int target_eof,
                           int c2t_pending, int t2c_pending,
                           uint32_t *client_ev, uint32_t *target_ev);

/* 半關閉 grace 期是否屆滿：eof_since==0（尚未 FIN）回 0；
 * 否則 now - eof_since >= grace 回 1。client 與 target 兩條路徑共用此函式，
 * 確保 5 秒掃描與事件迴圈內的回收判定永遠一致、不會各自漂移。 */
int conn_forward_grace_expired(time_t now, time_t eof_since, time_t grace);

/* ================= 出口停滯（stall）判定 =================
 *
 * 為什麼需要：電信端在核心網側換出口（NAT 重綁、PGW/UPF 錨點遷移）時，
 * Android framework 不會給任何回呼，舊 TCP 連線也收不到 FIN/RST。封包被
 * 中途的 NAT 丟棄，kernel 持續重傳並指數退避，使用者看到的就是「沒斷線但
 * 極慢」，要等 IDLE_TIMEOUT_SEC(300s) 才回收。
 *
 * 為什麼不能只看「N 秒無流量」：HTTP keep-alive、SSE、長輪詢、遊戲長連線
 * 本來就可能長時間沒有資料，用無流量判定會把它們整批誤殺。
 *
 * 判定的三個條件（缺一不可）：
 *   1. 這條連線曾經有過實際轉發（last_progress != 0）
 *   2. 上一個 5 秒窗口的流量 >= min_bytes（剛才還在做高速傳輸）
 *   3. 距離最後一次實際轉發 >= stall_sec（之後完全沒有進展）
 *
 * 條件 2 是防誤殺的核心：門檻設在 1MB/5s（≈200KB/s）之後，遊戲、SSE、
 * keep-alive、語音這類低速連線永遠達不到，只有「剛才還在做高速下載、
 * 突然完全停」才會被判定。因此可以安全地對單條連線動手，不需要連坐
 * 其他健康連線。
 *
 * 參數刻意保守（30 秒 + 1MB）：誤殺一條正在連線中的遊戲，比讓一條下載
 * 多卡 30 秒嚴重得多。代價是低速下載（<200KB/s）停滯時仍會退回 300 秒
 * 的 IDLE_TIMEOUT —— 這是刻意接受的取捨。 */
#define CONN_STALL_SEC 30
#define CONN_STALL_MIN_BYTES (1024ULL * 1024ULL)

/* 回 1 表示出口已停滯、應回收該連線。純函式，無 socket / epoll 依賴。 */
int conn_stall_expired(time_t now, time_t last_progress,
                       uint64_t prev_window_bytes, int stall_sec,
                       uint64_t min_bytes);

#endif /* CONN_FORWARD_H */
