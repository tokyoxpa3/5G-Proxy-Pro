#include "conn_state.h"

int conn_event_bad_slot(int sidx, int slot_count) {
    return (sidx < 0 || sidx >= slot_count) ? 1 : 0;
}

conn_event_disp_t conn_event_check_slot(uint32_t egen, uint32_t gen_now,
                                        uint32_t magic, int slot_state,
                                        int widx, int my_widx,
                                        int closed, int registered) {
    if (!slot_state) return CONN_EV_INACTIVE;
    if (egen != gen_now || magic != CONN_MAGIC) return CONN_EV_STALE;
    if (widx != my_widx) return CONN_EV_WRONG_WORKER;
    if (closed || !registered) return CONN_EV_NOT_READY;
    return CONN_EV_PROCESS;
}

void conn_event_decode(uint64_t raw, uint32_t *sidx, uint32_t *egen, int *is_target) {
    *is_target = (raw & CONN_EV_TARGET_FLAG) ? 1 : 0;
    uint64_t key = raw & ~CONN_EV_TARGET_FLAG;
    *sidx = (uint32_t)(key & 0xFFFFFFFFu);
    *egen = (uint32_t)(key >> 32);
}
