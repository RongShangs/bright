// ═══════════════════════════════════════════════════════════════
// 背屏守护进程：亮度校正 + 可选的主动息屏后唤醒
// 文件: app/src/main/cpp/watchdog.c
//
// 特点:
// - 平时4秒低频轮询；具体功耗需在目标设备实测
// - 检测到亮度被改后，立即恢复并1秒短间隔确认
// - 永不息屏开启时独立监测 display 1，不依赖亮度接管
// ═══════════════════════════════════════════════════════════════

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <errno.h>
#include <time.h>
#include <signal.h>
#include <sys/wait.h>
#include <poll.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/un.h>
#include "watchdog_logic.h"

#define BRIGHTNESS_PATH "/sys/class/backlight/panel1-backlight/brightness"
#define CONTROL_DIR "/data/adb/bright"
#define LOCK_PATH CONTROL_DIR "/daemon.lock"
#define SOCKET_PATH CONTROL_DIR "/control.sock"
#define MAX_PATH "/sys/class/backlight/panel1-backlight/max_brightness"
#define WAKE_LOCK_PATH  "/sys/power/wake_lock"
#define WAKE_UNLOCK_PATH "/sys/power/wake_unlock"
#define WAKE_LOCK_PREFIX "bright_rear_"

// 轮询间隔（秒）
#define NORMAL_INTERVAL   4   // 正常时低频
#define RECOVERY_INTERVAL 1   // 异常后短间隔确认
#define ACTIVE_INTERVAL   2   // 主动息屏后最多约2秒检测一次

static volatile sig_atomic_t stopping = 0;
static char wake_lock_name[48];
static int target = -1, keep_active = 0, active_healthy = 1, brightness_healthy = 1;

static long long now_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void on_stop(int signal_number) {
    (void) signal_number;
    stopping = 1;
}

static int write_power_node(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = write(fd, value, strlen(value));
    close(fd);
    return n == (ssize_t) strlen(value) ? 0 : -1;
}

// 使用有时限的 kernel wakelock。即使进程被强杀，锁也会自动过期。
static int renew_wake_lock(void) {
    char request[80];
    snprintf(request, sizeof(request), "%s 6000000000", wake_lock_name);
    return write_power_node(WAKE_LOCK_PATH, request);
}

static void release_wake_lock(void) {
    write_power_node(WAKE_UNLOCK_PATH, wake_lock_name);
}

// cmd display get-displays 输出每个逻辑屏的 DisplayInfo，其中包含独立的 state。
// 只认 display 1 的 OFF / DOZE / ON_SUSPEND；限时查询，防系统服务卡住亮度守护。
static int rear_display_asleep(void) {
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return -1;
    pid_t child = fork();
    if (child < 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }
    if (child == 0) {
        close(fds[0]);
        dup2(fds[1], STDOUT_FILENO);
        close(fds[1]);
        int null_fd = open("/dev/null", O_WRONLY);
        if (null_fd >= 0) {
            dup2(null_fd, STDERR_FILENO);
            close(null_fd);
        }
        execl("/system/bin/cmd", "cmd", "display", "get-displays", (char *) NULL);
        _exit(127);
    }

    close(fds[1]);
    char output[65536];
    size_t used = 0;
    int complete = 0, truncated = 0;
    struct timespec started, now;
    clock_gettime(CLOCK_MONOTONIC, &started);
    while (!stopping) {
        clock_gettime(CLOCK_MONOTONIC, &now);
        long elapsed_ms = (now.tv_sec - started.tv_sec) * 1000L +
                          (now.tv_nsec - started.tv_nsec) / 1000000L;
        int remaining_ms = 1200 - (int) elapsed_ms;
        if (remaining_ms <= 0) break;
        struct pollfd poll_fd = {.fd = fds[0], .events = POLLIN};
        if (poll(&poll_fd, 1, remaining_ms) <= 0) break;
        char chunk[1024];
        ssize_t n = read(fds[0], chunk, sizeof(chunk));
        if (n == 0) { complete = 1; break; }
        if (n < 0) break;
        if (used + (size_t) n < sizeof(output)) {
            memcpy(output + used, chunk, (size_t) n);
            used += (size_t) n;
        } else truncated = 1;
    }
    close(fds[0]);
    if (!complete) kill(child, SIGKILL);
    int status = 0;
    int waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (!complete || truncated || waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) return -1;

    output[used] = '\0';
    return parse_rear_state(output);
}

static int wake_rear_display(void) {
    if (stopping) return -1;
    pid_t child = fork();
    if (child == 0) {
        execl("/system/bin/input", "input", "-d", "1", "keyevent",
              "KEYCODE_WAKEUP", (char *) NULL);
        _exit(127);
    }
    if (child < 0) return -1;
    for (int i = 0; i < 80 && !stopping; ++i) {
        int status;
        if (waitpid(child, &status, WNOHANG) == child)
            return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
        usleep(25000);
    }
    kill(child, SIGKILL);
    while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
    return -1;
}

