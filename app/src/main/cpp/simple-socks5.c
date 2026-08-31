#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/epoll.h>
#include <poll.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>
#include <android/log.h>
#include <stdatomic.h>
#include <stdint.h>
#include <time.h>

#include "socks5_protocol.h"
#include "conn_state.h"
#include "conn_forward.h"
#include "udp_conn.h"
#include "ghost_purge.h"

extern void jni_attach_thread();
extern void jni_detach_thread();
extern int request_java_5g_socket(const char* host, int port, int is_udp);
// [根因修復 v2] fromSocket() 是 dup 語意：C 與 Java 各持一個 fd 引用同一描述，
// 釋放時必須「雙邊各關各的」——close() 關 C 的副本，release_java_socket() 讓
// Java socket.close() 收掉原生引用。只關任一邊都會洩漏成 CLOSE_WAIT 幽靈。
extern void release_java_socket(int fd);

#define LOG_TAG "SimpleSocks5"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define BUFFER_SIZE (64 * 1024)
#define MAX_EVENTS 512
#define WORKER_COUNT 4
#define MAX_CONCURRENT_CONNS 1000 
// [Slot 修復] 槽位總數必須 > MAX_CONCURRENT_CONNS（握手 CAS 預佔額度後才取槽）
#define CONN_SLOT_COUNT 1088
#define IDLE_TIMEOUT_SEC 300
#define UDP_IDLE_TIMEOUT_SEC 60 
/* CONN_MAGIC 已移至 conn_state.h（供純函式模組與本檔共用同一驗證值） */

static atomic_int g_conn_count = 0;
static int g_shutdown_pipe[2] = {-1, -1};

static pthread_mutex_t g_auth_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_auth_user[256];
static char g_auth_pass[256];
static int g_auth_enabled = 0;

typedef struct full_conn_t {
    // [Slot 修復] epoll 事件不再攜帶 malloc 位址，改攜帶 (gen<<32 | slot_idx)。
    // gen 為該槽位的世代編號，每次重新啟用時 +1；殘留事件的 gen 與現值不符
    // 即為舊連線的幽靈事件，直接跳過。因為槽位記憶體永不釋放，讀取永遠安全，
    // 「殘留事件撞上重用記憶體」的 UAF 在結構上不可能發生。
    uint32_t magic;
    int client_fd;
    int target_fd;
    unsigned char *c2t_buf;
    unsigned char *t2c_buf;
    ssize_t c2t_len, c2t_off;
    ssize_t t2c_len, t2c_off;
    int closed; // 標記是否已進入關閉流程（由 list_lock 保護）
    int client_eof; // 客戶端已 FIN（半關閉）: 停止讀取但繼續轉發 target→client
    time_t eof_since; // client_eof 的起始時間（grace period 用）
    time_t target_eof_since; // target 已 FIN 的起始時間（對稱 client_eof，grace period 用）
    uint32_t client_events;
    uint32_t target_events;

    // [H2 修復] 引用計數：handoff（握手執行緒）與 worker 並發持有同一 conn，
    // 任何一方都可能在另一方還在使用時決定銷毀；refs 歸零才真正釋放
    atomic_int refs;
    // [H2 修復] finalized 防護旗標：conn_finalize 只允許執行一次。
    // 即使 refs 帳目因未來改動出錯，也絕不會二次退休 → double-free
    atomic_int finalized;
    // [H2 修復] 兩個 epoll_ctl ADD 都完成前，worker 必須忽略此 conn 的事件
    // （ADD 進行中事件若提前送達，會與 handoff 的初始化競態）
    atomic_int registered;

    time_t last_active;
    struct full_conn_t *next;
    struct full_conn_t *prev;
    int widx; // [H2 修復] 擁有此 conn 的 worker 索引（退休鏈路由該 worker 的鎖保護）
    // [Slot 修復] 槽位識別
    int slot;              // 屬於哪個槽位（finalize 釋放槽位用）
    uint32_t gen;          // 目前世代（事件驗證用）
    uint64_t ep_u64;       // 寫入 epoll data.u64 的完整識別碼，MOD 時重用
} full_conn_t;

typedef struct {
    int epoll_fd;
    pthread_t thread_id;
    full_conn_t *conn_list_head;
    pthread_mutex_t list_lock; // [關鍵] 保護鏈表結構的鎖
} worker_t;

// [H2 修復] workers 陣列必須宣告在槽位表之後：finalize/handoff 都會以
// `w - workers` 計算 worker 索引（原先宣告在 132 行會導致編譯錯誤）
static worker_t workers[WORKER_COUNT];

// ================= [Slot 修復] 固定槽位表 =================
// 舊退休機制（conn 進 retirement 鏈延遲 16 世代後 free）只能「機率性」防護：
// 實測殘留事件仍可能在 >16 個迭代後送達，此時 free 過的記憶體已被重用，
// 新 conn 若恰好落在同一 worker，magic/widx 全部通過 → 幽靈事件處理錯誤連線
// （SEGV_ACCERR fault addr 0xb4...0058 = tagged heap 上 offset 0x58/refs 欄位）。
//
// 根本改法：所有 conn 本體放在靜態槽位表，**永不 malloc/free**。
// epoll 事件攜帶 (gen<<32|slot)，gen 於槽位重用時遞增；幽靈事件 gen 不符即棄。
// 記憶體永遠合法 → 讀取不可能 SEGV；世代檢查 → 不可能處理到錯的連線。
static full_conn_t g_slots[CONN_SLOT_COUNT];
static atomic_int g_slot_state[CONN_SLOT_COUNT]; // 1 = 使用中, 0 = 空閒
static int g_free_slots[CONN_SLOT_COUNT];        // 空閒槽位堆疊
static int g_free_slot_top = 0;
static pthread_mutex_t g_slot_lock = PTHREAD_MUTEX_INITIALIZER;

// [Slot 修復] 生命週期統計：無 tombstone 的崩潰難以診斷，改由計數器 +
// logcat 即時異常記錄提供證據（stale_skip > 0 代表確實存在幽靈事件）
static atomic_llong g_st_acquired = 0, g_st_released = 0;
static atomic_llong g_st_stale_skip = 0;   // gen 不符被跳過的事件數（關鍵指標）
static atomic_llong g_st_bad_slot = 0;     // slot 越界 / 槽位未啟用
static atomic_llong g_st_exhausted = 0;    // 槽位耗盡次數
static atomic_llong g_st_double_fin = 0;   // 二次 finalize 嘗試

// 取得空閒槽位並遞增世代。回傳 slot 索引，耗盡回傳 -1。
// 只在 handoff_to_worker（握手池執行緒）呼叫。
static int slot_acquire(void) {
    pthread_mutex_lock(&g_slot_lock);
    if (g_free_slot_top == 0) {
        pthread_mutex_unlock(&g_slot_lock);
        atomic_fetch_add(&g_st_exhausted, 1);
        return -1;
    }
    int idx = g_free_slots[--g_free_slot_top];
    uint32_t new_gen = ++g_slots[idx].gen; // 重用即換代：舊事件全部作廢
    pthread_mutex_unlock(&g_slot_lock);

    // 發佈前重置整個結構（取代 calloc 的歸零語意）；gen 已在鎖內更新須保留
    memset(&g_slots[idx], 0, sizeof(full_conn_t));
    g_slots[idx].gen = new_gen;
    g_slots[idx].slot = idx;
    atomic_store(&g_slot_state[idx], 1);
    atomic_fetch_add(&g_st_acquired, 1);
    return idx;
}

// 歸還槽位（conn_finalize 尾端呼叫；呼叫者不得再碰此 conn）
static void slot_release(int idx) {
    atomic_store(&g_slot_state[idx], 0);
    pthread_mutex_lock(&g_slot_lock);
    g_free_slots[g_free_slot_top++] = idx;
    pthread_mutex_unlock(&g_slot_lock);
    atomic_fetch_add(&g_st_released, 1);
}

static void slots_init(void) {
    for (int i = 0; i < CONN_SLOT_COUNT; i++) {
        // gen 單調遞增不重設：避免前一個服務週期的殘留事件（同 gen 值）在
        // 重啟後誤配。初值 1：data.u64==0（slot0+gen0）保留給 shutdown pipe
        if (g_slots[i].gen == 0) g_slots[i].gen = 1;
        atomic_store(&g_slot_state[i], 0);
        g_free_slots[i] = i;
    }
    g_free_slot_top = CONN_SLOT_COUNT;
}

static atomic_int server_running = 0;
static pthread_t listener_thread;
static atomic_int next_worker_idx = 0;

#define MAX_LISTENERS 8
#define MAX_BIND_ADDRS 16
static int g_listener_fds[MAX_LISTENERS];
static int g_listener_count = 0;
static char g_bind_addrs[MAX_BIND_ADDRS][INET6_ADDRSTRLEN];
static int g_bind_count = 0;

static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags != -1) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void optimize_socket(int fd) {
    int flag = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, (char *)&flag, sizeof(int));
    int buf_size = 3 * 1024 * 1024; 
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf_size, sizeof(buf_size));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf_size, sizeof(buf_size));
}

// [修改] 鏈表操作現在必須在持有 list_lock 時調用
static void list_add_locked(worker_t *w, full_conn_t *conn) {
    conn->next = w->conn_list_head;
    conn->prev = NULL;
    if (w->conn_list_head) w->conn_list_head->prev = conn;
    w->conn_list_head = conn;
}

static void list_remove_locked(worker_t *w, full_conn_t *conn) {
    if (conn->prev) conn->prev->next = conn->next;
    else w->conn_list_head = conn->next;
    if (conn->next) conn->next->prev = conn->prev;
    conn->next = NULL;
    conn->prev = NULL;
}

static int try_send(int fd, unsigned char *buf, ssize_t *len, ssize_t *off) {
    if (fd < 0) return -1;
    while (*off < *len) {
        ssize_t sent = send(fd, buf + *off, *len - *off, MSG_NOSIGNAL);
        if (sent > 0) {
            *off += sent;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
            return -1;
        }
    }
    *off = 0; *len = 0;
    return 1;
}

// [可測抽離] 事件興趣遮罩的決策核心抽至 conn_forward_interest（純函式，host 可測）。
// 這裡只做 CONN_FWD_* 抽象位元 → epoll 常數的機械映射，以及實際的 epoll_ctl MOD。
static uint32_t map_fwd_to_epoll(uint32_t fwd) {
    uint32_t ev = 0;
    if (fwd & CONN_FWD_RDHUP) ev |= EPOLLRDHUP;
    if (fwd & CONN_FWD_IN)    ev |= EPOLLIN;
    if (fwd & CONN_FWD_OUT)   ev |= EPOLLOUT;
    return ev;
}

static void update_conn_events(int epoll_fd, full_conn_t *full) {
    if (full->closed) return;
    // [CLOSE_WAIT 修復] target 已半關閉（FIN）後不再需要 EPOLLRDHUP/EPOLLIN：
    // 不會再有資料送達，持續 armed 只會讓 level-triggered EPOLLRDHUP 反覆觸發
    // （熱迴圈）。保留 EPOLLOUT（半關閉的 target 仍可接收）讓 c2t 剩餘資料在
    // grace period 內排空。此決策（含 client 側對稱規則）由 conn_forward_interest
    // 以純函式鎖住，host 單元測試覆蓋。
    uint32_t c_fwd, t_fwd;
    conn_forward_interest(
        full->closed, full->client_eof, full->target_eof_since ? 1 : 0,
        full->c2t_len > 0, full->t2c_len > 0, &c_fwd, &t_fwd);
    uint32_t c_ev = map_fwd_to_epoll(c_fwd);
    uint32_t t_ev = map_fwd_to_epoll(t_fwd);

    // [Slot 修復] 事件攜帶 (gen<<32|slot)，MOD 時必須重用同一識別碼
    if (full->client_events != c_ev) {
        struct epoll_event ev; ev.events = c_ev; ev.data.u64 = full->ep_u64;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, full->client_fd, &ev) == 0) full->client_events = c_ev;
    }
    if (full->target_events != t_ev) {
        struct epoll_event ev; ev.events = t_ev; ev.data.u64 = full->ep_u64 | CONN_EV_TARGET_FLAG;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, full->target_fd, &ev) == 0) full->target_events = t_ev;
    }
}

