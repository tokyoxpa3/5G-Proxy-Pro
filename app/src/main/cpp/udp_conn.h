#ifndef UDP_CONN_H
#define UDP_CONN_H

#include <stdint.h>
#include <time.h>

/*
 * UDP session 存活狀態與事件解碼的純驗證邏輯（零 socket / epoll / JNI 依賴）。
 *
 * 仿照 conn_state.h 的設計，將 UDP epoll 多路複用引擎的世代檢查、
 * fd 角色（client TCP 控制/資料 / local UDP / 5G remote UDP）解碼與逾時判斷
 * 抽離為純函式，由 host 單元測試鎖住，防範幽靈事件撞重用槽位。
 */

#define UDP_CONN_MAGIC 0x5EEDF00Eu

/* epoll 事件識別碼中的 fd 角色旗標（位於低 32 位元的 bit31 與 bit30） */
#define UDP_EV_ROLE_LOCAL_FLAG  (1ULL << 31)
#define UDP_EV_ROLE_REMOTE_FLAG (1ULL << 30)
#define UDP_EV_ROLE_MASK        (UDP_EV_ROLE_LOCAL_FLAG | UDP_EV_ROLE_REMOTE_FLAG)

typedef enum {
    UDP_ROLE_CLIENT = 0, /* Client TCP 連線（控制連線或 UDP-in-TCP 資料連線） */
    UDP_ROLE_LOCAL,      /* LAN 端 Local UDP relay socket (僅標準 0x03 使用) */
    UDP_ROLE_REMOTE      /* 5G 端 Remote UDP socket */
} udp_fd_role_t;

typedef enum {
    UDP_EV_PROCESS = 0,  /* 有效事件，應進入處理 */
    UDP_EV_INACTIVE,     /* 槽位未啟用（slot_state == 0） */
    UDP_EV_STALE,        /* 世代不符（egen != gen_now）或 magic 已毒化 */
    UDP_EV_NOT_READY     /* closed 或尚未完成 epoll 註冊 */
} udp_event_disp_t;

/* 判斷 slot 索引是否越界。sidx < 0 或 sidx >= slot_count 回傳 1，否則回傳 0。 */
int udp_event_bad_slot(int sidx, int slot_count);

/* 解碼 UDP epoll 事件識別碼：拆出 slot 索引、世代，以及觸發事件的 fd 角色。 */
void udp_event_decode(uint64_t raw, uint32_t *sidx, uint32_t *egen, udp_fd_role_t *role);

/* 判斷 UDP 槽位事件是否應被處理。檢查順序與 TCP 一致：INACTIVE -> STALE -> NOT_READY。 */
udp_event_disp_t udp_event_check_slot(uint32_t egen, uint32_t gen_now,
                                      uint32_t magic, int slot_state,
                                      int closed, int registered);

/* 判斷 UDP 連線是否已閒置逾時。last_active 為 0 時回傳 0；now - last_active > timeout_sec 回傳 1。 */
int udp_conn_idle_expired(time_t now, time_t last_active, time_t timeout_sec);

#endif /* UDP_CONN_H */