// 读取当前亮度，失败返回 -1
static int read_brightness(void) {
    int fd = open(BRIGHTNESS_PATH, O_RDONLY);
    if (fd < 0) return -1;
    char buf[16];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    char *end;
    errno = 0;
    long value = strtol(buf, &end, 10);
    while (*end == '\n' || *end == '\r' || *end == ' ') ++end;
    return errno || end == buf || *end || value < 0 || value > 65535 ? -1 : (int)value;
}

// 写入目标亮度: chmod 644 → write → chmod 444
static int write_brightness(int value) {
    if (stopping || chmod(BRIGHTNESS_PATH, 0644) != 0) return -1;
    int fd = open(BRIGHTNESS_PATH, O_WRONLY);
    if (fd < 0) { chmod(BRIGHTNESS_PATH, 0444); return -1; }
    char buf[16];
    int len = snprintf(buf, sizeof(buf), "%d", value);
    ssize_t written = write(fd, buf, len);
    close(fd);
    int locked = chmod(BRIGHTNESS_PATH, 0444);
    return written == len && locked == 0 ? 0 : -1;
}

static int private_directory(void) {
    struct stat st;
    return geteuid() == 0 && lstat(CONTROL_DIR, &st) == 0 &&
        S_ISDIR(st.st_mode) && st.st_uid == 0 && !(st.st_mode & 0077);
}

static int open_lock(void) {
    int fd = open(LOCK_PATH, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    struct stat st;
    if (fd < 0) return -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid || (st.st_mode & 0077)) {
        close(fd);
        return -1;
    }
    return fd;
}

static int read_line(int fd, char *buffer, size_t capacity) {
    size_t used = 0;
    long long deadline = now_ms() + 4500;
    while (used + 1 < capacity && !stopping) {
        int remaining = (int)(deadline - now_ms());
        if (remaining <= 0) return -1;
        struct pollfd p = {.fd = fd, .events = POLLIN};
        if (poll(&p, 1, remaining) <= 0) return -1;
        ssize_t n = read(fd, buffer + used, capacity - used - 1);
        if (n <= 0) return -1;
        used += (size_t)n;
        buffer[used] = 0;
        char *newline = strchr(buffer, '\n');
        if (newline) { *newline = 0; return 0; }
    }
    return -1;
}

static void reply_status(int fd) {
    char result[96];
    int n = snprintf(result, sizeof(result), "OK %d %d %d %d\n", target, keep_active, active_healthy, brightness_healthy);
    send(fd, result, (size_t)n, MSG_NOSIGNAL);
}

static int configure(int next_target, int next_active) {
    int acquired = next_active && (!keep_active || !active_healthy);
    if (acquired) {
        if (renew_wake_lock() != 0) return -1;
        int state = rear_display_asleep();
        if (state < 0 || (state == 1 && wake_rear_display() != 0)) {
            release_wake_lock();
            return -1;
        }
    }
    // Disabling always drops the wake lock, even if brightness restoration fails.
    if (!next_active) release_wake_lock();
    if (next_target >= 0 && write_brightness(next_target) != 0) {
        if (acquired) release_wake_lock();
        if (!next_active) keep_active = 0;
        brightness_healthy = 0;
        return -1;
    }
    if (next_target >= 0) {
        int first = read_brightness();
        usleep(50000);
        if (stopping || first != next_target || read_brightness() != next_target) {
            if (acquired) release_wake_lock();
            if (!next_active) keep_active = 0;
            brightness_healthy = 0;
            return -1;
        }
    }
    target = next_target;
    keep_active = next_active;
    active_healthy = brightness_healthy = 1;
    return 0;
}