// [H2 修復] 引用計數輔助：refs 歸零的一方負責銷毀。fd 與緩衝立即釋放，
// conn 槽位標記為空閒（[Slot 修復] 本體記憶體永不釋放，無退休鏈）。
// 注意呼叫方：conn->closed 必須已為 1（殘留事件讀到 closed=1 會 skip）
//
// [H2 修復 v2] refs 語意簡化為「base ref = 1」：slot_acquire 後由 handoff 設定，
// 任何路徑恰好 unref 一次。
// 成功路徑：handoff 不扣、worker 扣（step-3 垃圾回收）→ 1→0 finalize。
// add_failed（handoff 搶到鎖）：handoff 扣一次 → 1→0 finalize。
// add_failed（worker 先收集）：worker 扣一次，handoff 不扣 → 1→0 finalize。
// 緩衝失敗：handoff 扣一次 → 1→0 finalize。
static void conn_finalize(full_conn_t *conn) {
    // finalized 防護：任何殘留的重複 unref 都不得二次 finalize
    if (atomic_exchange(&conn->finalized, 1) != 0) {
        atomic_fetch_add(&g_st_double_fin, 1);
        LOGE("WARN: conn_finalize called twice slot=%d gen=%u", conn->slot, conn->gen);
        return; // 防止二次釋放槽位 / 二次關閉 fd
    }

    // 防禦性：若 conn 仍掛在 worker 鏈表上（理論上不該發生），先移除再釋放，
    // 確保 conn 不會在鏈表中被重用（timeout 掃描會讀到不一致的 next/prev）。
    // magic 毒化在同一個鎖內、移除之後執行：
    // 保證「已毒化卻仍在鏈表上」的狀態不存在（掃描不會撞見半釋放的 conn）
    worker_t *w = &workers[conn->widx];
    pthread_mutex_lock(&w->list_lock);
    if (conn->next || conn->prev || w->conn_list_head == conn) {
        list_remove_locked(w, conn);
        LOGI("conn_finalize: removed still-linked conn slot=%d from worker %d", conn->slot, conn->widx);
    }
    conn->magic = 0;
    pthread_mutex_unlock(&w->list_lock);

    // 關閉 FDs（close 會自動把 fd 從所屬 epoll 移除）
    if (conn->client_fd >= 0) { close(conn->client_fd); conn->client_fd = -1; }
    if (conn->target_fd >= 0) {
        // [根因修復 v2 2026-08-23] 實測（HyperOS/Android 15 單連線追蹤）：
        // ParcelFileDescriptor.fromSocket() 是「DUP」語意 —— 同一個 socket
        // 開啟描述存在兩個 fd：原生 fd（java.net.Socket 內部持有）與 detachFd()
        // 交給 C 的副本。只關任一個，另一個都會讓描述存活成 CLOSE_WAIT，
        // 留在 epoll 永久就緒 → level-triggered 事件風暴 → 舊設計下殘留事件
        // 撞重用記憶體 = SIGSEGV 的真正源頭。
        // 因此必須雙邊各關各的：C 關自己的副本，並通知 Java 關 Socket（原生）。
        //
        // [fd 重用競態 2026-08-31] 順序必須是「先 release 再 close」：
        // activeSockets 以 fd 編號當 key，close() 一執行該編號即被 OS 釋放、
        // 可能立刻被並發握手執行緒的 detachFd() 重用。若先 close 再 release，
        // 延遲抵達的 JNI map.remove(fd) 會誤刪並關閉「重用編號」上的無辜連線。
        // dup 語意下兩邊是不同編號、指向同一描述，先關 Java 端不影響 C 端副本，
        // 描述要等 C 端也 close 後才真正銷毀（發 FIN）。
        release_java_socket(conn->target_fd); // Java 端 socket.close() 收掉原生 fd
        close(conn->target_fd);
        conn->target_fd = -1;
    }

    if (conn->c2t_buf) { free(conn->c2t_buf); conn->c2t_buf = NULL; }
    if (conn->t2c_buf) { free(conn->t2c_buf); conn->t2c_buf = NULL; }

    atomic_fetch_sub(&g_conn_count, 1);
    int slot = conn->slot;
    slot_release(slot); // [Slot 修復] 槽位回到空閒堆疊；本體記憶體永不釋放
}

static void conn_unref(full_conn_t *conn) {
    if (!conn) return;
    if (atomic_fetch_sub(&conn->refs, 1) == 1) {
        conn_finalize(conn);
    }
}

