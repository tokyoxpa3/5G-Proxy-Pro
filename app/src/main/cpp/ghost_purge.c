#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <dirent.h>
#include <stdatomic.h>
#include <stdint.h>
#include <time.h>
#include <android/log.h>

#include "ghost_purge.h"

#define LOG_TAG "GhostPurge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

// 可清除的 worker epoll fd（由 ghost_purge_set_epoll_fds 註冊）
#define MAX_PURGE_EPOLL_FDS 8
static int g_purge_epoll_fds[MAX_PURGE_EPOLL_FDS];
static int g_purge_epoll_count = 0;

static atomic_llong g_ghost_purged = 0;

void ghost_purge_set_epoll_fds(const int *fds, int n) {
    if (n > MAX_PURGE_EPOLL_FDS) n = MAX_PURGE_EPOLL_FDS;
    for (int i = 0; i < n; i++) g_purge_epoll_fds[i] = fds[i];
    g_purge_epoll_count = n;
}

long long ghost_purge_get_count(void) {
    return (long long)atomic_load(&g_ghost_purged);
}

void ghost_purge_reset(void) {
    atomic_store(&g_ghost_purged, 0);
}

void ghost_stamp_record(uint64_t u64, int client_fd, int target_fd, int widx) {
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
    for (int w = 0; w < g_purge_epoll_count; w++) {
        if (cfd >= 0) epoll_ctl(g_purge_epoll_fds[w], EPOLL_CTL_DEL, cfd, &ev);
        if (tfd >= 0) epoll_ctl(g_purge_epoll_fds[w], EPOLL_CTL_DEL, tfd, &ev);
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
            for (int w = 0; w < g_purge_epoll_count; w++)
                epoll_ctl(g_purge_epoll_fds[w], EPOLL_CTL_DEL, n, &ev);
        }
    }
    closedir(d);
    return 1;
}

void ghost_stuck_track(uint64_t u64, const char *why,
                       uint32_t egen, uint32_t gennow, uint32_t magicv, int widx_v,
                       uint32_t ev, time_t now) {
    stuck_ent_t *e = &g_stuck[(u64 >> 13) & STUCK_TRACK_MASK];
    if (!e->used || e->u64 != u64) {
        // 槽被別的 u64 佔走或首次：直接重置（碰撞時統計略低估可接受）
        e->u64 = u64; e->count = 0; e->used = 1; e->last_log = 0;
    }
    e->count++;
    long long c = e->count;
    if (ghost_stuck_should_log(c) && now - e->last_log >= 1) {
        e->last_log = now;
        LOGE("STUCK %s u64=%llx slot=%u gen_evt=%u gen_now=%u magic=%x widx=%d events=%x count=%lld",
             why, (unsigned long long)u64, (uint32_t)(u64 & 0xFFFFFFFFu),
             egen, gennow, magicv, widx_v, ev, c);
    }
    // [幽靈清除器] 同一戳記殘留過多 = 底層 fd 未被正常回收且永久就緒，
    // 主動從所有 worker 的 epoll 拔除，杜絕熱迴圈（fd 本體留給洩漏追蹤）
    if (ghost_stuck_should_purge(c)) {
        int purged = stamp_purge(u64);
        if (purged) {
            atomic_fetch_add(&g_ghost_purged, 1);
            LOGE("GHOST PURGED u64=%llx slot=%u after %lld residual events",
                 (unsigned long long)u64, (uint32_t)(u64 & 0xFFFFFFFFu), c);
            e->count = 0; // 重置計數，若又出現代表另有來源
        }
    }
}