static int serve(void) {
    int lock_fd = open_lock();
    if (lock_fd < 0) return 2;
    if (flock(lock_fd, LOCK_EX | LOCK_NB)) { close(lock_fd); return 3; }
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path, sizeof(address.sun_path), "%s", SOCKET_PATH);
    unlink(SOCKET_PATH);
    if (server < 0 || bind(server, (struct sockaddr *)&address, sizeof(address)) || listen(server, 4)) {
        if (server >= 0) close(server);
        close(lock_fd);
        return 2;
    }
    struct sigaction action = {.sa_handler = on_stop};
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    snprintf(wake_lock_name, sizeof(wake_lock_name), WAKE_LOCK_PREFIX "%d", getpid());
    int maximum = -1;
    FILE *max_file = fopen(MAX_PATH, "r");
    if (max_file) { if (fscanf(max_file, "%d", &maximum) != 1) maximum = -1; fclose(max_file); }
    long long next_brightness = now_ms(), next_display = now_ms(), idle_deadline = now_ms() + 10000;
    int failures = 0;
    while (!stopping) {
        long long now = now_ms();
        if (target < 0 && !keep_active && now >= idle_deadline) break;
        if (keep_active && now >= next_display) {
            int state = -1;
            if (renew_wake_lock() == 0) state = rear_display_asleep();
            if (state >= 0 && (state == 0 || wake_rear_display() == 0)) {
                failures = 0;
                active_healthy = 1;
                next_display = now_ms() + ACTIVE_INTERVAL * 1000;
            } else {
                active_healthy = 0;
                release_wake_lock();
                if (failures < 4) ++failures;
                next_display = now_ms() + (2000LL << failures);
            }
        }
        if (stopping) break;
        if (target >= 0 && now_ms() >= next_brightness) {
            int current = read_brightness();
            int interval = NORMAL_INTERVAL;
            brightness_healthy = current >= 0;
            if (current >= 0 && current != target) {
                usleep(50000);
                if (stopping) break;
                current = read_brightness();
                brightness_healthy = current >= 0;
                if (current >= 0 && current != target) {
                    brightness_healthy = write_brightness(target) == 0;
                    interval = RECOVERY_INTERVAL;
                }
            }
            next_brightness = now_ms() + interval * 1000;
        }
        if (stopping) break;
        long long due = target >= 0 ? next_brightness : (keep_active ? next_display : idle_deadline);
        if (keep_active && next_display < due) due = next_display;
        int delay = (int)(due - now_ms());
        struct pollfd p = {.fd = server, .events = POLLIN};
        if (poll(&p, 1, delay > 0 ? delay : 0) <= 0 || stopping) continue;
        int peer = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (peer < 0) continue;
        struct ucred credentials;
        socklen_t length = sizeof(credentials);
        if (getsockopt(peer, SOL_SOCKET, SO_PEERCRED, &credentials, &length) || credentials.uid != 0) {
            close(peer); continue;
        }
        char request[96], number[32], extra;
        int active, requested_target;
        if (read_line(peer, request, sizeof(request)) != 0) { close(peer); continue; }
        if (!strcmp(request, "STATUS")) reply_status(peer);
        else if (!strcmp(request, "STOP")) {
            stopping = 1;
            release_wake_lock();
            target = -1; keep_active = 0;
            reply_status(peer);
        } else if (sscanf(request, "SET %31s %d %c", number, &active, &extra) == 2 &&
                   (active == 0 || active == 1) && !parse_target(number, maximum, &requested_target) &&
                   configure(requested_target, active) == 0) {
            next_brightness = now_ms() + NORMAL_INTERVAL * 1000;
            next_display = now_ms() + ACTIVE_INTERVAL * 1000;
            failures = 0;
            reply_status(peer);
            if (target < 0 && !keep_active) stopping = 1;
        } else send(peer, "ERROR\n", 6, MSG_NOSIGNAL);
        close(peer);
    }
    release_wake_lock();
    close(server);
    unlink(SOCKET_PATH);
    close(lock_fd);
    return 0;
}

static int client(const char *request, int wait_for_exit) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path, sizeof(address.sun_path), "%s", SOCKET_PATH);
    if (fd < 0) return 2;
    if (connect(fd, (struct sockaddr *)&address, sizeof(address))) {
        close(fd);
        int lock_fd = open_lock();
        int absent = lock_fd >= 0 && flock(lock_fd, LOCK_EX | LOCK_NB) == 0;
        if (lock_fd >= 0) close(lock_fd);
        if (absent && (!strcmp(request, "STATUS\n") || !strcmp(request, "STOP\n"))) {
            puts("STOPPED"); return 0;
        }
        return 3;
    }
    if (send(fd, request, strlen(request), MSG_NOSIGNAL) != (ssize_t)strlen(request)) { close(fd); return 2; }
    char response[96];
    int success = read_line(fd, response, sizeof(response)) == 0 && !strncmp(response, "OK ", 3);
    close(fd);
    if (!success) return 2;
    if (wait_for_exit) {
        int lock_fd = open_lock();
        if (lock_fd < 0) return 2;
        int stopped = 0;
        for (int i = 0; i < 100; ++i) {
            if (!flock(lock_fd, LOCK_EX | LOCK_NB)) { stopped = 1; break; }
            usleep(10000);
        }
        close(lock_fd);
        if (!stopped) return 2;
    }
    puts(response);
    return 0;
}

int main(int argc, char *argv[]) {
    umask(0077);
    if (!private_directory() || argc < 2) return 2;
    if (argc == 2 && !strcmp(argv[1], "--serve")) return serve();
    if (argc == 2 && !strcmp(argv[1], "--status")) return client("STATUS\n", 0);
    if (argc == 2 && !strcmp(argv[1], "--stop")) return client("STOP\n", 1);
    if (argc == 4 && !strcmp(argv[1], "--set")) {
        int value;
        if (parse_target(argv[2], 65535, &value) || (strcmp(argv[3], "0") && strcmp(argv[3], "1"))) return 2;
        char request[64];
        snprintf(request, sizeof(request), "SET %d %s\n", value, argv[3]);
        return client(request, value == -1 && !strcmp(argv[3], "0"));
    }
    return 2;
}