static void* worker_loop_safe(void* arg) {
    jni_attach_thread();
    worker_t *me = (worker_t*)arg;
    int my_widx = (int)(me - workers); // 自身 worker 索引，用於殘留事件防禦
    struct epoll_event events[MAX_EVENTS];
    
    // 垃圾回收佇列 (用於在鎖外釋放資源)
    full_conn_t *garbage_list[MAX_EVENTS]; 
    int garbage_count = 0;
    // [H2 修復] 退出路徑的批次回收佇列（宣告在頂端：goto exit_worker 會跳過標籤後宣告）
    full_conn_t *collected[MAX_EVENTS];

    time_t last_check_time = time(NULL);

    struct epoll_event stop_ev; stop_ev.events = EPOLLIN; stop_ev.data.u64 = 0;
    epoll_ctl(me->epoll_fd, EPOLL_CTL_ADD, g_shutdown_pipe[0], &stop_ev);

    while (atomic_load(&server_running)) {
        garbage_count = 0;
        int nfds = epoll_wait(me->epoll_fd, events, MAX_EVENTS, 2000); // 縮短 wait 時間增加反應速度
        time_t now = time(NULL);

        // 1. 處理 I/O 事件
        for (int i = 0; i < nfds; i++) {
            if (events[i].data.u64 == 0) goto exit_worker; // Shutdown pipe event

            // [Slot 修復] 事件解碼：低 32 位 = 槽位索引，高 32 位 = 事件發生時的世代。
            // 槽位記憶體永不釋放，以下所有讀取都安全；世代不符 = 幽靈事件（殘留），
            // 直接跳過 —— 不可能處理到已被重用的連線。
            // [可測抽離] 事件識別碼解碼抽至 conn_event_decode（純函式，host 可測）。
            // client/target 共用 (gen<<32|slot)，target fd 額外帶 CONN_EV_TARGET_FLAG
            // 以區分 EPOLLRDHUP 來源（對端半關閉是 client 或 target 觸發）。
            uint32_t sidx, egen; int is_target;
            conn_event_decode(events[i].data.u64, &sidx, &egen, &is_target);
            uint64_t key_u64 = events[i].data.u64 & ~CONN_EV_TARGET_FLAG;
            // [可測抽離] 事件有效性判斷抽至 conn_event_bad_slot / conn_event_check_slot
            // （純函式，host 可測）。判斷鏈順序（越界 → 未啟用 → 世代/magic → 錯 worker
            // → 未就緒）若被更動，等同重新打開殘留事件撞重用槽位的 UAF 大門。
            if (conn_event_bad_slot((int)sidx, CONN_SLOT_COUNT)) {
                // [Slot 診斷] 速率限制：每 worker 每秒最多 1 筆，避免熱迴圈灌爆 logcat
                static time_t last_bad_log[WORKER_COUNT] = {0};
                if (now - last_bad_log[my_widx] >= 1) {
                    last_bad_log[my_widx] = now;
                    LOGE("bad_slot idx=%llu raw_u64=%llx events=%x", (unsigned long long)sidx,
                         (unsigned long long)events[i].data.u64, events[i].events);
                }
                atomic_fetch_add(&g_st_bad_slot, 1);
                continue;
            }
            full_conn_t *full = &g_slots[sidx];
            conn_event_disp_t disp = conn_event_check_slot(
                egen, full->gen, full->magic,
                atomic_load(&g_slot_state[sidx]),
                full->widx, my_widx,
                full->closed, atomic_load(&full->registered));
            if (disp == CONN_EV_INACTIVE) {
                ghost_stuck_track(key_u64, "inactive", egen,
                                  g_slots[sidx].gen, g_slots[sidx].magic, g_slots[sidx].widx,
                                  events[i].events, now);
                atomic_fetch_add(&g_st_bad_slot, 1);
                continue;
            }
            if (disp == CONN_EV_STALE) {
                // 幽靈事件：正常情況下 gen 遞增後舊事件全部失效。此計數 > 0 證明
                // 殘留事件確實存在且被正確防禦（舊設計下這正是 SIGSEGV 來源）
                ghost_stuck_track(key_u64, "stale", egen,
                                  full->gen, full->magic, full->widx, events[i].events, now);
                atomic_fetch_add(&g_st_stale_skip, 1);
                continue;
            }
            // [H2 修復] 錯 worker / closed / 尚未完成 epoll 註冊的連線一律跳過：
            // handoff 的兩個 ADD 完成前，事件提前送達會與初始化競態
            if (disp == CONN_EV_WRONG_WORKER || disp == CONN_EV_NOT_READY) continue;

            full->last_active = now;
            uint32_t ev = events[i].events;
            
            int fatal_error = 0;
            // EPOLLRDHUP 不再視為立即致命:對端 FIN 前通常還有一批資料在緩衝區,
            // 直接關閉會把最後一段回應丟掉 (HTTP 200 但 body 不完整)
            if (ev & (EPOLLERR | EPOLLHUP)) fatal_error = 1;

            // 數據轉發邏輯
            if (!fatal_error) {
                // Buffer flushing (To Client)
                if (full->t2c_len > 0) {
                    if (try_send(full->client_fd, full->t2c_buf, &full->t2c_len, &full->t2c_off) < 0) fatal_error = 1;
                }
                // Buffer flushing (To Target)
                if (full->c2t_len > 0) {
                    if (try_send(full->target_fd, full->c2t_buf, &full->c2t_len, &full->c2t_off) < 0) fatal_error = 1;
                }
                // Read from Client
                if (!fatal_error && full->c2t_len == 0 && !full->client_eof) {
                    ssize_t r = recv(full->client_fd, full->c2t_buf, BUFFER_SIZE, 0);
                    if (r > 0) {
                        full->c2t_len = r; full->c2t_off = 0;
                        if (try_send(full->target_fd, full->c2t_buf, &full->c2t_len, &full->c2t_off) < 0) fatal_error = 1;
                    } else if (r == 0) {
                        // 客戶端半關閉 (FIN): 停止讀取，但仍須把 target 的剩餘資料轉發回去
                        full->client_eof = 1;
                        full->eof_since = now;
                    } else if (errno != EAGAIN && errno != EWOULDBLOCK) fatal_error = 1;
                }
                // Read from Target
                if (!fatal_error && full->t2c_len == 0) {
                    ssize_t r = recv(full->target_fd, full->t2c_buf, BUFFER_SIZE, 0);
                    if (r > 0) {
                        full->t2c_len = r; full->t2c_off = 0;
                        if (try_send(full->client_fd, full->t2c_buf, &full->t2c_len, &full->t2c_off) < 0) fatal_error = 1;
                    } else if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) fatal_error = 1;
                }
            }

            // [CLOSE_WAIT 修復] 主動處理 EPOLLRDHUP：對端已送 FIN（半關閉）。
            // 藉由 is_target 精確區分來源——target 半關閉時記下 target_eof_since，
            // 交由下方 5 秒掃描的 grace period 回收（對稱 client_eof）。過去 target
            // FIN 只在 t2c_len==0 時能被 recv 偵測；client 停滯（t2c_len>0，緩衝塞滿）
            // 時 recv 被跳過、EPOLLRDHUP 又被忽略 → 上游 fd 卡 CLOSE_WAIT 永不回收。
            if (!fatal_error && is_target && !full->target_eof_since &&
                (ev & EPOLLRDHUP) && full->target_fd >= 0) {
                full->target_eof_since = now;
            }

            // 緩衝清空後以 MSG_PEEK 偵測對端是否已 FIN:
            // 伺服器關閉 = 回應已完整送達,此時才結束連線
            if (!fatal_error && full->c2t_len == 0 && full->t2c_len == 0) {
                if (full->client_eof) {
                    // [關鍵] client 已 FIN（半關閉）且所有資料已轉發完成：
                    // 給 target 2 秒 grace period 等待剩餘資料（半關閉的 client
                    // 仍可能再收到 target 遲到的回應），逾時即結束連線回收 fd。
                    // 不能無限期等 target 的 FIN——HTTP keep-alive 的 target
                    // 不會發 FIN，否則測速等大量短連線會堆積數百條 CLOSE_WAIT
                    // 消耗 fd，最終拒絕服務。
                    // [可測抽離] grace 判定抽至 conn_forward_grace_expired（純函式）。
                    if (conn_forward_grace_expired(now, full->eof_since, CONN_FWD_GRACE_SEC)) fatal_error = 1;
                } else {
                    char tmp;
                    if (recv(full->target_fd, &tmp, 1, MSG_PEEK) == 0) fatal_error = 1;
                }
            }

            if (fatal_error) {
                // [關鍵] 標記刪除，稍後統一處理
                // 注意：同一連線的兩個 fd 可能在同一批事件中同時觸發，
                // 因此不能在事件迴圈內立即 destroy（第二個事件會讀到已釋放記憶體）。
                // 事件迴圈最多加入 nfds(<=MAX_EVENTS) 個垃圾，不可能溢出。
                pthread_mutex_lock(&me->list_lock);
                if (!full->closed) {
                    full->closed = 1;
                    list_remove_locked(me, full);
                    garbage_list[garbage_count++] = full;
                }
                pthread_mutex_unlock(&me->list_lock);
            } else {
                update_conn_events(me->epoll_fd, full);
            }
        }

        // 2. 檢查超時 (每 5 秒一次)
        //    鏈表最多可有 MAX_CONCURRENT_CONNS(1000) 條連線，超過 MAX_EVENTS 的
        //    超時連線若直接丟棄會造成 fd 永久洩漏；桶滿時先鎖外銷毀再繼續收集。
        if (now - last_check_time >= 5) {
            pthread_mutex_lock(&me->list_lock); // [關鍵] 鎖住鏈表進行遍歷
            full_conn_t *curr = me->conn_list_head;
            while (curr) {
                full_conn_t *next = curr->next;
                // [CLOSE_WAIT 修復] 主動偵測 target FIN：client 停滯（t2c 塞滿）時，
                // 正常 target 讀取（事件迴圈只在 t2c_len==0 才 recv）被跳過，target
                // 的 FIN 永遠無法經由事件迴圈偵測 → 上游 fd 卡 CLOSE_WAIT。此處對
                // 仍有效、尚有未排空資料的 conn 做一次 MSG_PEEK，確認 target 是否
                // 已半關閉；確認後以 target_eof_since + 2 秒 grace 回收（對稱 client_eof）。
                if (curr->magic == CONN_MAGIC && !curr->closed && atomic_load(&curr->registered) &&
                    !curr->target_eof_since && curr->t2c_len > 0 && curr->target_fd >= 0) {
                    char peek;
                    if (recv(curr->target_fd, &peek, 1, MSG_PEEK) == 0) {
                        curr->target_eof_since = now;
                    }
                }
                // 檢查是否超時且未被關閉
                // 1. 一般 idle 超時
                // 2. client 已半關閉且超過 2 秒 grace period（target 的 keep-alive
                //    連線不會發 FIN，事件迴圈不會再觸發，必須靠這裡回收，
                //    否則 CLOSE_WAIT 堆積消耗 fd）
                // 3. target 已半關閉（FIN）且超過 2 秒 grace period：client 停滯
                //    未讀導致剩餘資料無法排空，強制回收上游 fd（對稱第 2 點）
                // magic 檢查：已毒化（finalize 進行中）的 conn 不收集，
                // finalize 的防禦移除會負責把它解開，避免雙重移除
                // [可測抽離] grace 判定抽至 conn_forward_grace_expired（純函式），
                // 與事件迴圈內的同名判定共用同一函式與同一常數，杜絕漂移。
                if (curr->magic == CONN_MAGIC && !curr->closed && atomic_load(&curr->registered) &&
                    (now - curr->last_active > IDLE_TIMEOUT_SEC ||
                    (curr->client_eof && conn_forward_grace_expired(now, curr->eof_since, CONN_FWD_GRACE_SEC)) ||
                    conn_forward_grace_expired(now, curr->target_eof_since, CONN_FWD_GRACE_SEC))) {
                    curr->closed = 1;
                    list_remove_locked(me, curr);
                    if (garbage_count < MAX_EVENTS) garbage_list[garbage_count++] = curr;
                    else {
                        // 桶滿：先鎖外銷毀已收集的垃圾騰出空間，再收下這條連線
                        pthread_mutex_unlock(&me->list_lock);
                        for (int g = 0; g < garbage_count; g++) conn_unref(garbage_list[g]);
                        garbage_count = 0;
                        pthread_mutex_lock(&me->list_lock);
                        garbage_list[garbage_count++] = curr;
                    }
                }
                curr = next;
            }
            pthread_mutex_unlock(&me->list_lock);
            last_check_time = now;

            // [Slot 修復] 生命週期統計僅在 debug 版輸出（release 版不刷 logcat）
#ifndef NDEBUG
            static time_t last_stats = 0;
            if (my_widx == 0 && now - last_stats >= 30) {
                last_stats = now;
                LOGI("stats: conns=%d acquired=%lld released=%lld stale_skip=%lld bad_slot=%lld exhausted=%lld purged=%lld",
                     atomic_load(&g_conn_count),
                     (long long)atomic_load(&g_st_acquired), (long long)atomic_load(&g_st_released),
                     (long long)atomic_load(&g_st_stale_skip), (long long)atomic_load(&g_st_bad_slot),
                     (long long)atomic_load(&g_st_exhausted), ghost_purge_get_count());
            }
#endif
        }

        // 3. 執行垃圾回收 (在鎖外，安全執行 JNI)
        //    [H2 修復] unref 放下 worker 的參考；handoff 仍持有的連線不會在此釋放
        for (int i = 0; i < garbage_count; i++) {
            conn_unref(garbage_list[i]);
        }
    }

exit_worker:
    // 清理剩餘連線（退出時執行緒池已先排水，沒有並發的 handoff 競爭；
    // JNI release_java_socket 在鎖外呼叫同樣安全：Java 端只碰 ConcurrentHashMap）。
    // [H2 修復] 鎖內只做「取出 + 標記 closed」，conn_unref 一律移到鎖外：
    // conn_unref → conn_finalize 會再鎖同一個 list_lock，
    // 在鎖內呼叫等同對自己持有的非遞迴 mutex 重複上鎖（shutdown 死鎖）。
    // 鏈表可能超過 MAX_EVENTS 條，故以批次取出（每批鎖一次、鎖外 unref）。
    for (;;) {
        int n = 0;
        pthread_mutex_lock(&me->list_lock);
        full_conn_t *curr = me->conn_list_head;
        while (curr && n < MAX_EVENTS) {
            full_conn_t *next = curr->next;
            // 一律先解除鏈接（維持鏈表一致，不留孤兒節點）；
            // 已毒化（finalize 進行中）的 conn 不收集不 unref —— 它正由
            // finalize 流程擁有與釋放，重複 unref 會破壞帳目
            list_remove_locked(me, curr);
            if (curr->magic == CONN_MAGIC) {
                curr->closed = 1;
                collected[n++] = curr;
            }
            curr = next;
        }
        me->conn_list_head = curr;
        pthread_mutex_unlock(&me->list_lock);
        for (int i = 0; i < n; i++) conn_unref(collected[i]);
        if (n < MAX_EVENTS) break;
    }

    // 銷毀事件迴圈中途退出時尚未處理的垃圾
    for (int g = 0; g < garbage_count; g++) conn_unref(garbage_list[g]);

    close(me->epoll_fd);
    jni_detach_thread();
    return NULL;
}

static void handoff_to_worker(int client_fd, int target_fd) {
    // [item1] next_worker_idx 以 atomic 取用，避免多個 handshake 執行緒的資料競態
    int idx = atomic_fetch_add(&next_worker_idx, 1) % WORKER_COUNT;
    worker_t *w = &workers[idx];
    int removed_worker_ref = 0; // add_failed 回滾用（C 不允許 label 後直接宣告）

    // [Slot 修復] 從固定槽位表取一槽（本體永不釋放）；耗盡時拒絕連線。
    // g_conn_count 已由 handle_handshake 預佔，此路徑需歸還
    int slot = slot_acquire();
    if (slot < 0) {
        atomic_fetch_sub(&g_conn_count, 1);
        // [fd 重用競態] 同樣必須先 release_java_socket 再 close(target_fd)，否則
        // close 後 fd 編號可能被立即重用，導致 map.remove 誤刪並發建立的新連線。
        release_java_socket(target_fd); close(client_fd); close(target_fd); return;
    }
    full_conn_t *full = &g_slots[slot];

    // [H2 修復 v2] refs = 1（base ref），由 worker 的鏈表成員身分持有。
    // 不額外加 worker 參考、handoff 成功也不扣 —— 每條 conn 恰好 unref 一次，
    // 使 over-unref（refs→負數）在結構上不可能。
    atomic_store(&full->refs, 1);
    atomic_store(&full->registered, 0);
    atomic_store(&full->finalized, 0);
    full->widx = idx; // 提早設定：緩衝失敗路徑的 conn_finalize 會依此找對應 worker
    full->magic = CONN_MAGIC; // 標記為有效 conn（release 時毒化回 0）
    // [Slot 修復] epoll 事件識別碼：gen 已於 slot_acquire 遞增，幽靈事件必不符
    full->ep_u64 = ((uint64_t)full->gen << 32) | (uint32_t)slot;

    full->client_fd = client_fd;
    full->target_fd = target_fd;
    full->c2t_buf = malloc(BUFFER_SIZE);
    full->t2c_buf = malloc(BUFFER_SIZE);
    // [item3] 任一緩衝配置失敗即整條回收（conn_finalize 會關閉 fd、
    // 歸還槽位與連線數額度），避免 c2t_buf 成功但 t2c_buf 失敗時洩漏。
    // 此時 worker 尚未持有參考，unref 即真正釋放
    if (!full->c2t_buf || !full->t2c_buf) {
        conn_unref(full);
        return;
    }
    full->last_active = time(NULL);
    full->closed = 0;
    full->client_eof = 0;
    full->eof_since = 0;
    full->target_eof_since = 0;
    full->c2t_len = 0; full->c2t_off = 0;
    full->t2c_len = 0; full->t2c_off = 0;

    set_nonblocking(client_fd); set_nonblocking(target_fd);
    optimize_socket(client_fd); optimize_socket(target_fd);

    full->client_events = EPOLLIN | EPOLLRDHUP;
    full->target_events = EPOLLIN | EPOLLRDHUP;

    // [H2 修復] 先入鏈表，再做兩個 epoll ADD。
    // ADD 完成前 registered=0：worker 的事件處理與 timeout 掃描都會跳過
    // 未註冊的 conn，銷毀只會發生在下列兩個路徑之一，closed 旗標 + list_lock
    // 保證 list_remove 只執行一次
    pthread_mutex_lock(&w->list_lock);
    list_add_locked(w, full);
    pthread_mutex_unlock(&w->list_lock);

    struct epoll_event ev;
    ev.events = full->client_events; ev.data.u64 = full->ep_u64;
    if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) != 0) goto add_failed;
    // [CLOSE_WAIT 修復] target fd 額外帶 CONN_EV_TARGET_FLAG（bit31），
    // worker 事件迴圈以此區分 EPOLLRDHUP 是 client 或 target 的對端半關閉
    ev.events = full->target_events; ev.data.u64 = full->ep_u64 | CONN_EV_TARGET_FLAG;
    if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, target_fd, &ev) != 0) goto add_failed;
    // client_fd 已 ADD 成功的情境：close 時核心會自動把它從 epoll 移除，無需 DEL

    ghost_stamp_record(full->ep_u64, client_fd, target_fd, idx); // [幽靈清除器] 記錄戳記→fd 對應
    atomic_store(&full->registered, 1); // 事件從此可交付 worker
    // [H2 修復 v2] 成功路徑不再扣 ref：conn 的 base ref（=1）由 worker 持有，
    // 待 worker 日後垃圾回收時 unref（1→0）→ finalize
    return;

