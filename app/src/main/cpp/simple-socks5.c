#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <dirent.h>
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
#define CONN_MAGIC 0x5EEDF00Du   // 存活 conn 的驗證值；release 時毒化為 0

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
static atomic_llong g_st_ghost_purged = 0; // 幽靈註冊被強制拔除次數

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

// [Slot 診斷] 卡死事件追蹤：同一 u64 被連續跳過 N 次代表某個 fd 卡在 epoll 裡
// （正常殘留事件只出現一兩次就消失）。用小雜湊表記錄，門檻到達時大聲記 log。
#define STUCK_TRACK_SLOTS 512
#define STUCK_TRACK_MASK (STUCK_TRACK_SLOTS - 1)
typedef struct { uint64_t u64; long long count; time_t last_log; int used; } stuck_ent_t;
static stuck_ent_t g_stuck[STUCK_TRACK_SLOTS];

// [幽靈清除器] 記錄每個註冊戳記對應的 fd 對。當某戳記的殘留事件超過門檻
//（代表其底層 fd 因任何未知路徑仍開著且永久就緒），直接對兩個記錄的 fd 做
// EPOLL_CTL_DEL —— 無論洩漏根源為何，熱迴圈都會被切斷（DEL 對已關閉/未註冊
// 的 fd 只是無害失敗）。環形覆寫，只需涵蓋近期註冊。
#define STAMP_RING_SIZE 4096
#define STAMP_RING_MASK (STAMP_RING_SIZE - 1)
typedef struct {
    uint64_t u64;
    int client_fd;
    int target_fd;
    int widx;
    unsigned long long c_ino, t_ino;   // 註冊當下的 inode（跨 dup 引用比對用）
} stamp_ent_t;
static stamp_ent_t g_stamp_ring[STAMP_RING_SIZE];
static atomic_int g_stamp_ring_pos = 0;
static pthread_mutex_t g_stamp_lock = PTHREAD_MUTEX_INITIALIZER;

static void stamp_record(uint64_t u64, int client_fd, int target_fd, int widx) {
    struct stat st;
    unsigned long long ci = 0, ti = 0;
    if (client_fd >= 0 && fstat(client_fd, &st) == 0) ci = ((unsigned long long)st.st_dev << 32) | st.st_ino;
    if (target_fd >= 0 && fstat(target_fd, &st) == 0) ti = ((unsigned long long)st.st_dev << 32) | st.st_ino;
    pthread_mutex_lock(&g_stamp_lock);
    int pos = atomic_fetch_add(&g_stamp_ring_pos, 1) & STAMP_RING_MASK;
    g_stamp_ring[pos].u64 = u64;
    g_stamp_ring[pos].client_fd = client_fd;
    g_stamp_ring[pos].target_fd = target_fd;
    g_stamp_ring[pos].widx = widx;
    g_stamp_ring[pos].c_ino = ci;
    g_stamp_ring[pos].t_ino = ti;
    pthread_mutex_unlock(&g_stamp_lock);
}

// [幽靈清除器 v2] epoll 註冊錨定在「開啟描述」而非 fd 編號：若 conn 的 C 端副本
// 已關閉但另一個 dup 引用（如 Java 端原生 fd）仍存活，DEL(舊編號) 會 ENOENT，
// 幽靈註冊繼續發事件。因此這裡改以「inode 反查」：掃描 /proc/self/fd 找出
// 仍指向同一描述的任何 fd 編號，對其執行 DEL —— 無論倖存引用是誰都拔得掉。
static int stamp_purge(uint64_t u64) {
    int found = 0;
    unsigned long long cino = 0, tino = 0;
    int cfd = -1, tfd = -1;
    struct epoll_event ev;
    pthread_mutex_lock(&g_stamp_lock);
    for (int i = 0; i < STAMP_RING_SIZE; i++) {
        if (g_stamp_ring[i].u64 == u64) {
            cino = g_stamp_ring[i].c_ino; tino = g_stamp_ring[i].t_ino;
            cfd = g_stamp_ring[i].client_fd; tfd = g_stamp_ring[i].target_fd;
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_stamp_lock);
    if (!found) return 0;

    // 直接編號先試（多數情況描述已死、編號未重用）
    for (int w = 0; w < WORKER_COUNT; w++) {
        if (cfd >= 0) epoll_ctl(workers[w].epoll_fd, EPOLL_CTL_DEL, cfd, &ev);
        if (tfd >= 0) epoll_ctl(workers[w].epoll_fd, EPOLL_CTL_DEL, tfd, &ev);
    }

    // [節流] inode 掃描成本高（opendir + 每個 fd fstat），全域每 200ms 限一次；
    // 幽靈事件在掃描前仍會被 gen/state 檢查擋下，只是延後拔除，正確性不受影響
    static atomic_llong g_last_scan_ms = 0;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    long long now_ms = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    long long prev_ms = atomic_load(&g_last_scan_ms);
    if (now_ms - prev_ms < 200) return 1;
    if (!atomic_compare_exchange_strong(&g_last_scan_ms, &prev_ms, now_ms)) return 1;

    // inode 反查：掃 /proc/self/fd，對每個數值 fd 做 fstat 比對 dev:ino
    DIR *d = opendir("/proc/self/fd");
    if (!d) return 1;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        int n = atoi(de->d_name);
        if (n < 0) continue;
        struct stat st2;
        if (fstat(n, &st2) != 0) continue;
        unsigned long long id = ((unsigned long long)st2.st_dev << 32) | st2.st_ino;
        if ((cino && id == cino) || (tino && id == tino)) {
            for (int w = 0; w < WORKER_COUNT; w++)
                epoll_ctl(workers[w].epoll_fd, EPOLL_CTL_DEL, n, &ev);
        }
    }
    closedir(d);
    return 1;
}

