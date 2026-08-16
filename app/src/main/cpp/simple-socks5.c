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
#include <time.h>

extern void jni_attach_thread();
extern void jni_detach_thread();
extern int request_java_5g_socket(const char* host, int port, int is_udp);
extern void release_java_socket(int fd);

#define LOG_TAG "SimpleSocks5"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define BUFFER_SIZE (256 * 1024)
#define MAX_EVENTS 512
#define WORKER_COUNT 4
#define MAX_CONCURRENT_CONNS 1000 
#define MAX_HANDSHAKE_THREADS 128
#define IDLE_TIMEOUT_SEC 300 

static atomic_int g_conn_count = 0;
static atomic_int g_handshake_count = 0;
static int g_shutdown_pipe[2] = {-1, -1};

static char g_auth_user[256];
static char g_auth_pass[256];
static int g_auth_enabled = 0;

typedef struct full_conn_t {
    int client_fd;
    int target_fd;
    unsigned char *c2t_buf; 
    unsigned char *t2c_buf; 
    ssize_t c2t_len, c2t_off;
    ssize_t t2c_len, t2c_off;
    int closed; // 標記是否已進入關閉流程
    int client_eof; // 客戶端已 FIN（半關閉）: 停止讀取但繼續轉發 target→client
    time_t eof_since; // client_eof 的起始時間（grace period 用）
    uint32_t client_events;
    uint32_t target_events;
    
    time_t last_active;
    struct full_conn_t *next;
    struct full_conn_t *prev;
} full_conn_t;

typedef struct {
    int epoll_fd;
    pthread_t thread_id;
    full_conn_t *conn_list_head; 
    pthread_mutex_t list_lock; // [關鍵修改] 保護鏈表結構的鎖
} worker_t;

static worker_t workers[WORKER_COUNT];
static volatile int server_running = 0;
static pthread_t listener_thread;
static int next_worker_idx = 0;

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

    if (full->client_events != c_ev) {
        struct epoll_event ev; ev.events = c_ev; ev.data.ptr = full;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, full->client_fd, &ev) == 0) full->client_events = c_ev;
    }
    if (full->target_events != t_ev) {
        struct epoll_event ev; ev.events = t_ev; ev.data.ptr = full;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, full->target_fd, &ev) == 0) full->target_events = t_ev;
    }
}

// 銷毀連線的輔助函數 (必須在無鎖狀態下調用，因為含 JNI)
static void destroy_connection(full_conn_t *conn) {
    if (!conn) return;
    
    // 關閉 FDs
    if (conn->client_fd >= 0) { close(conn->client_fd); conn->client_fd = -1; }
    if (conn->target_fd >= 0) { 
        release_java_socket(conn->target_fd); // JNI Call
        close(conn->target_fd); 
        conn->target_fd = -1; 
    }
    
    // 釋放記憶體
    if (conn->c2t_buf) { free(conn->c2t_buf); conn->c2t_buf = NULL; }
    if (conn->t2c_buf) { free(conn->t2c_buf); conn->t2c_buf = NULL; }
    
    free(conn);
    atomic_fetch_sub(&g_conn_count, 1);
}

