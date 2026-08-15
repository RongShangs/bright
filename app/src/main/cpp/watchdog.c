// ═══════════════════════════════════════════════════════════════
// 背屏亮度C守护进程（自适应轮询版）
// 文件: app/src/main/cpp/watchdog.c
//
// 特点:
// - 平时4秒低频轮询（CPU≈0%，极致省电）
// - 检测到亮度被改后，立即恢复并1秒短间隔确认
// - 内存仅128KB（静态编译）
// ═══════════════════════════════════════════════════════════════

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <errno.h>
#include <time.h>

#define BRIGHTNESS_PATH "/sys/class/backlight/panel1-backlight/brightness"
#define PID_FILE_PATH   "/data/local/tmp/bright_watchdog.pid"

// 轮询间隔（秒）
#define NORMAL_INTERVAL   4   // 正常时低频
#define RECOVERY_INTERVAL 1   // 异常后短间隔确认

// 读取当前亮度，失败返回 -1
static int read_brightness(void) {
    int fd = open(BRIGHTNESS_PATH, O_RDONLY);
    if (fd < 0) return -1;
    char buf[16];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    return atoi(buf);
}

// 写入目标亮度: chmod 644 → write → chmod 444
static int write_brightness(int value) {
    chmod(BRIGHTNESS_PATH, 0644);  // 解锁
    int fd = open(BRIGHTNESS_PATH, O_WRONLY);
    if (fd < 0) return -1;
    char buf[16];
    int len = snprintf(buf, sizeof(buf), "%d", value);
    ssize_t written = write(fd, buf, len);
    close(fd);
    chmod(BRIGHTNESS_PATH, 0444);  // 重新锁定
    return (written == len) ? 0 : -1;
}

int main(int argc, char *argv[]) {
    int target = 2000;
    if (argc > 1) target = atoi(argv[1]);

    // 写入PID文件（供stopWatchdog定位）
    int pid_fd = open(PID_FILE_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (pid_fd >= 0) {
        char pbuf[16];
        int n = snprintf(pbuf, sizeof(pbuf), "%d", getpid());
        write(pid_fd, pbuf, n);
        close(pid_fd);
    }

    int current;
    int interval = NORMAL_INTERVAL;

    while (1) {
        current = read_brightness();

        if (current != target && current >= 0) {
            // ── 检测到亮度被改（可能系统写0）──
            // 二次确认，防瞬时波动
            usleep(50000);
            current = read_brightness();

            if (current != target && current >= 0) {
                // 确认异常 → 立即恢复
                write_brightness(target);
                interval = RECOVERY_INTERVAL;  // 切短间隔确认稳定
            }
        } else {
            // 亮度正常 → 恢复低频
            interval = NORMAL_INTERVAL;
        }

        sleep(interval);
    }

    return 0;
}