add_failed:
    // [H2 修復 v2] 回滾只扣一次：搶到 list_remove 的一方扣掉 base ref（1→0 finalize）。
    // 若 worker 已先收集（closed=1、已移除鏈表），worker 的 step-3 會負責扣，
    // handoff 此處完全不扣 —— 確保每條 conn 恰好 unref 一次
    pthread_mutex_lock(&w->list_lock);
    if (!full->closed) {
        full->closed = 1;
        list_remove_locked(w, full);
        removed_worker_ref = 1;
    }
    pthread_mutex_unlock(&w->list_lock);
    if (removed_worker_ref) conn_unref(full); // 扣 base ref（1→0）→ conn_finalize
}

// [IPv6 支援] 判斷兩個 sockaddr 的 IP 是否相同，v4 與 v4-mapped v6 視為相同。
// 雙棧 UDP socket 收到的 IPv4 來源會以 v4-mapped (::ffff:a.b.c.d) 形式呈現，
// 必須正規化後才能與控制連線的對端位址比對。位址正規化（v4 → ::ffff:a.b.c.d）
// 抽至 socks5_addr_normalize（純函式，host 可測），此處只做 sockaddr 解包。
static int same_ip(const struct sockaddr_storage *a, const struct sockaddr_storage *b) {
    const unsigned char *pa, *pb;
    int a6 = (a->ss_family == AF_INET6);
    int b6 = (b->ss_family == AF_INET6);
    pa = a6 ? (const unsigned char *)&((const struct sockaddr_in6 *)a)->sin6_addr
            : (const unsigned char *)&((const struct sockaddr_in *)a)->sin_addr;
    pb = b6 ? (const unsigned char *)&((const struct sockaddr_in6 *)b)->sin6_addr
            : (const unsigned char *)&((const struct sockaddr_in *)b)->sin_addr;
    unsigned char na[16], nb[16];
    socks5_addr_normalize(pa, a6, na);
    socks5_addr_normalize(pb, b6, nb);
    return memcmp(na, nb, 16) == 0;
}

// 由 socks5_udp_parse 解出的 (atyp, addr, port) 組回 sockaddr_storage，供 sendto 使用。
// atyp 由 parse 保證為 0x01/0x04；addr 為 4B（v4）或 16B（v6），port[2] 為原始網路序位元組。
static int build_sockaddr(unsigned char atyp, const unsigned char *addr, const unsigned char port[2],
                          struct sockaddr_storage *out, socklen_t *out_len) {
    if (atyp == SOCKS5_ATYP_IPV4) {
        struct sockaddr_in *s = (struct sockaddr_in *)out;
        memset(s, 0, sizeof(*s));
        s->sin_family = AF_INET;
        memcpy(&s->sin_addr, addr, 4);
        memcpy(&s->sin_port, port, 2);
        *out_len = sizeof(*s);
        return 0;
    }
    if (atyp == SOCKS5_ATYP_IPV6) {
        struct sockaddr_in6 *s = (struct sockaddr_in6 *)out;
        memset(s, 0, sizeof(*s));
        s->sin6_family = AF_INET6;
        memcpy(&s->sin6_addr, addr, 16);
        memcpy(&s->sin6_port, port, 2);
        *out_len = sizeof(*s);
        return 0;
    }
    return -1;
}

/* 回覆 BND 為 0.0.0.0:0 的標準回覆（CONNECT 成功/失敗、UDP pool 滿、UDP-in-TCP）。
 * 回覆封裝抽至 socks5_encode_reply（純函式，host 可測），此處只帶入全 0 位址。 */
static void send_zero_reply(int client_fd, unsigned char rep) {
    unsigned char zero4[4] = {0, 0, 0, 0};
    unsigned char zero2[2] = {0, 0};
    unsigned char out[10];
    int n = socks5_encode_reply(out, rep, 0, zero4, zero2);
    send(client_fd, out, n, MSG_NOSIGNAL);
}

// ================= UDP session 固定槽位 + epoll worker（P2） =================
// 原先每條 UDP session（0x03 標準 UDP ASSOCIATE / 0x04 UDP-in-TCP）都佔一條
// 專用執行緒跑 poll()（g_udp_pool，UDP_POOL_SIZE=96 條上限）。
// 5G-Proxy-Client 的 tun2socks 對每個 UDP socket（DNS / QUIC 443）都開一條
// session，96 條很快被佔滿 → REP=0x04 退避 =「伺服器拒絕服務」。
// 改為固定槽位表 + 單一 epoll worker：每條 session 的 2~3 個 fd 註冊進同一個
// epoll，世代編號防幽靈事件（純驗證抽至 udp_conn.h/c，host 可測），與 TCP 槽位
// 同一套「永不釋放 + 世代遞增」策略。槽位上限仍受 MAX_CONCURRENT_CONNS 額度控制。

// [UDP 擴展] 多 worker 分散 epoll 負載：tun2socks 會對每個 UDP socket（DNS/QUIC）
// 各開一條 session，全部塞進單一 epoll worker 會成單點瓶頸。round-robin 指派
// （udp_start_session 的 g_udp_next_worker）與建立/join 迴圈皆已參數化，拉高此值即生效。
#define UDP_WORKER_COUNT 4
#define UDP_SLOT_COUNT 1088

typedef struct udp_conn_t {
    uint32_t magic;
    int slot;
    uint32_t gen;
    uint64_t ep_u64;

    int client_fd;     // TCP：0x03 控制連線 / 0x04 資料連線
    int local_udp_fd;  // 0x03 的 LAN relay socket；0x04 為 -1
    int remote_udp_fd; // 5G UDP socket

    unsigned char *in_buf;   // 0x03 datagram 暫存 / 0x04 frame 重組緩衝
    unsigned char *out_buf;  // 0x04 出向 frame 緩衝（remote→client 可能 partial send）

    // 0x03：來源驗證（控制連線 peer）+ 回覆路由（最後一個合法 client 來源）
    struct sockaddr_storage peer_ss;
    socklen_t peer_ss_len;
    struct sockaddr_storage client_src_addr;
    socklen_t client_src_len;

    // 0x04 frame 重組狀態（非阻塞下需記錄部分讀取進度）
    unsigned char len_bytes[2];
    int len_got;      // 長度欄已讀 0/1/2 位元組
    int frame_expect; // 期望的 datagram 總長（讀到長度欄後設定）
    int frame_got;    // 已重組的 datagram 位元組數
    int out_len, out_off; // 0x04 出向 frame：總長 / 已送出

    uint32_t client_events; // 目前 client fd 的興趣遮罩（MOD 比對用）

    int closed;
    atomic_int refs;
    atomic_int finalized;
    atomic_int registered;
    time_t last_active;

    struct udp_conn_t *next, *prev;
    int widx;
} udp_conn_t;

typedef struct {
    int epoll_fd;
    pthread_t thread_id;
    udp_conn_t *conn_list_head;
    pthread_mutex_t list_lock;
} udp_worker_t;

static udp_worker_t udp_workers[UDP_WORKER_COUNT];

// 槽位表（靜態，永不釋放；世代遞增防幽靈事件）
static udp_conn_t g_udp_slots[UDP_SLOT_COUNT];
static atomic_int g_udp_slot_state[UDP_SLOT_COUNT];
static int g_udp_free_slots[UDP_SLOT_COUNT];
static int g_udp_free_slot_top = 0;
static pthread_mutex_t g_udp_slot_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_udp_next_worker = 0;

static atomic_llong g_udp_st_acquired = 0, g_udp_st_released = 0;
static atomic_llong g_udp_st_stale = 0, g_udp_st_bad_slot = 0;
static atomic_llong g_udp_st_exhausted = 0, g_udp_st_double_fin = 0;

static void udp_slots_init(void) {
    for (int i = 0; i < UDP_SLOT_COUNT; i++) {
        if (g_udp_slots[i].gen == 0) g_udp_slots[i].gen = 1;
        atomic_store(&g_udp_slot_state[i], 0);
        g_udp_free_slots[i] = i;
    }
    g_udp_free_slot_top = UDP_SLOT_COUNT;
}

static int udp_slot_acquire(void) {
    pthread_mutex_lock(&g_udp_slot_lock);
    if (g_udp_free_slot_top == 0) {
        pthread_mutex_unlock(&g_udp_slot_lock);
        atomic_fetch_add(&g_udp_st_exhausted, 1);
        return -1;
    }
    int idx = g_udp_free_slots[--g_udp_free_slot_top];
    uint32_t new_gen = ++g_udp_slots[idx].gen;
    pthread_mutex_unlock(&g_udp_slot_lock);

    memset(&g_udp_slots[idx], 0, sizeof(udp_conn_t));
    g_udp_slots[idx].gen = new_gen;
    g_udp_slots[idx].slot = idx;
    g_udp_slots[idx].client_fd = -1;
    g_udp_slots[idx].local_udp_fd = -1;
    g_udp_slots[idx].remote_udp_fd = -1;
    atomic_store(&g_udp_slot_state[idx], 1);
    atomic_fetch_add(&g_udp_st_acquired, 1);
    return idx;
}

static void udp_slot_release(int idx) {
    atomic_store(&g_udp_slot_state[idx], 0);
    pthread_mutex_lock(&g_udp_slot_lock);
    g_udp_free_slots[g_udp_free_slot_top++] = idx;
    pthread_mutex_unlock(&g_udp_slot_lock);
    atomic_fetch_add(&g_udp_st_released, 1);
}

