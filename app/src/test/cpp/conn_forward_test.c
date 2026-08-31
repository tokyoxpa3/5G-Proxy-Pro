/* conn_forward 純函式單元測試：鎖住 worker 資料轉發狀態機的兩個決策核心——
 * 事件興趣遮罩（level-triggered 熱迴圈防護）與半關閉 grace 期（CLOSE_WAIT 回收）。
 * 與 conn_state_test.c 相同，零 Android/JNI/socket/epoll 依賴，host 直接編譯執行。 */
#include "conn_forward.h"

#include <stdio.h>

static int g_failures = 0;

#define CHECK(cond) do { \
    if (!(cond)) { \
        printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
        g_failures++; \
    } \
} while (0)

/* 一次檢查事件興趣遮罩的完整輸出（比對 client 與 target 兩側）。 */
static void check_interest(int closed, int ceof, int teof, int c2t, int t2c,
                           uint32_t exp_c, uint32_t exp_t) {
    uint32_t c = 0xdead, t = 0xdead;
    conn_forward_interest(closed, ceof, teof, c2t, t2c, &c, &t);
    if (c != exp_c) {
        printf("FAIL interest client: closed=%d ceof=%d teof=%d c2t=%d t2c=%d got=%x exp=%x\n",
               closed, ceof, teof, c2t, t2c, c, exp_c);
        g_failures++;
    }
    if (t != exp_t) {
        printf("FAIL interest target: closed=%d ceof=%d teof=%d c2t=%d t2c=%d got=%x exp=%x\n",
               closed, ceof, teof, c2t, t2c, t, exp_t);
        g_failures++;
    }
}

#define RDHUP CONN_FWD_RDHUP
#define IN    CONN_FWD_IN
#define OUT   CONN_FWD_OUT

static void test_interest(void) {
    /* 已進入關閉流程：兩側都不 arm 任何事件 */
    check_interest(1, 0, 0, 0, 0, 0, 0);

    /* 閒置（無半關閉、無緩衝待送）：兩側皆 RDHUP|IN（正常雙向待命） */
    check_interest(0, 0, 0, 0, 0, RDHUP | IN, RDHUP | IN);

    /* 客戶端半關閉：client 側不再讀（無 IN），target 側不變 */
    check_interest(0, 1, 0, 0, 0, RDHUP, RDHUP | IN);

    /* 目標端半關閉：[CLOSE_WAIT 修復] target 側完全 disarm（無 RDHUP/IN），
     * 否則 level-triggered RDHUP 反覆觸發 = 熱迴圈。client 側不變。 */
    check_interest(0, 0, 1, 0, 0, RDHUP | IN, 0);

    /* 兩側皆半關閉：client 無 IN、target 全 0 */
    check_interest(0, 1, 1, 0, 0, RDHUP, 0);

    /* t2c 緩衝有待送資料（target→client）：client 加 OUT；target 因 t2c 非空
     * 停止再讀（無 IN），也無 c2t 待送故無 OUT */
    check_interest(0, 0, 0, 0, 1, RDHUP | IN | OUT, RDHUP);

    /* c2t 緩衝有待送資料（client→target）：client 因 c2t 非空停止再讀（無 IN）；
     * target 加 OUT（可寫給 target） */
    check_interest(0, 0, 0, 1, 0, RDHUP, RDHUP | IN | OUT);

    /* 雙向緩衝皆有待送資料：兩側都只有 OUT（皆停止再讀） */
    check_interest(0, 0, 0, 1, 1, RDHUP | OUT, RDHUP | OUT);

    /* 客戶端半關閉 + t2c 待送（最後一批回應回給已 FIN 的 client） */
    check_interest(0, 1, 0, 0, 1, RDHUP | OUT, RDHUP);

    /* 目標端半關閉 + c2t 待送（半關閉的 target 仍可接收，排空剩餘 c2t） */
    check_interest(0, 0, 1, 1, 0, RDHUP, OUT);
}

static void test_interest_null_outputs(void) {
    /* 輸出指標為 NULL 時不得寫壞記憶體（防禦路徑） */
    conn_forward_interest(0, 0, 0, 0, 0, NULL, NULL);
    conn_forward_interest(1, 1, 1, 1, 1, NULL, NULL);
}

static void test_grace_expired(void) {
    /* 尚未 FIN（eof_since==0）：無論時間差多大都不回收 */
    CHECK(conn_forward_grace_expired(1000, 0, CONN_FWD_GRACE_SEC) == 0);

    /* 未到 grace（差 < grace） */
    CHECK(conn_forward_grace_expired(1000, 999, CONN_FWD_GRACE_SEC) == 0);

    /* 恰好等於 grace（>= 為閉區間）：回收 */
    CHECK(conn_forward_grace_expired(1000, 998, CONN_FWD_GRACE_SEC) == 1);

    /* 超過 grace：回收 */
    CHECK(conn_forward_grace_expired(1000, 900, CONN_FWD_GRACE_SEC) == 1);

    /* grace 為 0 的邊界：只要已 FIN 就回收 */
    CHECK(conn_forward_grace_expired(1000, 1000, 0) == 1);
}

int main(void) {
    test_interest();
    test_interest_null_outputs();
    test_grace_expired();
    if (g_failures == 0) {
        printf("conn_forward_test: ALL PASS\n");
        return 0;
    }
    printf("conn_forward_test: %d FAILED\n", g_failures);
    return 1;
}