#define GHOST_PURGE_THRESHOLD 5000

static void stuck_track(int my_widx, int epoll_fd, uint64_t u64, const char *why,
                        uint32_t egen, uint32_t gennow, uint32_t magicv, int widx_v,
                        uint32_t ev, time_t now) {
    stuck_ent_t *e = &g_stuck[(u64 >> 13) & STUCK_TRACK_MASK];
    if (!e->used || e->u64 != u64) {
        // 槽被別的 u64 佔走或首次：直接重置（碰撞時統計略低估可接受）
        e->u64 = u64; e->count = 0; e->used = 1; e->last_log = 0;
    }
    e->count++;
    long long c = e->count;
    if ((c == 32 || c == 200 || c == 1000 || c == 5000 || c == 25000 ||
         (c > 25000 && (c % 50000) == 0)) && now - e->last_log >= 1) {
        e->last_log = now;
        LOGE("STUCK %s u64=%llx slot=%u gen_evt=%u gen_now=%u magic=%x widx=%d events=%x count=%lld",
             why, (unsigned long long)u64, (uint32_t)(u64 & 0xFFFFFFFFu),
             egen, gennow, magicv, widx_v, ev, c);
    }
    // [幽靈清除器] 同一戳記殘留過多 = 底層 fd 未被正常回收且永久就緒，
    // 主動從所有 worker 的 epoll 拔除，杜絕熱迴圈（fd 本體留給洩漏追蹤）
    if (c == GHOST_PURGE_THRESHOLD || (c > GHOST_PURGE_THRESHOLD && (c % GHOST_PURGE_THRESHOLD) == 0)) {
        int purged = stamp_purge(u64);
        if (purged) {
            atomic_fetch_add(&g_st_ghost_purged, 1);
            LOGE("GHOST PURGED u64=%llx slot=%u after %lld residual events",
                 (unsigned long long)u64, (uint32_t)(u64 & 0xFFFFFFFFu), c);
            e->count = 0; // 重置計數，若又出現代表另有來源
        }
    }
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

static void update_conn_events(int epoll_fd, full_conn_t *full) {
    if (full->closed) return;
    uint32_t c_ev = EPOLLRDHUP; 
    if (full->c2t_len == 0 && !full->client_eof) c_ev |= EPOLLIN;
    if (full->t2c_len > 0)  c_ev |= EPOLLOUT;

    uint32_t t_ev = EPOLLRDHUP;
    if (full->t2c_len == 0) t_ev |= EPOLLIN;
    if (full->c2t_len > 0)  t_ev |= EPOLLOUT;

    // [Slot 修復] 事件攜帶 (gen<<32|slot)，MOD 時必須重用同一識別碼
    if (full->client_events != c_ev) {
        struct epoll_event ev; ev.events = c_ev; ev.data.u64 = full->ep_u64;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, full->client_fd, &ev) == 0) full->client_events = c_ev;
    }
    if (full->target_events != t_ev) {
        struct epoll_event ev; ev.events = t_ev; ev.data.u64 = full->ep_u64;
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
        close(conn->target_fd);
        release_java_socket(conn->target_fd); // Java 端 socket.close() 收掉原生 fd
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
            uint32_t sidx = (uint32_t)(events[i].data.u64 & 0xFFFFFFFFu);
            uint32_t egen = (uint32_t)(events[i].data.u64 >> 32);
            if (sidx >= CONN_SLOT_COUNT) {
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
            if (!atomic_load(&g_slot_state[sidx])) {
                stuck_track(my_widx, me->epoll_fd, events[i].data.u64, "inactive", egen,
                            g_slots[sidx].gen, g_slots[sidx].magic, g_slots[sidx].widx,
                            events[i].events, now);
                atomic_fetch_add(&g_st_bad_slot, 1);
                continue;
            }
            if (egen != full->gen || full->magic != CONN_MAGIC) {
                // 幽靈事件：正常情況下 gen 遞增後舊事件全部失效。此計數 > 0 證明
                // 殘留事件確實存在且被正確防禦（舊設計下這正是 SIGSEGV 來源）
                stuck_track(my_widx, me->epoll_fd, events[i].data.u64, "stale", egen,
                            full->gen, full->magic, full->widx, events[i].events, now);
                atomic_fetch_add(&g_st_stale_skip, 1);
                continue;
            }
            if (full->widx != my_widx) continue;
            // [H2 修復] closed 或尚未完成 epoll 註冊的連線一律跳過：
            // handoff 的兩個 ADD 完成前，事件提前送達會與初始化競態
            if (full->closed || !atomic_load(&full->registered)) continue;

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
                    if (now - full->eof_since >= 2) fatal_error = 1;
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
                // 檢查是否超時且未被關閉
                // 1. 一般 idle 超時
                // 2. client 已半關閉且超過 2 秒 grace period（target 的 keep-alive
                //    連線不會發 FIN，事件迴圈不會再觸發，必須靠這裡回收，
                //    否則 CLOSE_WAIT 堆積消耗 fd）
                // magic 檢查：已毒化（finalize 進行中）的 conn 不收集，
                // finalize 的防禦移除會負責把它解開，避免雙重移除
                if (curr->magic == CONN_MAGIC && !curr->closed && atomic_load(&curr->registered) &&
                    (now - curr->last_active > IDLE_TIMEOUT_SEC ||
                    (curr->client_eof && now - curr->eof_since >= 2))) {
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
                     (long long)atomic_load(&g_st_exhausted), (long long)atomic_load(&g_st_ghost_purged));
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
        close(client_fd); close(target_fd); release_java_socket(target_fd); return;
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
    ev.events = full->target_events; ev.data.u64 = full->ep_u64;
    if (epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, target_fd, &ev) != 0) goto add_failed;
    // client_fd 已 ADD 成功的情境：close 時核心會自動把它從 epoll 移除，無需 DEL

    stamp_record(full->ep_u64, client_fd, target_fd, idx); // [幽靈清除器] 記錄戳記→fd 對應
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

static void handle_udp_session_full(int client_fd) {
    atomic_fetch_add(&g_conn_count, 1);

    int local_udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in local_addr = {0};
    local_addr.sin_family = AF_INET; 
    local_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    bind(local_udp_fd, (struct sockaddr*)&local_addr, sizeof(local_addr));
    
    socklen_t addr_len = sizeof(local_addr);
    getsockname(local_udp_fd, (struct sockaddr*)&local_addr, &addr_len);

    struct sockaddr_storage ss; socklen_t slen = sizeof(ss);
    unsigned char resp[10] = {0x05, 0x00, 0, 0x01, 0,0,0,0, 0,0};
    
    // 取得客戶端的原始目標地址 (為了回覆做準備)
    if (getsockname(client_fd, (struct sockaddr*)&ss, &slen) == 0) {
        if (ss.ss_family == AF_INET) {
            struct sockaddr_in *s4 = (struct sockaddr_in *)&ss; 
            memcpy(&resp[4], &s4->sin_addr, 4);
        } else if (ss.ss_family == AF_INET6) {
             struct sockaddr_in6 *s6 = (struct sockaddr_in6 *)&ss;
            if (IN6_IS_ADDR_V4MAPPED(&s6->sin6_addr)) 
                memcpy(&resp[4], &s6->sin6_addr.s6_addr[12], 4);
        }
    }
    
    // 填入 Server 端綁定的 UDP Port
    unsigned short p = ntohs(local_addr.sin_port); 
    resp[8] = p >> 8; 
    resp[9] = p & 0xFF;
    
    // 1. 發送 SOCKS5 UDP 握手成功回覆
    send(client_fd, resp, 10, MSG_NOSIGNAL);

    // 2. 請求 Java 層建立 5G UDP Socket
    int remote_udp_fd = request_java_5g_socket("", 0, 1); // is_udp = 1
    if (remote_udp_fd < 0) { 
        close(local_udp_fd); 
        close(client_fd); 
        atomic_fetch_sub(&g_conn_count, 1); 
        return; 
    }

    // 記錄控制連線的對端位址：UDP relay 只接受來自此用戶端的封包，
    // 且回覆一律送回此位址，避免被其他裝置竄改轉送目標
    struct sockaddr_storage peer_ss;
    socklen_t peer_ss_len = sizeof(peer_ss);
    struct sockaddr_in peer4 = {0};
    if (getpeername(client_fd, (struct sockaddr*)&peer_ss, &peer_ss_len) == 0) {
        if (peer_ss.ss_family == AF_INET) {
            memcpy(&peer4, &peer_ss, sizeof(peer4));
        } else if (peer_ss.ss_family == AF_INET6) {
            struct sockaddr_in6 *p6 = (struct sockaddr_in6 *)&peer_ss;
            if (IN6_IS_ADDR_V4MAPPED(&p6->sin6_addr)) {
                peer4.sin_family = AF_INET;
                memcpy(&peer4.sin_addr, &p6->sin6_addr.s6_addr[12], 4);
            }
        }
    }

    unsigned char *udp_buf = malloc(BUFFER_SIZE + 64);
    struct sockaddr_in client_src_addr = {0}; 
    socklen_t client_src_len = 0;
    
    // [關鍵修正] 使用 poll() 取代 select()/FD_SET：
    // select() 的 FD_SET 受 FD_SETSIZE(1024) 硬限制，當進程開啟的 fd 超過 1024
    // (例如大量 TCP 連線各佔 2 個 fd) 時，FD_SET 會觸發 bionic FORTIFY 檢查並
    // SIGABRT：'FORTIFY: FD_SET: file descriptor NNNN >= FD_SETSIZE 1024'。
    // poll() 以 pollfd 陣列管理，無此上限。
    struct pollfd fds[4];

    while (atomic_load(&server_running)) {
        fds[0].fd = g_shutdown_pipe[0]; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = client_fd;          fds[1].events = POLLIN; fds[1].revents = 0;
        fds[2].fd = local_udp_fd;       fds[2].events = POLLIN; fds[2].revents = 0;
        fds[3].fd = remote_udp_fd;      fds[3].events = POLLIN; fds[3].revents = 0;

        int res = poll(fds, 4, UDP_IDLE_TIMEOUT_SEC * 1000);
        if (res <= 0) break; // 超時或錯誤

        if (fds[0].revents) break; // shutdown pipe
        
        // 監測 TCP 控制通道是否斷開
        if (fds[1].revents) {
            if (recv(client_fd, udp_buf, 1, MSG_PEEK) <= 0) break;
        }

        // 收到 Client 的 UDP 封包 -> 轉發給 5G
        if (fds[2].revents) {
            struct sockaddr_in tmp; socklen_t tlen = sizeof(tmp);
            ssize_t r = recvfrom(local_udp_fd, udp_buf, BUFFER_SIZE, 0, (struct sockaddr*)&tmp, &tlen);
            if (r > 3 && udp_buf[2] == 0) { // SOCKS5 UDP Header 至少 4 bytes (RSV+FRAG+ATYP)，FRAG 須為 0
                // 來源驗證：只接受控制連線同來源 IP 的封包 (允許多個 UDP 來源 port)
                if (peer4.sin_family != AF_INET || tmp.sin_addr.s_addr != peer4.sin_addr.s_addr) {
                    LOGE("UDP relay: 拒絕未授權來源封包 %s:%u", inet_ntoa(tmp.sin_addr), ntohs(tmp.sin_port));
                } else {
                    client_src_addr = tmp; 
                    client_src_len = tlen;
                    
                    int hlen = 0; 
                    void* dst = NULL; 
                    socklen_t dlen = 0;
                    struct sockaddr_in d4; 
                    struct sockaddr_in6 d6;
                    
                    // 解析 SOCKS5 UDP Header
                    if (udp_buf[3] == 0x01) { // IPv4
                        hlen = 10; 
                        d4.sin_family=AF_INET; 
                        memcpy(&d4.sin_addr,&udp_buf[4],4); 
                        memcpy(&d4.sin_port,&udp_buf[8],2); 
                        dst=&d4; dlen=sizeof(d4);
                    } else if (udp_buf[3] == 0x04) { // IPv6
                        hlen = 22; 
                        d6.sin6_family=AF_INET6; 
                        memcpy(&d6.sin6_addr,&udp_buf[4],16); 
                        memcpy(&d6.sin6_port,&udp_buf[20],2); 
                        dst=&d6; dlen=sizeof(d6);
                    }
                    
                    if (dst && r > hlen) {
                        if (sendto(remote_udp_fd, udp_buf+hlen, r-hlen, 0, (struct sockaddr*)dst, dlen) < 0) {
                            LOGE("UDP relay: 5G sendto 失敗, errno=%d (%s)", errno, strerror(errno));
                        }
                    } else {
                        LOGE("UDP relay: 無法解析 SOCKS5 UDP Header (atyp=%u, len=%zd)", udp_buf[3], r);
                    }
                }
            } else if (r > 3) {
                LOGE("UDP relay: 收到 FRAG!=0 的 UDP 封包，已丟棄");
            }
        }
        
        // 收到 5G 的 UDP 封包 -> 封裝 Header 轉回給 Client
        if (fds[3].revents && client_src_len > 0) {
            struct sockaddr_in6 src6; socklen_t sl = sizeof(src6);
            int off = 22; // 預留足夠空間給 IPv6 Header

            // 直接讀到 buffer 後面，保留前面給 Header
            ssize_t r = recvfrom(remote_udp_fd, udp_buf + off, BUFFER_SIZE - off, 0, (struct sockaddr*)&src6, &sl);

            if (r > 0) {
                int start = 0;
                // 判斷來源地址類型;雙棧 socket 收到 IPv4 來源時會是 v4-mapped，一律輸出 IPv4 ATYP
                if (src6.sin6_family == AF_INET) {
                    struct sockaddr_in *s4 = (struct sockaddr_in *)&src6;
                    start = off - 10;
                    memset(&udp_buf[start], 0, 3); // RSV (2 bytes) + FRAG (1 byte)
                    udp_buf[start+3] = 0x01; // ATYP IPv4
                    memcpy(&udp_buf[start+4], &s4->sin_addr, 4);
                    memcpy(&udp_buf[start+8], &s4->sin_port, 2);
                } else if (IN6_IS_ADDR_V4MAPPED(&src6.sin6_addr)) {
                    start = off - 10;
                    memset(&udp_buf[start], 0, 3); // RSV + FRAG
                    udp_buf[start+3] = 0x01; // ATYP IPv4
                    memcpy(&udp_buf[start+4], &src6.sin6_addr.s6_addr[12], 4); // v4-mapped 尾 4 bytes
                    memcpy(&udp_buf[start+8], &src6.sin6_port, 2);
                } else {
                    start = off - 22;
                    memset(&udp_buf[start], 0, 3); // RSV + FRAG
                    udp_buf[start+3] = 0x04; // ATYP IPv6
                    memcpy(&udp_buf[start+4], &src6.sin6_addr, 16);
                    memcpy(&udp_buf[start+20], &src6.sin6_port, 2);
                }
                sendto(local_udp_fd, udp_buf + start, r + (off - start), 0, (struct sockaddr*)&client_src_addr, client_src_len);
            }
        }
    }
    
    if(udp_buf) free(udp_buf);
    close(remote_udp_fd); release_java_socket(remote_udp_fd); // [根因修復 v2] 雙邊各關各的引用
    close(local_udp_fd); 
    close(client_fd);
    atomic_fetch_sub(&g_conn_count, 1);
}

// UDP-in-TCP（自訂擴充 SOCKS5 指令 0x04）：
// 握手成功後，同一條 TCP 連線以 frame 承載 UDP datagram。
// frame = [2-byte 長度, network order] + [SOCKS5 UDP datagram]
//        datagram = RSV(2)=0 + FRAG(1)=0 + ATYP(0x01|0x04) + ADDR + PORT(2) + DATA
static void handle_udp_tcp_session(int client_fd) {
    atomic_fetch_add(&g_conn_count, 1);

    // 先建立 5G UDP socket，成功才回覆成功；失敗回 REP=0x04 讓 client 退回標準協定
    int remote_udp_fd = request_java_5g_socket("", 0, 1); // is_udp = 1
    unsigned char resp[10] = {0x05, 0x00, 0, 0x01, 0,0,0,0, 0,0};
    if (remote_udp_fd < 0) {
        resp[1] = 0x04; // host unreachable（此擴充指令失敗）
        send(client_fd, resp, 10, MSG_NOSIGNAL);
        close(client_fd);
        atomic_fetch_sub(&g_conn_count, 1);
        return;
    }
    send(client_fd, resp, 10, MSG_NOSIGNAL);

    // 取消 handshake 階段的 5 秒讀超時；blocking socket 由 poll 決定何時讀寫
    struct timeval tv = {UDP_IDLE_TIMEOUT_SEC, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    unsigned char *datagram = malloc(BUFFER_SIZE + 64);
    if (!datagram) {
        close(remote_udp_fd); release_java_socket(remote_udp_fd); // [根因修復 v2] 雙邊各關各的引用
        close(client_fd);
        atomic_fetch_sub(&g_conn_count, 1);
        return;
    }

    struct sockaddr_in6 src6; socklen_t sl = sizeof(src6);

    while (atomic_load(&server_running)) {
        struct pollfd fds[3];
        fds[0].fd = g_shutdown_pipe[0]; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = client_fd;          fds[1].events = POLLIN; fds[1].revents = 0;
        fds[2].fd = remote_udp_fd;      fds[2].events = POLLIN; fds[2].revents = 0;

        int res = poll(fds, 3, UDP_IDLE_TIMEOUT_SEC * 1000);
        if (res <= 0) break; // 超時或錯誤
        if (fds[0].revents) break; // shutdown pipe

        // client → 讀 frame → 5G UDP
        if (fds[1].revents) {
            unsigned char h[2];
            if (recv(client_fd, h, 2, MSG_WAITALL) != 2) break;
            int dlen = (h[0] << 8) | h[1];
            if (dlen < 4 || dlen > BUFFER_SIZE) break; // 協定違規
            if (recv(client_fd, datagram, dlen, MSG_WAITALL) != dlen) break;

            // 解析 SOCKS5 UDP datagram（RSV=0, FRAG=0）
            if (datagram[0] == 0 && datagram[1] == 0 && datagram[2] == 0) {
                int hlen = 0;
                void *dst = NULL;
                socklen_t dlen2 = 0;
                struct sockaddr_in d4;
                struct sockaddr_in6 d6;
                if (datagram[3] == 0x01 && dlen >= 10) { // IPv4
                    hlen = 10;
                    d4.sin_family = AF_INET;
                    memcpy(&d4.sin_addr, datagram + 4, 4);
                    memcpy(&d4.sin_port, datagram + 8, 2);
                    dst = &d4; dlen2 = sizeof(d4);
                } else if (datagram[3] == 0x04 && dlen >= 22) { // IPv6
                    hlen = 22;
                    d6.sin6_family = AF_INET6;
                    memcpy(&d6.sin6_addr, datagram + 4, 16);
                    memcpy(&d6.sin6_port, datagram + 20, 2);
                    dst = &d6; dlen2 = sizeof(d6);
                }
                if (dst && dlen > hlen) {
                    if (sendto(remote_udp_fd, datagram + hlen, dlen - hlen, 0,
                               (struct sockaddr*)dst, dlen2) < 0) {
                        LOGE("UDP-in-TCP: 5G sendto 失敗, errno=%d (%s)", errno, strerror(errno));
                    } else {
                        LOGI("UDP-in-TCP: client→5G frame dlen=%d hlen=%d payload=%d", dlen, hlen, dlen - hlen);
                    }
                }
            }
        }

        // 5G UDP → 封裝成 frame → client（單一 send 送出 長度欄 + datagram）
        if (fds[2].revents) {
            sl = sizeof(src6);
            // 前方預留 24 bytes：2-byte frame 長度欄 + 22-byte IPv6 SOCKS5 UDP 標頭。
            // 非 v4-mapped IPv6 來源時 start = off - 22 = 2，長度欄寫在 datagram[0..1]；
            // 若只預留 22，會寫到 datagram[-2] 腐蝕 heap（malloc metadata）
            int off = 2 + 22;
            ssize_t r = recvfrom(remote_udp_fd, datagram + off, BUFFER_SIZE - off, 0,
                                 (struct sockaddr*)&src6, &sl);
            if (r > 0) {
                LOGI("UDP-in-TCP: 5G→client datagram r=%zd", r);
                int start = 0;
                if (src6.sin6_family == AF_INET) {
                    struct sockaddr_in *s4 = (struct sockaddr_in *)&src6;
                    start = off - 10;
                    memset(&datagram[start], 0, 3); // RSV + FRAG
                    datagram[start+3] = 0x01;       // ATYP IPv4
                    memcpy(&datagram[start+4], &s4->sin_addr, 4);
                    memcpy(&datagram[start+8], &s4->sin_port, 2);
                } else if (IN6_IS_ADDR_V4MAPPED(&src6.sin6_addr)) {
                    start = off - 10;
                    memset(&datagram[start], 0, 3);
                    datagram[start+3] = 0x01;
                    memcpy(&datagram[start+4], &src6.sin6_addr.s6_addr[12], 4);
                    memcpy(&datagram[start+8], &src6.sin6_port, 2);
                } else {
                    start = off - 22;
                    memset(&datagram[start], 0, 3);
                    datagram[start+3] = 0x04;       // ATYP IPv6
                    memcpy(&datagram[start+4], &src6.sin6_addr, 16);
                    memcpy(&datagram[start+20], &src6.sin6_port, 2);
                }
                int dlen = r + (off - start);
                datagram[start - 2] = (unsigned char)(dlen >> 8);
                datagram[start - 1] = (unsigned char)(dlen & 0xFF);
                int total = dlen + 2;
                ssize_t tt = 0;
                while (tt < total) {
                    ssize_t n = send(client_fd, datagram + start - 2 + tt, total - tt, MSG_NOSIGNAL);
                    if (n > 0) tt += n;
                    else break;
                }
            }
        }
    }

    free(datagram);
    close(remote_udp_fd); release_java_socket(remote_udp_fd); // [根因修復 v2] 雙邊各關各的引用
    close(client_fd);
    atomic_fetch_sub(&g_conn_count, 1);
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

    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2 || buf[0] != 0x01) return -1;
    ulen = buf[1];
    if (ulen == 0 || ulen > 255) return -1;
    // 帳號使用獨立緩衝區，避免後續 PLEN/密碼讀取覆蓋帳號內容
    if (recv(client_fd, buf + 2, ulen, MSG_WAITALL) != ulen) return -1;
    if (recv(client_fd, buf, 1, MSG_WAITALL) != 1) return -1;
    plen = buf[0];
    if (plen > 255) return -1;
    // 密碼也使用獨立緩衝區（允許 plen == 0：設定的密碼為空時，客戶端可不送密碼）
    if (plen > 0 && recv(client_fd, buf + 258, plen, MSG_WAITALL) != plen) return -1;

    const unsigned char *user = buf + 2;
    const unsigned char *pass = buf + 258;

    // 帳號與密碼都必須完全相符（啟用認證時兩欄皆非空，因此不再允許空值放行）
    int user_ok = (ulen == (unsigned char)strlen(auth_user)) &&
                  memcmp(user, auth_user, ulen) == 0;
    int pass_ok = (plen == (unsigned char)strlen(auth_pass)) &&
                  memcmp(pass, auth_pass, plen) == 0;
    int ok = user_ok && pass_ok;

    send(client_fd, ok ? "\x01\x00" : "\x01\x01", 2, MSG_NOSIGNAL);
    return ok ? 0 : -1;
}

// ================= 執行緒池（item10 改良版） =================
// 取代「每條連線 spawn 一條執行緒」的作法：
//  - 固定執行緒數 + 有界佇列 + 縮小 stack（128KB），burst 時以「丟棄連線」替代建立執行緒
//  - 分兩個池：
//      g_handshake_pool：只服務「短命」的 SOCKS5 握手（單次最多 5 秒 timeout）
//      g_udp_pool：      服務「長命」的 UDP session（單次最多 UDP_IDLE_TIMEOUT_SEC）
//  - 關鍵原因：5G-Proxy-Client 的 tun2socks 對每個 UDP socket（DNS / QUIC 443）
//    都開一條 session，若與握手共用執行緒，64 條很快被 UDP session 佔死
//    → 所有 TCP 握手排隊逾時 =「伺服器拒絕服務」。
#define HANDSHAKE_POOL_SIZE 64
#define HANDSHAKE_QUEUE_SIZE 1024
#define UDP_POOL_SIZE 96
#define UDP_QUEUE_SIZE 512
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
static job_pool_t g_udp_pool;

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

static void handle_udp_job(pool_job_t job) {
    if (job.cmd == 0x04) {
        handle_udp_tcp_session(job.fd);
    } else {
        handle_udp_session_full(job.fd);
    }
}

// 由握手池 worker 呼叫：握手完成後 TCP 轉交 epoll worker，
// UDP / UDP-in-TCP 則轉交專用 UDP session 池（避免長命 session 佔死握手執行緒）
static void handle_handshake_fd(int client_fd) {
    unsigned char buf[1024]; 
    struct timeval tv = {5, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);

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

    int method_offered = 0;
    for (int i = 0; i < nmethods; i++) {
        if (buf[i] == desired_method) { method_offered = 1; break; }
    }
    if (!method_offered) {
        send(client_fd, "\x05\xff", 2, MSG_NOSIGNAL);
        goto err;
    }
    if (desired_method == 0x02) {
        send(client_fd, "\x05\x02", 2, MSG_NOSIGNAL);
        if (do_auth_check(client_fd, buf, auth_user, auth_pass) != 0) goto err;
    } else {
        send(client_fd, "\x05\x00", 2, MSG_NOSIGNAL);
    }

    if (recv(client_fd, buf, 4, MSG_WAITALL) != 4 || buf[0] != 0x05) goto err;
    int cmd = buf[1];
    // ... 解析 host/port ...
    char host[256] = {0};
    int port = 0;
    // (解析邏輯同原檔)
    int atyp = buf[3];
    if (atyp == 0x01) { recv(client_fd, buf, 4, MSG_WAITALL); inet_ntop(AF_INET, buf, host, 256); }
    else if (atyp == 0x03) { recv(client_fd, buf, 1, MSG_WAITALL); int len = buf[0]; recv(client_fd, buf, len, MSG_WAITALL); memcpy(host, buf, len); }
    else if (atyp == 0x04) { recv(client_fd, buf, 16, MSG_WAITALL); inet_ntop(AF_INET6, buf, host, 256); }
    else goto err;
    recv(client_fd, buf, 2, MSG_WAITALL); port = (buf[0] << 8) | buf[1];

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
            unsigned char fail[10] = {0x05, 0x04, 0, 0x01, 0,0,0,0, 0,0}; 
            send(client_fd, fail, 10, MSG_NOSIGNAL); 
            goto err;
        }
        unsigned char success[10] = {0x05, 0x00, 0, 0x01, 0,0,0,0, 0,0}; 
        send(client_fd, success, 10, MSG_NOSIGNAL);
        
        // 轉交給 Worker
        handoff_to_worker(client_fd, target_fd);
        
        return;
    } else if (cmd == 0x03 || cmd == 0x04) { // UDP / UDP-in-TCP
        // [UDP 池] 轉交專用 UDP session 池；池滿時回 REP=0x04 讓客戶端
        // 退避/關閉，不讓長命 UDP session 佔死握手執行緒池
        if (job_pool_enqueue(&g_udp_pool, client_fd, cmd) != 0) {
            unsigned char fail[10] = {0x05, 0x04, 0, 0x01, 0,0,0,0, 0,0};
            send(client_fd, fail, 10, MSG_NOSIGNAL);
            close(client_fd);
        }
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

    for (int i = 0; i < WORKER_COUNT; i++) {
        workers[i].epoll_fd = epoll_create1(0);
        workers[i].conn_list_head = NULL;
        pthread_mutex_init(&workers[i].list_lock, NULL); // [關鍵] 初始化鎖
        pthread_create(&workers[i].thread_id, NULL, worker_loop_safe, &workers[i]);
    }

    // [執行緒池] 握手池（短命任務）+ UDP session 池（長命任務）
    job_pool_init(&g_handshake_pool, HANDSHAKE_POOL_SIZE, HANDSHAKE_QUEUE_SIZE, handle_handshake_job);
    job_pool_init(&g_udp_pool, UDP_POOL_SIZE, UDP_QUEUE_SIZE, handle_udp_job);

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
             (long long)atomic_load(&g_st_exhausted), (long long)atomic_load(&g_st_ghost_purged));
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
    atomic_store(&g_st_ghost_purged, 0);
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
        // 寫足量喚醒所有 poller（listener + 96 UDP worker + 64 握手 worker + 4 轉發 worker）
        for(int k=0; k<200; k++) write(g_shutdown_pipe[1], &stop_sig, 1);
    }
    pthread_join(listener_thread, NULL);
    // [執行緒池] 必須在銷毀 worker 的 list_lock 之前停止並排空執行緒池：
    // 池內的握手任務仍會呼叫 handoff_to_worker 去 lock worker 的 list_lock，
    // 若先 join/destroy worker 再排水，等同對已銷毀的 mutex 上鎖（UB）
    job_pool_shutdown(&g_handshake_pool);
    job_pool_shutdown(&g_udp_pool);
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