static void udp_conn_unref(udp_conn_t *u);

static void udp_conn_finalize(udp_conn_t *u) {
    if (atomic_exchange(&u->finalized, 1) != 0) {
        atomic_fetch_add(&g_udp_st_double_fin, 1);
        return;
    }
    udp_worker_t *w = &udp_workers[u->widx];
    pthread_mutex_lock(&w->list_lock);
    if (u->next || u->prev || w->conn_list_head == u) {
        if (u->prev) u->prev->next = u->next; else w->conn_list_head = u->next;
        if (u->next) u->next->prev = u->prev;
        u->next = NULL; u->prev = NULL;
    }
    u->magic = 0;
    pthread_mutex_unlock(&w->list_lock);

    if (u->client_fd >= 0) { close(u->client_fd); u->client_fd = -1; }
    if (u->local_udp_fd >= 0) { close(u->local_udp_fd); u->local_udp_fd = -1; }
    if (u->remote_udp_fd >= 0) {
        // [fd 重用競態] 先 release 再 close：close 後 fd 編號可能被立即重用，
        // 導致 activeSockets.map.remove(fd) 誤刪並發建立的新 session。詳見
        // conn_finalize 的完整說明（dup 語意、先關 Java 端不影響 C 端副本）。
        release_java_socket(u->remote_udp_fd); // [根因修復 v2] 雙邊各關各的引用
        close(u->remote_udp_fd);
        u->remote_udp_fd = -1;
    }
    free(u->in_buf); u->in_buf = NULL;
    free(u->out_buf); u->out_buf = NULL;
    atomic_fetch_sub(&g_conn_count, 1);
    udp_slot_release(u->slot);
}

static void udp_conn_unref(udp_conn_t *u) {
    if (!u) return;
    if (atomic_fetch_sub(&u->refs, 1) == 1) udp_conn_finalize(u);
}

// 出向 frame 排空（0x04）。回傳 0 = 尚未排空（等 EPOLLOUT）/ 已排空；
// -1 = 不可回復的 send 錯誤，應關閉 session。
static int udp_flush_out(udp_conn_t *u) {
    while (u->out_off < u->out_len) {
        ssize_t n = send(u->client_fd, u->out_buf + u->out_off, u->out_len - u->out_off, MSG_NOSIGNAL);
        if (n > 0) { u->out_off += n; }
        else if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        else return -1;
    }
    u->out_len = 0; u->out_off = 0;
    return 0;
}

// 0x03：client 控制連線事件 → 偵測對端是否斷開。回傳 -1 = 關閉。
static int udp_ctrl_event(udp_conn_t *u, uint32_t ev) {
    if (ev & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) return -1;
    if (ev & EPOLLIN) {
        char tmp;
        if (recv(u->client_fd, &tmp, 1, MSG_PEEK) <= 0) return -1;
    }
    return 0;
}

// 0x03：local UDP → 5G remote。回傳 -1 = 關閉。
static int udp_local_event(udp_conn_t *u) {
    struct sockaddr_storage tmp; socklen_t tlen;
    for (;;) {
        tlen = sizeof(tmp);
        ssize_t r = recvfrom(u->local_udp_fd, u->in_buf, BUFFER_SIZE, 0, (struct sockaddr*)&tmp, &tlen);
        if (r < 0) { if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; return -1; }
        if (r <= 3) continue; // 連 RSV(2)+FRAG(1)+ATYP(1) 都湊不齊，丟棄
        unsigned char atyp; const unsigned char *addr; unsigned char port[2];
        int hlen = socks5_udp_parse(u->in_buf, (size_t)r, &atyp, &addr, port);
        if (hlen > 0 && !same_ip(&tmp, &u->peer_ss)) {
            continue; // 未授權來源：靜默丟棄（避免 log 洪水 DoS）
        }
        if (hlen > 0) {
            u->client_src_addr = tmp;
            u->client_src_len = tlen;
            struct sockaddr_storage dst_ss; socklen_t dlen;
            if (build_sockaddr(atyp, addr, port, &dst_ss, &dlen) == 0 && r > hlen) {
                if (sendto(u->remote_udp_fd, u->in_buf + hlen, r - hlen, 0, (struct sockaddr*)&dst_ss, dlen) < 0) {
                    LOGE("UDP relay: 5G sendto 失敗, errno=%d (%s)", errno, strerror(errno));
                }
            }
        }
    }
}

// remote 5G UDP → client（0x03 走 local UDP / 0x04 走 TCP frame）。回傳 -1 = 關閉。
static int udp_remote_event(udp_conn_t *u) {
    if (u->local_udp_fd >= 0) {
        // 0x03：5G → local UDP（SOCKS5 UDP header 封裝）
        struct sockaddr_in6 src6; socklen_t sl;
        for (;;) {
            sl = sizeof(src6);
            int off = 22; // 預留 IPv6 header
            ssize_t r = recvfrom(u->remote_udp_fd, u->in_buf + off, BUFFER_SIZE - off, 0, (struct sockaddr*)&src6, &sl);
            if (r < 0) { if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; return -1; }
            if (r == 0) continue;
            if (u->client_src_len > 0) {
                const unsigned char *addr; int is_v6;
                if (src6.sin6_family == AF_INET) { addr = (const unsigned char *)&((struct sockaddr_in *)&src6)->sin_addr; is_v6 = 0; }
                else if (IN6_IS_ADDR_V4MAPPED(&src6.sin6_addr)) { addr = (const unsigned char *)&src6.sin6_addr.s6_addr[12]; is_v6 = 0; }
                else { addr = (const unsigned char *)&src6.sin6_addr; is_v6 = 1; }
                int hlen = socks5_udp_encode(u->in_buf + off - (is_v6 ? 22 : 10), is_v6, addr, (const unsigned char *)&src6.sin6_port);
                int start = off - hlen;
                sendto(u->local_udp_fd, u->in_buf + start, r + hlen, 0, (struct sockaddr*)&u->client_src_addr, u->client_src_len);
            }
            // client_src_len == 0：尚無 client 來源，無回覆路由，丟棄
        }
    } else {
        // 0x04：5G → client TCP frame
        struct sockaddr_in6 src6; socklen_t sl;
        for (;;) {
            if (u->out_len > 0) return 0; // 出向尚未排空，等 EPOLLOUT
            sl = sizeof(src6);
            int off = 2 + 22; // 2-byte frame 長度欄 + 22-byte IPv6 header
            ssize_t r = recvfrom(u->remote_udp_fd, u->out_buf + off, BUFFER_SIZE - off, 0, (struct sockaddr*)&src6, &sl);
            if (r < 0) { if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; return -1; }
            if (r == 0) continue;
            const unsigned char *addr; int is_v6;
            if (src6.sin6_family == AF_INET) { addr = (const unsigned char *)&((struct sockaddr_in *)&src6)->sin_addr; is_v6 = 0; }
            else if (IN6_IS_ADDR_V4MAPPED(&src6.sin6_addr)) { addr = (const unsigned char *)&src6.sin6_addr.s6_addr[12]; is_v6 = 0; }
            else { addr = (const unsigned char *)&src6.sin6_addr; is_v6 = 1; }
            int hlen = socks5_udp_encode(u->out_buf + off - (is_v6 ? 22 : 10), is_v6, addr, (const unsigned char *)&src6.sin6_port);
            int start = off - hlen;
            int dlen = r + hlen;
            u->out_buf[start - 2] = (unsigned char)(dlen >> 8);
            u->out_buf[start - 1] = (unsigned char)(dlen & 0xFF);
            u->out_len = dlen + 2;
            u->out_off = 0;
            // [P2 修復] frame 建在 out_buf[start-2] 而非 offset 0，但 udp_flush_out 的
            // out_off 語意是「已送出位元組數」（初始應為 0）。先 memmove 到 offset 0，
            // 否則 send() 從 buffer 中段起算、少送 start-2 位元組（IPv4 少 12 bytes，
            // client 收到長度欄後永遠收不齊 body → recv 逾時）。
            if (start - 2 != 0) memmove(u->out_buf, u->out_buf + start - 2, (size_t)u->out_len);
            if (udp_flush_out(u) != 0) return -1;
            // 若已完整送出（out_len==0）循環讀下一 datagram；若 partial 則下輪 return
        }
    }
}

// 0x04：client TCP → 5G（frame 重組）。回傳 -1 = 關閉。
static int udp_client_data_event(udp_conn_t *u, uint32_t ev) {
    // 先排空出向（可能有 pending frame）
    if (u->out_len > 0 && udp_flush_out(u) != 0) return -1;
    if (!(ev & EPOLLIN)) return 0;

    for (;;) {
        if (u->len_got < 2) {
            while (u->len_got < 2) {
                ssize_t n = recv(u->client_fd, u->len_bytes + u->len_got, 2 - u->len_got, 0);
                if (n > 0) { u->len_got += n; }
                else if (n == 0) return -1;
                else if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
                else return -1;
            }
            int dlen = socks5_udp_tcp_frame_len(u->len_bytes, BUFFER_SIZE);
            if (dlen < 0) return -1; // 協定違規：裝不下表頭或爆緩衝
            u->frame_expect = dlen;
            u->frame_got = 0;
        }
        while (u->frame_got < u->frame_expect) {
            ssize_t n = recv(u->client_fd, u->in_buf + u->frame_got, u->frame_expect - u->frame_got, 0);
            if (n > 0) { u->frame_got += n; }
            else if (n == 0) return -1;
            else if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
            else return -1;
        }
        // 完整 datagram 到手 → 解析並轉發 5G
        unsigned char atyp; const unsigned char *addr; unsigned char port[2];
        int hlen = socks5_udp_parse(u->in_buf, (size_t)u->frame_expect, &atyp, &addr, port);
        if (hlen > 0) {
            struct sockaddr_storage dst_ss; socklen_t dlen;
            if (build_sockaddr(atyp, addr, port, &dst_ss, &dlen) == 0 && u->frame_expect > hlen) {
                if (sendto(u->remote_udp_fd, u->in_buf + hlen, u->frame_expect - hlen, 0, (struct sockaddr*)&dst_ss, dlen) < 0) {
                    LOGE("UDP-in-TCP: 5G sendto 失敗, errno=%d (%s)", errno, strerror(errno));
                }
            }
        }
        u->len_got = 0;
        u->frame_expect = 0;
        u->frame_got = 0;
        u->last_active = time(NULL);
        // 循環讀下一 frame
    }
}

static void udp_update_events(udp_worker_t *w, udp_conn_t *u) {
    if (u->closed) return;
    uint32_t c_ev = EPOLLIN | EPOLLRDHUP;
    if (u->local_udp_fd < 0 && u->out_len > 0) c_ev |= EPOLLOUT; // 0x04 出向未排空
    if (u->client_events != c_ev) {
        struct epoll_event ev;
        ev.events = c_ev; ev.data.u64 = u->ep_u64;
        if (epoll_ctl(w->epoll_fd, EPOLL_CTL_MOD, u->client_fd, &ev) == 0) u->client_events = c_ev;
    }
}

