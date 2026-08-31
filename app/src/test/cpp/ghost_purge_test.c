// ghost_purge_test.c
//
// 測試 ghost_purge.h 抽出的純決策函式：
//  - ghost_stuck_should_log: 對數階梯門檻 (32, 200, 1000, 5000, 25000, 75000...)
//  - ghost_stuck_should_purge: 清除門檻 (5000 及 5000 的倍數)
//
// 零 socket / epoll / pthread 依賴，可在 MinGW 與 Linux 直接執行。

#include <stdio.h>
#include <assert.h>
#include "ghost_purge.h"

static void test_stuck_should_log(void) {
    // 應該記錄的點
    assert(ghost_stuck_should_log(32));
    assert(ghost_stuck_should_log(200));
    assert(ghost_stuck_should_log(1000));
    assert(ghost_stuck_should_log(5000));
    assert(ghost_stuck_should_log(25000));
    assert(ghost_stuck_should_log(50000));   // 50000 % 50000 == 0 且 > 25000
    assert(ghost_stuck_should_log(100000));  // 100000 % 50000 == 0 且 > 25000

    // 不該記錄的點
    assert(!ghost_stuck_should_log(0));
    assert(!ghost_stuck_should_log(1));
    assert(!ghost_stuck_should_log(31));
    assert(!ghost_stuck_should_log(33));
    assert(!ghost_stuck_should_log(199));
    assert(!ghost_stuck_should_log(201));
    assert(!ghost_stuck_should_log(999));
    assert(!ghost_stuck_should_log(1001));
    assert(!ghost_stuck_should_log(4999));
    assert(!ghost_stuck_should_log(5001));
    assert(!ghost_stuck_should_log(24999));
    assert(!ghost_stuck_should_log(25001));
    assert(!ghost_stuck_should_log(75000));  // 75000 % 50000 = 25000 != 0
    assert(!ghost_stuck_should_log(125000)); // 125000 % 50000 = 25000 != 0

    printf("test_stuck_should_log: PASS\n");
}

static void test_stuck_should_purge(void) {
    // 應該清除的點（5000 及其倍數）
    assert(ghost_stuck_should_purge(5000));
    assert(ghost_stuck_should_purge(10000));
    assert(ghost_stuck_should_purge(15000));
    assert(ghost_stuck_should_purge(50000));

    // 不該清除的點
    assert(!ghost_stuck_should_purge(0));
    assert(!ghost_stuck_should_purge(1));
    assert(!ghost_stuck_should_purge(4999));
    assert(!ghost_stuck_should_purge(5001));
    assert(!ghost_stuck_should_purge(9999));
    assert(!ghost_stuck_should_purge(10001));

    printf("test_stuck_should_purge: PASS\n");
}

int main(void) {
    test_stuck_should_log();
    test_stuck_should_purge();
    printf("ghost_purge_test: ALL PASS\n");
    return 0;
}
