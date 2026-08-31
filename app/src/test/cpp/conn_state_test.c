/* conn_state 純函式單元測試：鎖住 worker 事件迴圈的殘留事件防禦語意。
 * 與 socks5_protocol_test.c 相同，零 Android/JNI/socket 依賴，host 直接編譯執行。 */
#include "conn_state.h"

#include <stdio.h>

static int g_failures = 0;

#define CHECK(cond) do { \
    if (!(cond)) { \
        printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
        g_failures++; \
    } \
} while (0)

static void test_bad_slot(void) {
    /* 合法索引：0 與 slot_count-1 */
    CHECK(conn_event_bad_slot(0, 1088) == 0);
    CHECK(conn_event_bad_slot(1087, 1088) == 0);

    /* 越界：負數、等於 slot_count、遠超 slot_count */
    CHECK(conn_event_bad_slot(-1, 1088) == 1);
    CHECK(conn_event_bad_slot(1088, 1088) == 1);
    CHECK(conn_event_bad_slot(2000, 1088) == 1);
}

static void test_check_slot_process(void) {
    /* 全部欄位合法 → PROCESS */
    conn_event_disp_t d = conn_event_check_slot(
        5, 5, CONN_MAGIC, 1, 2, 2, 0, 1);
    CHECK(d == CONN_EV_PROCESS);
}

static void test_check_slot_inactive(void) {
    /* 槽位未啟用 → INACTIVE（即便其餘欄位都合法） */
    conn_event_disp_t d = conn_event_check_slot(
        5, 5, CONN_MAGIC, 0, 2, 2, 0, 1);
    CHECK(d == CONN_EV_INACTIVE);
}

static void test_check_slot_stale(void) {
    /* 世代不符 → STALE（magic 仍合法） */
    conn_event_disp_t d = conn_event_check_slot(
        4, 5, CONN_MAGIC, 1, 2, 2, 0, 1);
    CHECK(d == CONN_EV_STALE);

    /* magic 已毒化 → STALE（gen 相符） */
    d = conn_event_check_slot(
        5, 5, 0 /* poisoned */, 1, 2, 2, 0, 1);
    CHECK(d == CONN_EV_STALE);
}

static void test_check_slot_wrong_worker(void) {
    /* 事件屬於其他 worker → WRONG_WORKER */
    conn_event_disp_t d = conn_event_check_slot(
        5, 5, CONN_MAGIC, 1, 3, 2, 0, 1);
    CHECK(d == CONN_EV_WRONG_WORKER);
}

static void test_check_slot_not_ready(void) {
    /* closed=1 → NOT_READY */
    conn_event_disp_t d = conn_event_check_slot(
        5, 5, CONN_MAGIC, 1, 2, 2, 1, 1);
    CHECK(d == CONN_EV_NOT_READY);

    /* registered=0 → NOT_READY */
    d = conn_event_check_slot(
        5, 5, CONN_MAGIC, 1, 2, 2, 0, 0);
    CHECK(d == CONN_EV_NOT_READY);
}

static void test_check_slot_ordering(void) {
    /* 關鍵順序：magic 已毒化（finalize 進行中）的 conn，即便同時 closed=1、
     * registered=0，也必須歸為 STALE —— 幽靈追蹤的語意靠這個順序。
     * 若 STALE 檢查被移到 NOT_READY 之後，已毒化 conn 會被誤判為「未就緒」
     * 而靜默跳過，stale_skip 計數失真，殘留事件防禦的證據鏈就斷了。 */
    conn_event_disp_t d = conn_event_check_slot(
        5, 5, 0 /* poisoned */, 1, 2, 2, 1, 0);
    CHECK(d == CONN_EV_STALE);

    /* 同理：槽位未啟用優先於世代/magic。即便 gen/magic 也異常，
     * 仍應先歸為 INACTIVE（與 worker 迴圈先查 slot_state 一致）。 */
    d = conn_event_check_slot(
        4, 5, 0, 0, 3, 2, 1, 0);
    CHECK(d == CONN_EV_INACTIVE);
}

static void test_decode(void) {
    uint32_t sidx, egen; int is_target;

    /* client 事件：無旗標 → is_target=0，slot/gen 原樣 */
    conn_event_decode(((uint64_t)5u << 32) | 42u, &sidx, &egen, &is_target);
    CHECK(sidx == 42 && egen == 5 && is_target == 0);

    /* target 事件：帶 bit31 旗標，解碼後 slot/gen 不變、is_target=1 */
    conn_event_decode(((uint64_t)5u << 32) | 42u | CONN_EV_TARGET_FLAG,
                      &sidx, &egen, &is_target);
    CHECK(sidx == 42 && egen == 5 && is_target == 1);

    /* 邊界：slot 接近上限（1087 < 2^31）時旗標不干擾 slot 解碼 */
    conn_event_decode(((uint64_t)9u << 32) | 1087u | CONN_EV_TARGET_FLAG,
                      &sidx, &egen, &is_target);
    CHECK(sidx == 1087 && egen == 9 && is_target == 1);

    /* 世代高 32 位不受低 32 位旗標影響 */
    conn_event_decode(((uint64_t)0xFFFFFFFFu << 32) | 1087u | CONN_EV_TARGET_FLAG,
                      &sidx, &egen, &is_target);
    CHECK(sidx == 1087 && egen == 0xFFFFFFFFu && is_target == 1);
}

int main(void) {
    test_bad_slot();
    test_decode();
    test_check_slot_process();
    test_check_slot_inactive();
    test_check_slot_stale();
    test_check_slot_wrong_worker();
    test_check_slot_not_ready();
    test_check_slot_ordering();
    if (g_failures == 0) {
        printf("conn_state_test: ALL PASS\n");
        return 0;
    }
    printf("conn_state_test: %d FAILED\n", g_failures);
    return 1;
}