static void* udp_worker_loop(void* arg) {
    jni_attach_thread();
    udp_worker_t *me = (udp_worker_t*)arg;
    struct epoll_event events[MAX_EVENTS];
    udp_conn_t *garbage[MAX_EVENTS];
    int garbage_count = 0;
    time_t last_check = time(NULL);

    struct epoll_event stop_ev; stop_ev.events = EPOLLIN; stop_ev.data.u64 = 0;
    epoll_ctl(me->epoll_fd, EPOLL_CTL_ADD, g_shutdown_pipe[0], &stop_ev);

    while (atomic_load(&server_running)) {
        garbage_count = 0;
        int nfds = epoll_wait(me->epoll_fd, events, MAX_EVENTS, 2000);
        time_t now = time(NULL);

        for (int i = 0; i < nfds; i++) {
            if (events[i].data.u64 == 0) goto exit_udp_worker; // shutdown pipe

            uint32_t sidx, egen; udp_fd_role_t role;
            udp_event_decode(events[i].data.u64, &sidx, &egen, &role);
            if (udp_event_bad_slot((int)sidx, UDP_SLOT_COUNT)) {
                atomic_fetch_add(&g_udp_st_bad_slot, 1);
                continue;
            }
            udp_conn_t *u = &g_udp_slots[sidx];
            udp_event_disp_t disp = udp_event_check_slot(
                egen, u->gen, u->magic, atomic_load(&g_udp_slot_state[sidx]),
                u->closed, atomic_load(&u->registered));
            if (disp != UDP_EV_PROCESS) {
                if (disp == UDP_EV_STALE) atomic_fetch_add(&g_udp_st_stale, 1);
                continue;
            }
            u->last_active = now;
            uint32_t ev = events[i].events;
            int fatal = 0;
            switch (role) {
            case UDP_ROLE_CLIENT:
                fatal = (u->local_udp_fd >= 0) ? udp_ctrl_event(u, ev) : udp_client_data_event(u, ev);
                break;
            case UDP_ROLE_LOCAL:
                fatal = udp_local_event(u);
                break;
            case UDP_ROLE_REMOTE:
                fatal = udp_remote_event(u);
                break;
            }
            if (fatal) {
                pthread_mutex_lock(&me->list_lock);
                if (!u->closed) {
                    u->closed = 1;
                    if (u->prev) u->prev->next = u->next; else me->conn_list_head = u->next;
                    if (u->next) u->next->prev = u->prev;
                    u->next = NULL; u->prev = NULL;
                    if (garbage_count < MAX_EVENTS) garbage[garbage_count++] = u;
                }
                pthread_mutex_unlock(&me->list_lock);
            } else {
                udp_update_events(me, u);
            }
        }

        // 5 秒掃描閒置逾時（UDP_IDLE_TIMEOUT_SEC）
        if (now - last_check >= 5) {
            pthread_mutex_lock(&me->list_lock);
            udp_conn_t *cur = me->conn_list_head;
            while (cur) {
                udp_conn_t *next = cur->next;
                if (cur->magic == UDP_CONN_MAGIC && !cur->closed &&
                    atomic_load(&cur->registered) &&
                    udp_conn_idle_expired(now, cur->last_active, UDP_IDLE_TIMEOUT_SEC)) {
                    cur->closed = 1;
                    if (cur->prev) cur->prev->next = cur->next; else me->conn_list_head = cur->next;
                    if (cur->next) cur->next->prev = cur->prev;
                    cur->next = NULL; cur->prev = NULL;
                    if (garbage_count < MAX_EVENTS) garbage[garbage_count++] = cur;
                }
                cur = next;
            }
            pthread_mutex_unlock(&me->list_lock);
            last_check = now;
        }

        for (int i = 0; i < garbage_count; i++) udp_conn_unref(garbage[i]);
    }

exit_udp_worker:
    // 清空剩餘 session（分批取出、鎖外 unref，避免 finalize 重入 list_lock 死鎖）
    for (;;) {
        int n = 0;
        udp_conn_t *collected[MAX_EVENTS];
        pthread_mutex_lock(&me->list_lock);
        udp_conn_t *cur = me->conn_list_head;
        while (cur && n < MAX_EVENTS) {
            udp_conn_t *next = cur->next;
            if (cur->prev) cur->prev->next = cur->next; else me->conn_list_head = cur->next;
            if (cur->next) cur->next->prev = cur->prev;
            cur->next = NULL; cur->prev = NULL;
            if (cur->magic == UDP_CONN_MAGIC) { cur->closed = 1; collected[n++] = cur; }
            cur = next;
        }
        pthread_mutex_unlock(&me->list_lock);
        for (int i = 0; i < n; i++) udp_conn_unref(collected[i]);
        if (n < MAX_EVENTS) break;
    }
    // 銷毀事件迴圈中途退出時尚未處理的垃圾
    for (int g = 0; g < garbage_count; g++) udp_conn_unref(garbage[g]);

    close(me->epoll_fd);
    jni_detach_thread();
    return NULL;
}

// 由握手執行緒呼叫：建立 UDP session（額度 → 槽位 → socket → 回覆 → 註冊）。
// 所有失敗路徑都由 udp_conn_unref 的 finalize 負責關 fd 與歸還 g_conn_count 額度。
static void udp_start_session(int client_fd, int cmd) {
    if (atomic_fetch_add(&g_conn_count, 1) >= MAX_CONCURRENT_CONNS) {
        atomic_fetch_sub(&g_conn_count, 1);
        send_zero_reply(client_fd, 0x04);
        close(client_fd);
        return;
    }
    int slot = udp_slot_acquire();
    if (slot < 0) {
        atomic_fetch_sub(&g_conn_count, 1);
        send_zero_reply(client_fd, 0x04);
        close(client_fd);
        return;
    }
    udp_conn_t *u = &g_udp_slots[slot];
    u->magic = UDP_CONN_MAGIC;
    u->client_fd = client_fd;
    u->local_udp_fd = -1;
    u->remote_udp_fd = -1;
    u->widx = atomic_fetch_add(&g_udp_next_worker, 1) % UDP_WORKER_COUNT;
    u->ep_u64 = ((uint64_t)u->gen << 32) | (uint32_t)slot;
    atomic_store(&u->refs, 1);
    atomic_store(&u->finalized, 0);
    atomic_store(&u->registered, 0);
    u->last_active = time(NULL);
    u->in_buf = malloc(BUFFER_SIZE + 64);
    u->out_buf = malloc(BUFFER_SIZE + 64);
    if (!u->in_buf || !u->out_buf) { udp_conn_unref(u); return; }

    if (cmd == 0x03) {
        // 標準 UDP ASSOCIATE：建立雙棧 local relay socket
        int local_udp_fd = socket(AF_INET6, SOCK_DGRAM, 0);
        int local_is_v6 = (local_udp_fd >= 0);
        if (!local_is_v6) local_udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (local_udp_fd < 0) { udp_conn_unref(u); return; }
        if (local_is_v6) {
            int v6only = 0;
            setsockopt(local_udp_fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
            struct sockaddr_in6 a6 = {0};
            a6.sin6_family = AF_INET6; /* sin6_addr 全 0 = :: */
            bind(local_udp_fd, (struct sockaddr*)&a6, sizeof(a6));
        } else {
            struct sockaddr_in a4 = {0};
            a4.sin_family = AF_INET;
            a4.sin_addr.s_addr = htonl(INADDR_ANY);
            bind(local_udp_fd, (struct sockaddr*)&a4, sizeof(a4));
        }
        u->local_udp_fd = local_udp_fd;

        // BND.ADDR 取控制連線的本地（伺服器端）位址，BND.PORT 取 local relay port
        struct sockaddr_storage local_ss; socklen_t local_len = sizeof(local_ss);
        getsockname(local_udp_fd, (struct sockaddr*)&local_ss, &local_len);
        unsigned short p = (local_ss.ss_family == AF_INET6)
            ? ntohs(((struct sockaddr_in6*)&local_ss)->sin6_port)
            : ntohs(((struct sockaddr_in*)&local_ss)->sin_port);
        unsigned char resp[22];
        int resp_len = 0;
        unsigned char resp_port[2] = { (unsigned char)(p >> 8), (unsigned char)(p & 0xFF) };
        struct sockaddr_storage ss; socklen_t slen = sizeof(ss);
        if (getsockname(client_fd, (struct sockaddr*)&ss, &slen) == 0) {
            if (ss.ss_family == AF_INET) {
                struct sockaddr_in *s4 = (struct sockaddr_in *)&ss;
                resp_len = socks5_encode_reply(resp, 0x00, 0, (const unsigned char *)&s4->sin_addr, resp_port);
            } else if (ss.ss_family == AF_INET6) {
                struct sockaddr_in6 *s6 = (struct sockaddr_in6 *)&ss;
                if (IN6_IS_ADDR_V4MAPPED(&s6->sin6_addr)) {
                    resp_len = socks5_encode_reply(resp, 0x00, 0, (const unsigned char *)&s6->sin6_addr.s6_addr[12], resp_port);
                } else {
                    resp_len = socks5_encode_reply(resp, 0x00, 1, (const unsigned char *)&s6->sin6_addr, resp_port);
                }
            }
        }
        if (resp_len == 0) {
            unsigned char zero4[4] = {0,0,0,0};
            unsigned char zero2[2] = {0,0};
            resp_len = socks5_encode_reply(resp, 0x00, 0, zero4, zero2);
        }
        send(client_fd, resp, resp_len, MSG_NOSIGNAL);

        int remote_udp_fd = request_java_5g_socket("", 0, 1); // is_udp = 1
        if (remote_udp_fd < 0) { udp_conn_unref(u); return; }
        u->remote_udp_fd = remote_udp_fd;

        // 控制連線 peer（來源驗證用）
        u->peer_ss_len = sizeof(u->peer_ss);
        getpeername(client_fd, (struct sockaddr*)&u->peer_ss, &u->peer_ss_len);
    } else { // 0x04 UDP-in-TCP
        int remote_udp_fd = request_java_5g_socket("", 0, 1);
        if (remote_udp_fd < 0) {
            send_zero_reply(client_fd, 0x04);
            udp_conn_unref(u); // finalize 關 client_fd 並歸還額度
            return;
        }
        u->remote_udp_fd = remote_udp_fd;
        send_zero_reply(client_fd, 0x00);
    }

    // 非阻塞 + 註冊進 UDP worker epoll
    udp_worker_t *w = &udp_workers[u->widx];
    set_nonblocking(client_fd);
    if (u->local_udp_fd >= 0) set_nonblocking(u->local_udp_fd);
    set_nonblocking(u->remote_udp_fd);

    // 先入鏈，再註冊（註冊完成前 registered=0，worker 跳過）
    pthread_mutex_lock(&w->list_lock);
    u->next = w->conn_list_head;
    u->prev = NULL;
    if (w->conn_list_head) w->conn_list_head->prev = u;
    w->conn_list_head = u;
    pthread_mutex_unlock(&w->list_lock);

    struct epoll_event ev;
    u->client_events = EPOLLIN | EPOLLRDHUP;
    ev.events = u->client_events; ev.data.u64 = u->ep_u64;
    if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) != 0) goto reg_failed;
    if (u->local_udp_fd >= 0) {
        ev.events = EPOLLIN; ev.data.u64 = u->ep_u64 | UDP_EV_ROLE_LOCAL_FLAG;
        if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, u->local_udp_fd, &ev) != 0) goto reg_failed;
    }
    ev.events = EPOLLIN; ev.data.u64 = u->ep_u64 | UDP_EV_ROLE_REMOTE_FLAG;
    if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, u->remote_udp_fd, &ev) != 0) goto reg_failed;

    atomic_store(&u->registered, 1);
    return;

