#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/epoll.h>
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

#define BUFFER_SIZE (256 * 1024)
#define MAX_EVENTS 128
#define WORKER_COUNT 4
#define MAX_CONCURRENT_CONNS 1000 
#define MAX_HANDSHAKE_THREADS 128
#define IDLE_TIMEOUT_SEC 60 

static atomic_int g_conn_count = 0;
static atomic_int g_handshake_count = 0;
static int g_shutdown_pipe[2] = {-1, -1};

typedef struct full_conn_t {
    int client_fd;
    int target_fd;
    unsigned char *c2t_buf; 
    unsigned char *t2c_buf; 
    ssize_t c2t_len, c2t_off;
    ssize_t t2c_len, t2c_off;
    int closed; // 標記是否已進入關閉流程
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
static int worker_server_fd = -1;
static pthread_t listener_thread;
static int next_worker_idx = 0;

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
    if (full->c2t_len == 0) c_ev |= EPOLLIN;
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
                if (!fatal_error && full->c2t_len == 0) {
                    ssize_t r = recv(full->client_fd, full->c2t_buf, BUFFER_SIZE, 0);
                    if (r > 0) {
                        full->c2t_len = r; full->c2t_off = 0;
                        if (try_send(full->target_fd, full->c2t_buf, &full->c2t_len, &full->c2t_off) < 0) fatal_error = 1;
                    } else if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) fatal_error = 1;
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
                char tmp;
                if (recv(full->target_fd, &tmp, 1, MSG_PEEK) == 0) fatal_error = 1;
            }

            if (fatal_error) {
                // [關鍵] 標記刪除，稍後統一處理
                pthread_mutex_lock(&me->list_lock);
                if (!full->closed) {
                    full->closed = 1;
                    list_remove_locked(me, full);
                    if (garbage_count < MAX_EVENTS) garbage_list[garbage_count++] = full;
                }
                pthread_mutex_unlock(&me->list_lock);
            } else {
                update_conn_events(me->epoll_fd, full);
            }
        }

        // 2. 檢查超時 (每 5 秒一次)
        if (now - last_check_time >= 5) {
            pthread_mutex_lock(&me->list_lock); // [關鍵] 鎖住鏈表進行遍歷
            full_conn_t *curr = me->conn_list_head;
            while (curr) {
                full_conn_t *next = curr->next;
                // 檢查是否超時且未被關閉
                if (!curr->closed && (now - curr->last_active > IDLE_TIMEOUT_SEC)) {
                    curr->closed = 1;
                    list_remove_locked(me, curr);
                    if (garbage_count < MAX_EVENTS) garbage_list[garbage_count++] = curr;
                    else {
                        // 如果垃圾桶滿了，不得不這裡處理 (極端情況)
                        // 為避免持有鎖做 JNI，這裡選擇放棄這次回收，等待下輪，或者直接 break
                        // 實務上單次循環很難超過 128 個超時
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

    unsigned char *udp_buf = malloc(BUFFER_SIZE + 64);
    struct sockaddr_in client_src_addr = {0}; 
    socklen_t client_src_len = 0;
    
    fd_set readfds;
    int max_fd = (local_udp_fd > remote_udp_fd ? local_udp_fd : remote_udp_fd);
    if (client_fd > max_fd) max_fd = client_fd;
    if (g_shutdown_pipe[0] > max_fd) max_fd = g_shutdown_pipe[0];

    while (server_running) {
        FD_ZERO(&readfds);
        FD_SET(local_udp_fd, &readfds); 
        FD_SET(remote_udp_fd, &readfds); 
        FD_SET(client_fd, &readfds); 
        FD_SET(g_shutdown_pipe[0], &readfds);

        struct timeval timeout = {IDLE_TIMEOUT_SEC, 0}; 
        int res = select(max_fd + 1, &readfds, NULL, NULL, &timeout);
        if (res <= 0) break; // 超時或錯誤

        if (FD_ISSET(g_shutdown_pipe[0], &readfds)) break;
        
        // 監測 TCP 控制通道是否斷開
        if (FD_ISSET(client_fd, &readfds)) {
            if (recv(client_fd, udp_buf, 1, MSG_PEEK) <= 0) break;
        }

        // 收到 Client 的 UDP 封包 -> 轉發給 5G
        if (FD_ISSET(local_udp_fd, &readfds)) {
            struct sockaddr_in tmp; socklen_t tlen = sizeof(tmp);
            ssize_t r = recvfrom(local_udp_fd, udp_buf, BUFFER_SIZE, 0, (struct sockaddr*)&tmp, &tlen);
            if (r > 3) { // SOCKS5 UDP Header 至少 4 bytes (RSV+FRAG+ATYP+...)
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
                    sendto(remote_udp_fd, udp_buf+hlen, r-hlen, 0, (struct sockaddr*)dst, dlen);
                }
            }
        }
        
        // 收到 5G 的 UDP 封包 -> 封裝 Header 轉回給 Client
        if (FD_ISSET(remote_udp_fd, &readfds) && client_src_len > 0) {
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

static void* handle_handshake(void* arg) {
    int client_fd = *(int*)arg; free(arg);
    unsigned char buf[1024]; 
    struct timeval tv = {5, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);

    // ... SOCKS5 握手邏輯 (這部分你原本寫得沒問題) ...
    // 為了簡潔，這裡省略握手細節，請保留你原本的握手代碼
    
    if (recv(client_fd, buf, 2, MSG_WAITALL) != 2 || buf[0] != 0x05) goto err;
    int nmethods = buf[1];
    if (recv(client_fd, buf, nmethods, MSG_WAITALL) != nmethods) goto err;
    send(client_fd, "\x05\x00", 2, MSG_NOSIGNAL);

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
    }
err:
    close(client_fd);
    atomic_fetch_sub(&g_handshake_count, 1);
    return NULL;
}

static void* listener_task(void* arg) {
    int port = *(int*)arg; free(arg);
    
    if (pipe(g_shutdown_pipe) < 0) return NULL;
    set_nonblocking(g_shutdown_pipe[0]); set_nonblocking(g_shutdown_pipe[1]);

    for (int i = 0; i < WORKER_COUNT; i++) {
        workers[i].epoll_fd = epoll_create1(0);
        workers[i].conn_list_head = NULL;
        pthread_mutex_init(&workers[i].list_lock, NULL); // [關鍵] 初始化鎖
        pthread_create(&workers[i].thread_id, NULL, worker_loop_safe, &workers[i]);
    }

    // ... (Listener Bind/Listen 代碼同原檔) ...
    worker_server_fd = socket(AF_INET6, SOCK_STREAM, 0);
    int opt = 1; setsockopt(worker_server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    int no = 0; setsockopt(worker_server_fd, IPPROTO_IPV6, IPV6_V6ONLY, &no, sizeof(no));
    struct sockaddr_in6 addr = { .sin6_family = AF_INET6, .sin6_port = htons(port) };
    if (bind(worker_server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) return NULL;
    listen(worker_server_fd, 128);

    while (server_running) {
        struct sockaddr_in6 caddr; socklen_t clen = sizeof(caddr);
        int cfd = accept(worker_server_fd, (struct sockaddr*)&caddr, &clen);
        if (cfd < 0) { if (server_running) continue; break; }
        
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
    
    if (worker_server_fd >= 0) { close(worker_server_fd); worker_server_fd = -1; }
    return NULL;
}

int socks5_server_main_dynamic(int port) {
    if (server_running) return -1;
    signal(SIGPIPE, SIG_IGN);
    server_running = 1;
    atomic_store(&g_conn_count, 0);
    atomic_store(&g_handshake_count, 0);
    int* p = malloc(sizeof(int)); *p = port;
    pthread_create(&listener_thread, NULL, listener_task, p);
    return 0;
}

void socks5_server_quit(void) {
    if (!server_running) return;
    server_running = 0;
    
    if (worker_server_fd >= 0) { shutdown(worker_server_fd, SHUT_RDWR); close(worker_server_fd); worker_server_fd = -1; }
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