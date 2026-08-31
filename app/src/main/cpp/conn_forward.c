#include "conn_forward.h"

void conn_forward_interest(int closed, int client_eof, int target_eof,
                           int c2t_pending, int t2c_pending,
                           uint32_t *client_ev, uint32_t *target_ev) {
    uint32_t c_ev = 0, t_ev = 0;

    if (!closed) {
        // client fd：RDHUP 恆 arm（偵測客戶端半關閉）；c2t 緩衝清空且未半關閉
        // 才需要 IN（可再讀入）；t2c 緩衝有待送出資料才需要 OUT（可寫回）。
        c_ev = CONN_FWD_RDHUP;
        if (!c2t_pending && !client_eof) c_ev |= CONN_FWD_IN;
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