reg_failed:
    pthread_mutex_lock(&w->list_lock);
    if (!u->closed) {
        u->closed = 1;
        if (u->prev) u->prev->next = u->next; else w->conn_list_head = u->next;
        if (u->next) u->next->prev = u->prev;
        u->next = NULL; u->prev = NULL;
    }
    pthread_mutex_unlock(&w->list_lock);
    udp_conn_unref(u);
    return;
}


void socks5_server_set_auth(const char *user, const char *pass) {
    // 安全原則：必須「同時」設定帳號與密碼才啟用認證。
    // 任一欄位留空 = 不啟用認證，避免「空值放行任意輸入」的漏洞。
    // [item2] 以 mutex 保護，避免與 handshake 執行緒的讀取競態
    pthread_mutex_lock(&g_auth_lock);
    if (!user || !pass || !user[0] || !pass[0]) {
        g_auth_enabled = 0;
        g_auth_user[0] = '\0';
        g_auth_pass[0] = '\0';
    } else {
        strncpy(g_auth_user, user, sizeof(g_auth_user) - 1);
        strncpy(g_auth_pass, pass, sizeof(g_auth_pass) - 1);
        g_auth_user[sizeof(g_auth_user) - 1] = '\0';
        g_auth_pass[sizeof(g_auth_pass) - 1] = '\0';
        g_auth_enabled = 1;
    }
    pthread_mutex_unlock(&g_auth_lock);
}

// RFC 1929 username/password 子協商。成功回傳 0，失敗回傳 -1（連線將被關閉）
// [item2] 帳密以參數傳入（handshake 開始時的鎖內快照），避免讀取過程被修改
static int do_auth_check(int client_fd, unsigned char *buf, const char *auth_user, const char *auth_pass) {
    unsigned char ulen, plen;
    unsigned char user_buf[256];
    unsigned char pass_buf[256];

    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2 || buf[0] != 0x01) return -1;
    ulen = buf[1];
    if (ulen == 0 || ulen > 255) return -1;
    // 帳號與密碼各用獨立緩衝（ulen/plen 皆為單 byte 0..255），
    // 不再以 buf 上的隱式偏移（buf+2 / buf+258）共享同一塊記憶體
    if (recv(client_fd, user_buf, ulen, MSG_WAITALL) != ulen) return -1;
    if (recv(client_fd, buf, 1, MSG_WAITALL) != 1) return -1;
    plen = buf[0];
    if (plen > 255) return -1;
    // 允許 plen == 0：設定的密碼為空時，客戶端可不送密碼
    if (plen > 0 && recv(client_fd, pass_buf, plen, MSG_WAITALL) != plen) return -1;

    // 帳號與密碼都必須完全相符（啟用認證時兩欄皆非空，因此不再允許空值放行）。
    // 比對邏輯抽至 socks5_check_credentials（純函式，可 host 單元測試）
    int ok = socks5_check_credentials(user_buf, ulen, pass_buf, plen, auth_user, auth_pass);

    send(client_fd, ok ? "\x01\x00" : "\x01\x01", 2, MSG_NOSIGNAL);
    return ok ? 0 : -1;
}

// ================= 執行緒池（item10 改良版） =================
// 取代「每條連線 spawn 一條執行緒」的作法：
//  - 固定執行緒數 + 有界佇列 + 縮小 stack（128KB），burst 時以「丟棄連線」替代建立執行緒
//  - 只服務「短命」的 SOCKS5 握手（單次最多 5 秒 timeout）。
//  - [P2] 長命的 UDP session 已改由 udp_worker_loop（epoll，見上）處理，
//    不再佔用握手執行緒，也不再有 96 條 session 執行緒上限。
#define HANDSHAKE_POOL_SIZE 64
#define HANDSHAKE_QUEUE_SIZE 1024
#define HANDSHAKE_STACK_SIZE (128 * 1024)

typedef struct {
    int fd;
    int cmd; // 0 = 握手；0x03/0x04 = UDP session 型態
} pool_job_t;

typedef struct {
    pool_job_t *jobs;
    int cap, head, tail, count;
    pthread_mutex_t lock;
    pthread_cond_t not_empty;
    pthread_cond_t not_full;
    int stop;
    pthread_t *threads;
    int nthreads;
    void (*handler)(pool_job_t);
} job_pool_t;

static job_pool_t g_handshake_pool;

static void handle_handshake_fd(int client_fd);

// 佇列放入：滿時回傳 -1（呼叫者負責關閉 fd），不阻塞 listener
static int job_pool_enqueue(job_pool_t *p, int fd, int cmd) {
    pthread_mutex_lock(&p->lock);
    if (p->stop || p->count >= p->cap) {
        pthread_mutex_unlock(&p->lock);
        return -1;
    }
    p->jobs[p->tail].fd = fd;
    p->jobs[p->tail].cmd = cmd;
    p->tail = (p->tail + 1) % p->cap;
    p->count++;
    pthread_cond_signal(&p->not_empty);
    pthread_mutex_unlock(&p->lock);
    return 0;
}

static void* job_pool_worker(void* arg) {
    job_pool_t *p = (job_pool_t *)arg;
    // [item4] 執行緒永久綁定 JVM，取代每次 JNI 呼叫的 attach/detach
    jni_attach_thread();
    for (;;) {
        pool_job_t job;
        pthread_mutex_lock(&p->lock);
        while (p->count == 0 && !p->stop) {
            pthread_cond_wait(&p->not_empty, &p->lock);
        }
        if (p->count == 0) { // stop 且佇列已清空
            pthread_mutex_unlock(&p->lock);
            break;
        }
        job = p->jobs[p->head];
        p->head = (p->head + 1) % p->cap;
        p->count--;
        pthread_cond_signal(&p->not_full);
        pthread_mutex_unlock(&p->lock);

        p->handler(job);
    }
    jni_detach_thread();
    return NULL;
}

static void job_pool_init(job_pool_t *p, int nthreads, int cap, void (*handler)(pool_job_t)) {
    p->jobs = malloc(sizeof(pool_job_t) * cap);
    p->threads = malloc(sizeof(pthread_t) * nthreads);
    p->cap = cap; p->head = 0; p->tail = 0; p->count = 0; p->stop = 0;
    p->nthreads = nthreads;
    p->handler = handler;
    pthread_mutex_init(&p->lock, NULL);
    pthread_cond_init(&p->not_empty, NULL);
    pthread_cond_init(&p->not_full, NULL);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    // 可 join（shutdown 時等待全部結束）；stack 縮小省記憶體
    pthread_attr_setstacksize(&attr, HANDSHAKE_STACK_SIZE);
    for (int i = 0; i < nthreads; i++) {
        pthread_create(&p->threads[i], &attr, job_pool_worker, p);
    }
    pthread_attr_destroy(&attr);
}

static void job_pool_shutdown(job_pool_t *p) {
    pthread_mutex_lock(&p->lock);
    p->stop = 1;
    pthread_cond_broadcast(&p->not_empty);
    pthread_mutex_unlock(&p->lock);

    // 等待所有 worker 結束（進行中的握手最多 5 秒 timeout，
    // UDP session 會經由 shutdown pipe 立即退出）
    for (int i = 0; i < p->nthreads; i++) {
        pthread_join(p->threads[i], NULL);
    }
    pthread_mutex_destroy(&p->lock);
    pthread_cond_destroy(&p->not_empty);
    pthread_cond_destroy(&p->not_full);
    free(p->jobs);
    free(p->threads);
}

static void handle_handshake_job(pool_job_t job) {
    handle_handshake_fd(job.fd);
}

// 由握手池 worker 呼叫：握手完成後 TCP 轉交 epoll worker，
// UDP / UDP-in-TCP 則轉交專用 UDP session 池（避免長命 session 佔死握手執行緒）
static void handle_handshake_fd(int client_fd) {
    unsigned char buf[1024]; 
    struct timeval tv = {5, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    // [健壯性] 回覆寫入同樣設 5 秒超時：握手 socket 為 blocking，慢速客戶端
    // （只連不讀的 slowloris 式）會讓 send() 無限阻塞、佔死握手執行緒。
    setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2 || buf[0] != 0x05) goto err;
    int nmethods = buf[1];
    if (nmethods < 1 || nmethods > 255) goto err;
    if (recv(client_fd, buf, nmethods, MSG_WAITALL) != nmethods) goto err;

    // 認證方式選擇：開啟認證時只接受 0x02 (user/pass)，否則只接受 0x00 (no auth)
    // [item2] 在鎖內快照帳密，確保與 do_auth_check 使用同一份一致性資料
    int desired_method;
    char auth_user[256] = {0};
    char auth_pass[256] = {0};
    pthread_mutex_lock(&g_auth_lock);
    desired_method = g_auth_enabled ? 0x02 : 0x00;
    if (desired_method == 0x02) {
        memcpy(auth_user, g_auth_user, sizeof(auth_user) - 1);
        memcpy(auth_pass, g_auth_pass, sizeof(auth_pass) - 1);
    }
    pthread_mutex_unlock(&g_auth_lock);

    if (!socks5_method_offered(buf, nmethods, desired_method)) {
        send(client_fd, "\x05\xff", 2, MSG_NOSIGNAL);
        goto err;
    }
    if (desired_method == 0x02) {
        send(client_fd, "\x05\x02", 2, MSG_NOSIGNAL);
        if (do_auth_check(client_fd, buf, auth_user, auth_pass) != 0) goto err;
    } else {
        send(client_fd, "\x05\x00", 2, MSG_NOSIGNAL);
    }

    if (recv(client_fd, buf, 4, MSG_WAITALL) != 4) goto err;
    // [RFC 1928] VER=0x05、RSV=0x00、ATYP∈{0x01,0x03,0x04} 的合法性驗證
    // 抽至 socks5_validate_request_header（純函式，可 host 單元測試）；
    // 任何一項不符即協定違規、直接關閉（先前漏查 RSV = s2_connect 的 WARN 來源）。
    if (socks5_validate_request_header(buf) != 0) goto err;
    int cmd = buf[1];
    // ... 解析 host/port ...
    char host[256] = {0};
    int port = 0;
    int atyp = buf[3];
    int addr_len;
    if (atyp == 0x03) {
        if (recv(client_fd, buf, 1, MSG_WAITALL) != 1) goto err;
        addr_len = socks5_request_addr_len(atyp, buf[0]);
        if (addr_len <= 0) goto err; // domain 長度為 0 等協定違規
        if (recv(client_fd, buf, addr_len, MSG_WAITALL) != addr_len) goto err;
        memcpy(host, buf, addr_len);
        host[addr_len] = '\0';
    } else {
        addr_len = socks5_request_addr_len(atyp, 0);
        if (addr_len <= 0) goto err; // ATYP 不合法（validate 已擋，此為雙保險）
        if (recv(client_fd, buf, addr_len, MSG_WAITALL) != addr_len) goto err;
        if (atyp == 0x01) inet_ntop(AF_INET, buf, host, 256);
        else inet_ntop(AF_INET6, buf, host, 256);
    }
    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2) goto err;
    port = (buf[0] << 8) | buf[1];

    if (cmd == 0x01) { // TCP
        tv.tv_sec = 0; setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
        // [item3] CAS 預佔連線數額度（在耗時的 connect 之前），失敗立即歸還，
        // 避免多執行緒同時通過檢查導致超限
        if (atomic_fetch_add(&g_conn_count, 1) >= MAX_CONCURRENT_CONNS) {
            atomic_fetch_sub(&g_conn_count, 1);
            goto err;
        }

        int target_fd = request_java_5g_socket(host, port, 0);
        if (target_fd < 0) {
            atomic_fetch_sub(&g_conn_count, 1);
            send_zero_reply(client_fd, 0x04);
            goto err;
        }
        send_zero_reply(client_fd, 0x00);
        
        // 轉交給 Worker
        handoff_to_worker(client_fd, target_fd);
        
        return;
    } else if (socks5_is_supported_cmd(cmd)) { // UDP / UDP-in-TCP (0x03 / 0x04)
        // [P2] 直接在此握手執行緒建立 session 並 handoff 給 UDP epoll worker，
        // 不再經 g_udp_pool（96 執行緒上限）。
        udp_start_session(client_fd, cmd);
        return;
    }
