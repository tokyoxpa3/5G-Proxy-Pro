#ifndef CONN_STATE_H
#define CONN_STATE_H

#include <stdint.h>

/*
 * 連線（conn）存活狀態的純驗證邏輯（零 socket / epoll / JNI 依賴）。
 *
 * 從 simple-socks5.c 的 worker 事件迴圈抽離，目的是讓「殘留事件防禦」的
 * 判斷鏈（槽位未啟用 → 世代/magic 不符 → 錯 worker → 未就緒）能被 host 端
 * 單元測試鎖住。過去 SIGSEGV 的根因正是殘留事件撞上重用槽位，這條判斷鏈的
 * 順序若被無意更動，就會重新打開 UAF 大門。
 */

/* 存活 conn 的驗證值；finalize 時毒化為 0。 */
#define CONN_MAGIC 0x5EEDF00Du

/* 事件處置結果，與 worker 迴圈的檢查順序一一對應。 */
typedef enum {
    CONN_EV_PROCESS = 0,   /* 有效事件，應進入資料轉發 */
    CONN_EV_INACTIVE,      /* 槽位未啟用（slot_state == 0） */
    CONN_EV_STALE,         /* 世代不符（egen != gen_now）或 magic 已毒化 */
    CONN_EV_WRONG_WORKER,  /* 事件屬於其他 worker（widx != my_widx） */
    CONN_EV_NOT_READY,     /* closed 或尚未完成 epoll 註冊 */
} conn_event_disp_t;

/* 判斷 slot 索引是否越界（供 caller 在解引用 g_slots[sidx] 之前先擋下）。
 * sidx < 0 或 sidx >= slot_count 回傳 1，否則回傳 0。 */
int conn_event_bad_slot(int sidx, int slot_count);

/* 判斷一個已解出槽位的事件是否應被處理。
 * 參數為槽位/連線的欄位快照；檢查順序必須與 worker 迴圈一致——
 * 先 INACTIVE、再 STALE（magic 已毒化的 conn 在此被歸為 STALE 供幽靈追蹤）、
 * 最後 WRONG_WORKER / NOT_READY。回傳上方 enum。 */
conn_event_disp_t conn_event_check_slot(uint32_t egen, uint32_t gen_now,
                                        uint32_t magic, int slot_state,
                                        int widx, int my_widx,
                                        int closed, int registered);

/* epoll 事件識別碼的「target fd」旗標：位於低 32 位的 bit31。
 * client/target 共用 (gen<<32|slot) 為基礎識別碼；為區分 EPOLLRDHUP 來源
 * （對端半關閉是 client 或 target 觸發），target fd 的 u64 額外帶此旗標。
 * slot 上限 CONN_SLOT_COUNT(1088) 遠小於 2^31，bit31 不會與 slot 衝突。 */
#define CONN_EV_TARGET_FLAG (1ULL << 31)

/* 解碼 epoll 事件識別碼：拆出 slot 索引、世代，以及是否為 target fd。
 * is_target 為 1 時代表該事件來自 target fd（帶 CONN_EV_TARGET_FLAG）。 */
void conn_event_decode(uint64_t raw, uint32_t *sidx, uint32_t *egen, int *is_target);

#endif /* CONN_STATE_H */
