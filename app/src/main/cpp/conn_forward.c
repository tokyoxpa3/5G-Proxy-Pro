#include "conn_forward.h"

void conn_forward_interest(int closed, int client_eof, int target_eof,
                           int c2t_pending, int t2c_pending,
                           uint32_t *client_ev, uint32_t *target_ev) {
    uint32_t c_ev = 0, t_ev = 0;

    if (!closed) {
        // client fd：未半關閉才 arm RDHUP/IN；半關閉後不再 arm RDHUP——否則
        // level-triggered EPOLLRDHUP 會持續就緒而反覆觸發（熱迴圈）。這與 target
        // 側對稱，先前只處理了 target 側、client 側是漏的。t2c 有待送資料才需 OUT。
        if (!client_eof) {
            c_ev |= CONN_FWD_RDHUP;
            if (!c2t_pending) c_ev |= CONN_FWD_IN;
        }
        if (t2c_pending) c_ev |= CONN_FWD_OUT;

        // target fd：未半關閉才 arm RDHUP/IN（半關閉後不會再有資料送達，
        // 持續 arm 只會讓 level-triggered RDHUP 反覆觸發 = 熱迴圈）；
        // c2t 緩衝有待送出資料才需要 OUT（可寫給 target）。
        if (!target_eof) {
            t_ev = CONN_FWD_RDHUP;
            if (!t2c_pending) t_ev |= CONN_FWD_IN;
        }
        if (c2t_pending) t_ev |= CONN_FWD_OUT;
    }

    if (client_ev) *client_ev = c_ev;
    if (target_ev) *target_ev = t_ev;
}

int conn_forward_grace_expired(time_t now, time_t eof_since, time_t grace) {
    if (eof_since == 0) return 0; /* 尚未 FIN */
    return (now - eof_since >= grace) ? 1 : 0;
}

int conn_stall_expired(time_t now, time_t last_progress,
                       uint64_t prev_window_bytes, int stall_sec,
                       uint64_t min_bytes) {
    /* 從未轉發過任何位元組：連線剛建立或握手階段，不屬於「停滯」。 */
    if (last_progress == 0) return 0;

    /* 上一個窗口沒有達到高速門檻 → 本來就是低速/閒置連線，不是出口故障。
     * 這一條是防誤殺遊戲、SSE、keep-alive 的關鍵，不可放寬。 */
    if (prev_window_bytes < min_bytes) return 0;

    /* 剛才還在高流量、現在卻完全沒有進展 → 出口已死。 */
    return (now - last_progress >= stall_sec) ? 1 : 0;
}