static void* worker_loop_safe(void* arg) {
    jni_attach_thread();
    worker_t *me = (worker_t*)arg;
    struct epoll_event events[MAX_EVENTS];
    
    // 垃圾回收佇列 (用於在鎖外釋放資源)
    full_conn_t *garbage_list[MAX_EVENTS]; 
    int garbage_count = 0;

    time_t last_check_time = time(NULL);

    struct epoll_event stop_ev; stop_ev.events = EPOLLIN; stop_ev.data.ptr = NULL;
    epoll_ctl(me->epoll_fd, EPOLL_CTL_ADD, g_shutdown_pipe[0], &stop_ev);

    while (server_running) {
        garbage_count = 0;
        int nfds = epoll_wait(me->epoll_fd, events, MAX_EVENTS, 2000); // 縮短 wait 時間增加反應速度
        time_t now = time(NULL);

        // 1. 處理 I/O 事件
        for (int i = 0; i < nfds; i++) {
            if (events[i].data.ptr == NULL) goto exit_worker; // Shutdown pipe event
            
            full_conn_t *full = (full_conn_t *)events[i].data.ptr;
            if (full->closed) continue; // 已經標記刪除的忽略

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
                if (!curr->closed && (now - curr->last_active > IDLE_TIMEOUT_SEC ||
                    (curr->client_eof && now - curr->eof_since >= 2))) {
                    curr->closed = 1;
                    list_remove_locked(me, curr);
                    if (garbage_count < MAX_EVENTS) garbage_list[garbage_count++] = curr;
                    else {
                        // 桶滿：先鎖外銷毀已收集的垃圾騰出空間，再收下這條連線
                        pthread_mutex_unlock(&me->list_lock);
                        for (int g = 0; g < garbage_count; g++) destroy_connection(garbage_list[g]);
                        garbage_count = 0;
                        pthread_mutex_lock(&me->list_lock);
                        garbage_list[garbage_count++] = curr;
                    }
                }
                curr = next;
            }
            pthread_mutex_unlock(&me->list_lock);
            last_check_time = now;
        }

        // 3. 執行垃圾回收 (在鎖外，安全執行 JNI)
        for (int i = 0; i < garbage_count; i++) {
            destroy_connection(garbage_list[i]);
        }
    }

exit_worker:
    // 清理剩餘連線
    pthread_mutex_lock(&me->list_lock);
    full_conn_t *curr = me->conn_list_head;
    while(curr) {
        full_conn_t *next = curr->next;
        destroy_connection(curr);
        curr = next;
    }
    me->conn_list_head = NULL;
    pthread_mutex_unlock(&me->list_lock);

    // 銷毀事件迴圈中途退出時尚未處理的垃圾
    for (int g = 0; g < garbage_count; g++) destroy_connection(garbage_list[g]);

    close(me->epoll_fd);
    jni_detach_thread();
    return NULL;
}

