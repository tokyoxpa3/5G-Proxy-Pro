#ifndef GHOST_PURGE_H
#define GHOST_PURGE_H

#include <stdint.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

// [幽靈清除器] 殘留事件（gen 不符的幽靈事件）累積到此門檻即觸發 EPOLL_CTL_DEL，
// 切斷「fd 永久就緒 → level-triggered 事件風暴」的熱迴圈。
#define GHOST_PURGE_THRESHOLD 5000

// 純函式：給定殘留事件計數，是否該記錄 STUCK log（對數式遞增，避免灌爆 logcat）。
// static inline 使 host 單元測試無需連結 epoll/pthread 即可直接驗證決策邏輯。
static inline int ghost_stuck_should_log(long long count) {
    return (count == 32 || count == 200 || count == 1000 || count == 5000 ||
            count == 25000 || (count > 25000 && (count % 50000) == 0));
}

// 純函式：給定殘留事件計數，是否該觸發幽靈清除（門檻及其倍數）。
static inline int ghost_stuck_should_purge(long long count) {
    return (count == GHOST_PURGE_THRESHOLD ||
            (count > GHOST_PURGE_THRESHOLD && (count % GHOST_PURGE_THRESHOLD) == 0));
}

// 設定可清除的 worker epoll fd 清單（stamp_purge 的 EPOLL_CTL_DEL 反查範圍）。
// 由 simple-socks5.c 在建立 worker 後呼叫一次。
void ghost_purge_set_epoll_fds(const int *fds, int n);

// 記錄一次註冊戳記（u64 事件識別碼 → client/target fd + inode 快照）。
void ghost_stamp_record(uint64_t u64, int client_fd, int target_fd, int widx);

// 卡死事件追蹤：同一戳記被連續跳過多次即大聲記 log；超過清除門檻則主動
// 對所有已註冊的 worker epoll 做 EPOLL_CTL_DEL，切斷幽靈熱迴圈。
void ghost_stuck_track(uint64_t u64, const char *why,
                       uint32_t egen, uint32_t gennow, uint32_t magicv, int widx_v,
                       uint32_t ev, time_t now);

// 讀取「幽靈清除次數」計數（供 stats 字串與診斷報表）。
long long ghost_purge_get_count(void);

// 重置幽靈清除計數（每次服務啟動時呼叫）。
void ghost_purge_reset(void);

#ifdef __cplusplus
}
#endif

#endif /* GHOST_PURGE_H */