err:
    close(client_fd);
    return;
}

typedef struct {
    int port;
} ListenerArgs;

// 為指定位址建立 TCP listener（AF_INET / AF_INET6），成功則加入 g_listener_fds
static void add_listener(int family, const void *addr, socklen_t addrlen, int port) {
    if (g_listener_count >= MAX_LISTENERS) return;
    int fd = socket(family, SOCK_STREAM, 0);
    if (fd < 0) return;
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    set_nonblocking(fd);
    if (family == AF_INET6) {
        int v6only = 1;
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
        struct sockaddr_in6 sa;
        memcpy(&sa, addr, sizeof(sa));
        sa.sin6_port = htons(port);
        if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { close(fd); return; }
    } else {
        struct sockaddr_in sa;
        memcpy(&sa, addr, sizeof(sa));
        sa.sin_port = htons(port);
        if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) { close(fd); return; }
    }
    if (listen(fd, 128) < 0) { close(fd); return; }
    g_listener_fds[g_listener_count++] = fd;
}

static void* listener_task(void* arg) {
    ListenerArgs *args = (ListenerArgs *)arg;
    int port = args->port;
    free(args);

    if (pipe(g_shutdown_pipe) < 0) return NULL;
    set_nonblocking(g_shutdown_pipe[0]); set_nonblocking(g_shutdown_pipe[1]);

    // [Slot 修復] 每次啟動重建空閒槽位堆疊（gen 延續遞增，跨重啟仍不混淆）
    slots_init();

    int worker_ep_fds[WORKER_COUNT];
    for (int i = 0; i < WORKER_COUNT; i++) {
        workers[i].epoll_fd = epoll_create1(0);
        worker_ep_fds[i] = workers[i].epoll_fd;
        workers[i].conn_list_head = NULL;
        pthread_mutex_init(&workers[i].list_lock, NULL); // [關鍵] 初始化鎖
        pthread_create(&workers[i].thread_id, NULL, worker_loop_safe, &workers[i]);
    }
    ghost_purge_set_epoll_fds(worker_ep_fds, WORKER_COUNT);

    // [執行緒池] 握手池（短命任務）
    job_pool_init(&g_handshake_pool, HANDSHAKE_POOL_SIZE, HANDSHAKE_QUEUE_SIZE, handle_handshake_job);
    // [P2] UDP session epoll worker（取代 g_udp_pool 的 96 執行緒）
    udp_slots_init();
    for (int i = 0; i < UDP_WORKER_COUNT; i++) {
        udp_workers[i].epoll_fd = epoll_create1(0);
        udp_workers[i].conn_list_head = NULL;
        pthread_mutex_init(&udp_workers[i].list_lock, NULL);
        pthread_create(&udp_workers[i].thread_id, NULL, udp_worker_loop, &udp_workers[i]);
    }

    g_listener_count = 0;

    // 本機 loopback（供健康檢查與本機使用，不對外暴露）
    struct sockaddr_in lo4 = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    add_listener(AF_INET, &lo4, sizeof(lo4), port);
    struct sockaddr_in6 lo6 = { .sin6_family = AF_INET6, .sin6_addr = IN6ADDR_LOOPBACK_INIT };
    add_listener(AF_INET6, &lo6, sizeof(lo6), port);

    // 只綁定 LAN 介面位址（Wi-Fi / 熱點 / USB 分享），絕不綁到行動網路
    for (int i = 0; i < g_bind_count && g_listener_count < MAX_LISTENERS; i++) {
        struct in_addr a4;
        struct in6_addr a6;
        if (inet_pton(AF_INET, g_bind_addrs[i], &a4) == 1) {
            struct sockaddr_in sa = { .sin_family = AF_INET, .sin_addr = a4 };
            add_listener(AF_INET, &sa, sizeof(sa), port);
        } else if (inet_pton(AF_INET6, g_bind_addrs[i], &a6) == 1) {
            struct sockaddr_in6 sa = { .sin6_family = AF_INET6, .sin6_addr = a6 };
            add_listener(AF_INET6, &sa, sizeof(sa), port);
        }
    }

    if (g_listener_count == 0) {
        LOGE("沒有可綁定的 LAN 位址，SOCKS5 伺服器無法啟動");
        atomic_store(&server_running, 0);
        if (g_shutdown_pipe[1] != -1) {
            char stop_sig = 1;
            write(g_shutdown_pipe[1], &stop_sig, 1);
        }
        return NULL;
    }

    struct pollfd pfds[MAX_LISTENERS + 1];
    while (atomic_load(&server_running)) {
        pfds[0].fd = g_shutdown_pipe[0]; pfds[0].events = POLLIN; pfds[0].revents = 0;
        int n = 1;
        for (int i = 0; i < g_listener_count; i++) {
            pfds[n].fd = g_listener_fds[i]; pfds[n].events = POLLIN; pfds[n].revents = 0;
            n++;
        }
        int res = poll(pfds, n, 1000);
        if (res <= 0) continue;
        if (pfds[0].revents) break; // shutdown pipe

        for (int i = 1; i < n; i++) {
            if (!(pfds[i].revents & (POLLIN | POLLERR | POLLHUP))) continue;
            for (;;) {
                int cfd = accept(pfds[i].fd, NULL, NULL);
                if (cfd < 0) break; // EAGAIN / 已關閉

                // [執行緒池] 放入握手池佇列；佇列滿（burst/攻擊）時丟棄連線，
                // 小睡避免 accept 迴圈空轉，不再建立無上限的執行緒
                if (job_pool_enqueue(&g_handshake_pool, cfd, 0) != 0) {
                    close(cfd);
                    usleep(10000);
                    continue;
                }
            }
        }
    }

    for (int i = 0; i < g_listener_count; i++) {
        if (g_listener_fds[i] >= 0) { close(g_listener_fds[i]); g_listener_fds[i] = -1; }
    }
    g_listener_count = 0;
    return NULL;
}

void socks5_server_set_bind_addrs(const char **addrs, int count) {
    g_bind_count = 0;
    for (int i = 0; i < count && g_bind_count < MAX_BIND_ADDRS; i++) {
        if (!addrs || !addrs[i] || !addrs[i][0]) continue;
        strncpy(g_bind_addrs[g_bind_count], addrs[i], INET6_ADDRSTRLEN - 1);
        g_bind_addrs[g_bind_count][INET6_ADDRSTRLEN - 1] = '\0';
        g_bind_count++;
    }
}

// [item6] 供 JNI 健康檢查直接讀取運行旗標，取代每 10 秒開真實 TCP 連線
int socks5_server_is_running(void) {
    return atomic_load(&server_running);
}

// [自檢/診斷] 供 JNI 讀取即時生命週期統計（App 內「複製診斷報告」用），
// 格式與 worker 的 30 秒 log 一致；server 未啟動時回傳 "not running"。
int socks5_server_get_stats(char *out, size_t out_len) {
    if (!out || out_len == 0) return -1;
    if (!atomic_load(&server_running)) {
        snprintf(out, out_len, "not running");
        return 0;
    }
    snprintf(out, out_len,
             "conns=%d acquired=%lld released=%lld stale_skip=%lld bad_slot=%lld exhausted=%lld purged=%lld",
             atomic_load(&g_conn_count),
             (long long)atomic_load(&g_st_acquired), (long long)atomic_load(&g_st_released),
             (long long)atomic_load(&g_st_stale_skip), (long long)atomic_load(&g_st_bad_slot),
             (long long)atomic_load(&g_st_exhausted), ghost_purge_get_count());
    return 0;
}

int socks5_server_main_dynamic(int port) {
    if (atomic_load(&server_running)) return -1;
    signal(SIGPIPE, SIG_IGN);
    atomic_store(&server_running, 1);
    atomic_store(&g_conn_count, 0);
    // [Slot 修復] 每個服務週期重置統計，logcat 軌跡對應當次執行
    atomic_store(&g_st_acquired, 0); atomic_store(&g_st_released, 0);
    atomic_store(&g_st_stale_skip, 0); atomic_store(&g_st_bad_slot, 0);
    atomic_store(&g_st_exhausted, 0); atomic_store(&g_st_double_fin, 0);
    ghost_purge_reset();
    ListenerArgs *args = malloc(sizeof(ListenerArgs));
    args->port = port;
    pthread_create(&listener_thread, NULL, listener_task, args);
    return 0;
}

void socks5_server_quit(void) {
    if (!atomic_load(&server_running)) return;
    atomic_store(&server_running, 0);

    // 關閉所有 listener，立即釋放綁定的埠號
    for (int i = 0; i < g_listener_count; i++) {
        if (g_listener_fds[i] >= 0) { shutdown(g_listener_fds[i], SHUT_RDWR); close(g_listener_fds[i]); g_listener_fds[i] = -1; }
    }
    g_listener_count = 0;

    if (g_shutdown_pipe[1] != -1) {
        char stop_sig = 1;
        // 寫足量喚醒所有 poller（listener + UDP worker + 64 握手 worker + 4 轉發 worker）
        for(int k=0; k<200; k++) write(g_shutdown_pipe[1], &stop_sig, 1);
    }
    pthread_join(listener_thread, NULL);
    // [執行緒池] 必須在銷毀 worker 的 list_lock 之前停止並排空執行緒池：
    // 池內的握手任務仍會呼叫 handoff_to_worker 去 lock worker 的 list_lock，
    // 若先 join/destroy worker 再排水，等同對已銷毀的 mutex 上鎖（UB）
    job_pool_shutdown(&g_handshake_pool);
    // [P2] UDP worker 取代 g_udp_pool；shutdown pipe 已喚醒它們，此處 join 收尾
    for (int i = 0; i < UDP_WORKER_COUNT; i++) {
        pthread_join(udp_workers[i].thread_id, NULL);
        pthread_mutex_destroy(&udp_workers[i].list_lock);
    }
    for (int i = 0; i < WORKER_COUNT; i++) {
        pthread_join(workers[i].thread_id, NULL);
        pthread_mutex_destroy(&workers[i].list_lock); // 銷毀鎖
    }
    // [Slot 修復] 槽位為靜態記憶體，關閉時無需釋放；下次啟動 slots_init() 重建
    LOGI("server stopped: acquired=%lld released=%lld stale_skip=%lld bad_slot=%lld exhausted=%lld double_fin=%lld",
         (long long)atomic_load(&g_st_acquired), (long long)atomic_load(&g_st_released),
         (long long)atomic_load(&g_st_stale_skip), (long long)atomic_load(&g_st_bad_slot),
         (long long)atomic_load(&g_st_exhausted), (long long)atomic_load(&g_st_double_fin));
    if (g_shutdown_pipe[0] != -1) { close(g_shutdown_pipe[0]); g_shutdown_pipe[0] = -1; }
    if (g_shutdown_pipe[1] != -1) { close(g_shutdown_pipe[1]); g_shutdown_pipe[1] = -1; }
}