static void handoff_to_worker(int client_fd, int target_fd) {
    int idx = next_worker_idx;
    next_worker_idx = (next_worker_idx + 1) % WORKER_COUNT;
    worker_t *w = &workers[idx];

    full_conn_t *full = calloc(1, sizeof(full_conn_t));
    if (!full) { close(client_fd); release_java_socket(target_fd); close(target_fd); return; }

    // [關鍵] 先完全初始化，再加入鏈表
    full->client_fd = client_fd; 
    full->target_fd = target_fd;
    full->c2t_buf = malloc(BUFFER_SIZE); 
    full->t2c_buf = malloc(BUFFER_SIZE);
    full->last_active = time(NULL);
    full->closed = 0;
    
    set_nonblocking(client_fd); set_nonblocking(target_fd);
    optimize_socket(client_fd); optimize_socket(target_fd);
    
    atomic_fetch_add(&g_conn_count, 1);

    full->client_events = EPOLLIN | EPOLLRDHUP;
    full->target_events = EPOLLIN | EPOLLRDHUP;

    // [關鍵] 加鎖修改鏈表 (這是 Listener 線程，與 Worker 線程競爭的地方)
    pthread_mutex_lock(&w->list_lock);
    list_add_locked(w, full);
    pthread_mutex_unlock(&w->list_lock);

    struct epoll_event ev;
    ev.events = full->client_events; ev.data.ptr = full;
    epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, client_fd, &ev);
    ev.events = full->target_events; ev.data.ptr = full;
    epoll_ctl(w->epoll_fd, EPOLL_CTL_ADD, target_fd, &ev);
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

    while (server_running) {
        fds[0].fd = g_shutdown_pipe[0]; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = client_fd;          fds[1].events = POLLIN; fds[1].revents = 0;
        fds[2].fd = local_udp_fd;       fds[2].events = POLLIN; fds[2].revents = 0;
        fds[3].fd = remote_udp_fd;      fds[3].events = POLLIN; fds[3].revents = 0;

        int res = poll(fds, 4, IDLE_TIMEOUT_SEC * 1000);
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
    release_java_socket(remote_udp_fd); 
    close(remote_udp_fd); 
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
    struct timeval tv = {IDLE_TIMEOUT_SEC, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    unsigned char *datagram = malloc(BUFFER_SIZE + 64);
    if (!datagram) {
        release_java_socket(remote_udp_fd);
        close(remote_udp_fd);
        close(client_fd);
        atomic_fetch_sub(&g_conn_count, 1);
        return;
    }

    struct sockaddr_in6 src6; socklen_t sl = sizeof(src6);

    while (server_running) {
        struct pollfd fds[3];
        fds[0].fd = g_shutdown_pipe[0]; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = client_fd;          fds[1].events = POLLIN; fds[1].revents = 0;
        fds[2].fd = remote_udp_fd;      fds[2].events = POLLIN; fds[2].revents = 0;

        int res = poll(fds, 3, IDLE_TIMEOUT_SEC * 1000);
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
            int off = 22; // 預留空間給 IPv6 Header
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
    release_java_socket(remote_udp_fd);
    close(remote_udp_fd);
    close(client_fd);
    atomic_fetch_sub(&g_conn_count, 1);
}

void socks5_server_set_auth(const char *user, const char *pass) {
    // 安全原則：必須「同時」設定帳號與密碼才啟用認證。
    // 任一欄位留空 = 不啟用認證，避免「空值放行任意輸入」的漏洞。
    if (!user || !pass || !user[0] || !pass[0]) {
        g_auth_enabled = 0;
        g_auth_user[0] = '\0';
        g_auth_pass[0] = '\0';
        return;
    }
    strncpy(g_auth_user, user, sizeof(g_auth_user) - 1);
    strncpy(g_auth_pass, pass, sizeof(g_auth_pass) - 1);
    g_auth_user[sizeof(g_auth_user) - 1] = '\0';
    g_auth_pass[sizeof(g_auth_pass) - 1] = '\0';
    g_auth_enabled = 1;
}

// RFC 1929 username/password 子協商。成功回傳 0，失敗回傳 -1（連線將被關閉）
static int do_auth_check(int client_fd, unsigned char *buf) {
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
    int user_ok = (ulen == (unsigned char)strlen(g_auth_user)) &&
                  memcmp(user, g_auth_user, ulen) == 0;
    int pass_ok = (plen == (unsigned char)strlen(g_auth_pass)) &&
                  memcmp(pass, g_auth_pass, plen) == 0;
    int ok = user_ok && pass_ok;

    send(client_fd, ok ? "\x01\x00" : "\x01\x01", 2, MSG_NOSIGNAL);
    return ok ? 0 : -1;
}

static void* handle_handshake(void* arg) {
    int client_fd = *(int*)arg; free(arg);
    unsigned char buf[1024]; 
    struct timeval tv = {5, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);

    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2 || buf[0] != 0x05) goto err;
    int nmethods = buf[1];
    if (nmethods < 1 || nmethods > 255) goto err;
    if (recv(client_fd, buf, nmethods, MSG_WAITALL) != nmethods) goto err;

    // 認證方式選擇：開啟認證時只接受 0x02 (user/pass)，否則只接受 0x00 (no auth)
    int desired_method = g_auth_enabled ? 0x02 : 0x00;
    int method_offered = 0;
    for (int i = 0; i < nmethods; i++) {
        if (buf[i] == desired_method) { method_offered = 1; break; }
    }
    if (!method_offered) {
        send(client_fd, "\x05\xff", 2, MSG_NOSIGNAL);
        goto err;
    }
    if (g_auth_enabled) {
        send(client_fd, "\x05\x02", 2, MSG_NOSIGNAL);
        if (do_auth_check(client_fd, buf) != 0) goto err;
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
        if (atomic_load(&g_conn_count) >= MAX_CONCURRENT_CONNS) goto err;

        int target_fd = request_java_5g_socket(host, port, 0);
        if (target_fd < 0) {
            unsigned char fail[10] = {0x05, 0x04, 0, 0x01, 0,0,0,0, 0,0}; 
            send(client_fd, fail, 10, MSG_NOSIGNAL); 
            goto err;
        }
        unsigned char success[10] = {0x05, 0x00, 0, 0x01, 0,0,0,0, 0,0}; 
        send(client_fd, success, 10, MSG_NOSIGNAL);
        
        // 轉交給 Worker
        handoff_to_worker(client_fd, target_fd);
        
        atomic_fetch_sub(&g_handshake_count, 1);
        return NULL;
    } else if (cmd == 0x03) { // UDP
        handle_udp_session_full(client_fd);
        atomic_fetch_sub(&g_handshake_count, 1);
        return NULL; 
    } else if (cmd == 0x04) { // UDP-in-TCP（自訂擴充）
        handle_udp_tcp_session(client_fd);
        atomic_fetch_sub(&g_handshake_count, 1);
        return NULL; 
    }
err:
    close(client_fd);
    atomic_fetch_sub(&g_handshake_count, 1);
    return NULL;
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

    for (int i = 0; i < WORKER_COUNT; i++) {
        workers[i].epoll_fd = epoll_create1(0);
        workers[i].conn_list_head = NULL;
        pthread_mutex_init(&workers[i].list_lock, NULL); // [關鍵] 初始化鎖
        pthread_create(&workers[i].thread_id, NULL, worker_loop_safe, &workers[i]);
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
        server_running = 0;
        if (g_shutdown_pipe[1] != -1) {
            char stop_sig = 1;
            write(g_shutdown_pipe[1], &stop_sig, 1);
        }
        return NULL;
    }

    struct pollfd pfds[MAX_LISTENERS + 1];
    while (server_running) {
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

                if (atomic_load(&g_handshake_count) >= MAX_HANDSHAKE_THREADS) {
                    close(cfd);
                    usleep(10000);
                    continue;
                }

                atomic_fetch_add(&g_handshake_count, 1);
                pthread_t t; int* p = malloc(sizeof(int)); *p = cfd;
                pthread_attr_t attr; pthread_attr_init(&attr);
                pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
                pthread_create(&t, &attr, handle_handshake, p); pthread_attr_destroy(&attr);
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

int socks5_server_main_dynamic(int port) {
    if (server_running) return -1;
    signal(SIGPIPE, SIG_IGN);
    server_running = 1;
    atomic_store(&g_conn_count, 0);
    atomic_store(&g_handshake_count, 0);
    ListenerArgs *args = malloc(sizeof(ListenerArgs));
    args->port = port;
    pthread_create(&listener_thread, NULL, listener_task, args);
    return 0;
}

void socks5_server_quit(void) {
    if (!server_running) return;
    server_running = 0;

    // 關閉所有 listener，立即釋放綁定的埠號
    for (int i = 0; i < g_listener_count; i++) {
        if (g_listener_fds[i] >= 0) { shutdown(g_listener_fds[i], SHUT_RDWR); close(g_listener_fds[i]); g_listener_fds[i] = -1; }
    }
    g_listener_count = 0;

    if (g_shutdown_pipe[1] != -1) {
        char stop_sig = 1;
        for(int k=0; k<10; k++) write(g_shutdown_pipe[1], &stop_sig, 1);
    }
    pthread_join(listener_thread, NULL);
    for (int i = 0; i < WORKER_COUNT; i++) {
        pthread_join(workers[i].thread_id, NULL);
        pthread_mutex_destroy(&workers[i].list_lock); // 銷毀鎖
    }
    if (g_shutdown_pipe[0] != -1) { close(g_shutdown_pipe[0]); g_shutdown_pipe[0] = -1; }
    if (g_shutdown_pipe[1] != -1) { close(g_shutdown_pipe[1]); g_shutdown_pipe[1] = -1; }
}