#include "udp_conn.h"

int udp_event_bad_slot(int sidx, int slot_count) {
    return (sidx < 0 || sidx >= slot_count) ? 1 : 0;
}

void udp_event_decode(uint64_t raw, uint32_t *sidx, uint32_t *egen, udp_fd_role_t *role) {
    uint64_t role_bits = raw & UDP_EV_ROLE_MASK;
    if (role_bits == UDP_EV_ROLE_LOCAL_FLAG) {
        *role = UDP_ROLE_LOCAL;
    } else if (role_bits == UDP_EV_ROLE_REMOTE_FLAG) {
        *role = UDP_ROLE_REMOTE;
    } else {
        *role = UDP_ROLE_CLIENT;
    }
    uint64_t key = raw & ~UDP_EV_ROLE_MASK;
    *sidx = (uint32_t)(key & 0xFFFFFFFFu);
    *egen = (uint32_t)(key >> 32);
}

udp_event_disp_t udp_event_check_slot(uint32_t egen, uint32_t gen_now,
                                      uint32_t magic, int slot_state,
                                      int closed, int registered) {
    if (!slot_state) return UDP_EV_INACTIVE;
    if (egen != gen_now || magic != UDP_CONN_MAGIC) return UDP_EV_STALE;
    if (closed || !registered) return UDP_EV_NOT_READY;
    return UDP_EV_PROCESS;
}

int udp_conn_idle_expired(time_t now, time_t last_active, time_t timeout_sec) {
    if (last_active == 0) return 0;
    return (now - last_active > timeout_sec) ? 1 : 0;
}
