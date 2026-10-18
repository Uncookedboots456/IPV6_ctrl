#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef PROC_CONF_DIR
#define PROC_CONF_DIR "/proc/sys/net/ipv6/conf"
#endif
#ifndef STATE_DIR
#define STATE_DIR "/data/adb/ipv6ctrl-system"
#endif
#ifndef BOOT_ID_PATH
#define BOOT_ID_PATH "/proc/sys/kernel/random/boot_id"
#endif
#ifndef MODULE_DIR
#define MODULE_DIR "/data/adb/modules/ipv6_ctrl"
#endif
#ifndef SHELL_PATH
#define SHELL_PATH "/system/bin/sh"
#endif

static volatile sig_atomic_t running = 1;
static void stop(int signo) { (void)signo; running = 0; }

static bool first_line(const char *path, char *out, size_t size)
{
    FILE *file = fopen(path, "re");
    if (!file) return false;
    bool ok = fgets(out, (int)size, file) != NULL;
    fclose(file);
    if (ok) out[strcspn(out, "\r\n")] = '\0';
    return ok;
}

static bool matches(const char *path, const char *expected)
{
    char value[128];
    return first_line(path, value, sizeof(value)) && strcmp(value, expected) == 0;
}

static bool enabled_module(void)
{
    return matches(MODULE_DIR "/protocol", "ipv6ctrl-managed-v2") &&
           access(MODULE_DIR "/disable", F_OK) != 0 && access(MODULE_DIR "/remove", F_OK) != 0;
}

/* Reuse PR #1's read-before-write, readback and per-interface sweep. */
static int read_state(const char *path)
{
    char value;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t count;
    do { count = read(fd, &value, 1); } while (count < 0 && errno == EINTR);
    close(fd);
    return count == 1 && (value == '0' || value == '1') ? value - '0' : -1;
}

static int disable_node(const char *path)
{
    if (read_state(path) == 1) return 0;
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t count;
    do { count = write(fd, "1", 1); } while (count < 0 && errno == EINTR);
    close(fd);
    return count == 1 && read_state(path) == 1 ? 0 : -1;
}

static int sweep(void)
{
    int failed = disable_node(PROC_CONF_DIR "/all/disable_ipv6") != 0;
    if (disable_node(PROC_CONF_DIR "/default/disable_ipv6") != 0) failed = 1;
    DIR *dir = opendir(PROC_CONF_DIR);
    if (!dir) return -1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.' || strcmp(entry->d_name, "all") == 0 ||
            strcmp(entry->d_name, "default") == 0) continue;
        char path[512];
        int length = snprintf(path, sizeof(path), "%s/%s/disable_ipv6", PROC_CONF_DIR, entry->d_name);
        if (length <= 0 || (size_t)length >= sizeof(path) || disable_node(path) != 0) failed = 1;
    }
    closedir(dir);
    return failed ? -1 : 0;
}

static int lock_file(const char *path, bool create)
{
    return open(path, O_RDWR | O_CLOEXEC | (create ? O_CREAT : 0), 0600);
}

static void report(const char *boot, const char *result)
{
    FILE *file = fopen(STATE_DIR "/daemon.status", "we");
    if (!file) return;
    fprintf(file, "%s\n%s\n", boot, result);
    fclose(file);
}

int main(int argc, char **argv)
{
#ifdef TEST_BUILD
    if (argc == 2 && strcmp(argv[1], "--test-info") == 0) {
        printf("conf=%s\nstate=%s\nboot=%s\nmodule=%s\n", PROC_CONF_DIR, STATE_DIR, BOOT_ID_PATH, MODULE_DIR);
        return 0;
    }
#endif
#ifndef TEST_BUILD
    if (getuid() != 0) { puts("error=root-required"); return 1; }
#endif
    umask(0077);
    if (argc == 2 && strcmp(argv[1], "--check") == 0) {
        /* Query only: never create a file, start a worker or change a target. */
        int fd = lock_file(STATE_DIR "/daemon.lock", false);
        if (fd < 0) return 1;
        int result = flock(fd, LOCK_EX | LOCK_NB);
        bool alive = result != 0 && (errno == EWOULDBLOCK || errno == EAGAIN);
        close(fd);
        return alive ? 0 : 1;
    }
    if (!matches(MODULE_DIR "/protocol", "ipv6ctrl-managed-v2")) {
        puts("error=module-disabled-or-missing"); return 1;
    }
    if (mkdir(STATE_DIR, 0700) != 0 && errno != EEXIST) return 1;
    if (argc == 3 && strcmp(argv[1], "--control") == 0) {
        if (strcmp(argv[2], "status") != 0 && strcmp(argv[2], "block") != 0 &&
            strcmp(argv[2], "restore") != 0 && strcmp(argv[2], "pause") != 0 &&
            strcmp(argv[2], "enable") != 0 && strcmp(argv[2], "toggle") != 0) return 2;
        int fd = lock_file(STATE_DIR "/control.lock", true);
        int locked = -1;
        /* Foreground controls wait briefly for a sweep; never wait indefinitely. */
        for (int attempt = 0; fd >= 0 && attempt < 100; attempt++) {
            locked = flock(fd, LOCK_EX | LOCK_NB);
            if (locked == 0 || (errno != EWOULDBLOCK && errno != EAGAIN)) break;
            struct timespec delay = {.tv_nsec = 20000000};
            nanosleep(&delay, NULL);
        }
        if (fd < 0 || locked != 0) {
            puts("error=operation-locked"); return 1;
        }
        /* Inherit the kernel lock through exec: SIGKILL cannot leave a stale mkdir lock. */
        if (fcntl(fd, F_SETFD, 0) != 0) return 1;
        execl(SHELL_PATH, "sh", MODULE_DIR "/control.sh", "--locked", PROC_CONF_DIR,
              STATE_DIR, BOOT_ID_PATH, MODULE_DIR, argv[2], (char *)NULL);
        puts("error=controller-unavailable"); return 1;
    }
    if (argc != 1) return 2;
    if (!enabled_module()) return 1;
    char boot[128];
    if (!first_line(BOOT_ID_PATH, boot, sizeof(boot)) || boot[0] == '\0') return 1;
    int singleton = lock_file(STATE_DIR "/daemon.lock", true);
    if (singleton < 0 || flock(singleton, LOCK_EX | LOCK_NB) != 0) return 1;
    int operation = lock_file(STATE_DIR "/control.lock", true);
    if (operation < 0) return 1;
    struct sigaction action = {.sa_handler = stop};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) != 0 || sigaction(SIGINT, &action, NULL) != 0) return 1;
    while (running) {
        if (flock(operation, LOCK_EX | LOCK_NB) == 0) {
            if (!enabled_module()) {
                /* Removing/disabling the module stops takeover; never turns IPv6 on implicitly. */
                unlink(STATE_DIR "/managed");
                flock(operation, LOCK_UN);
                break;
            }
            if (matches(STATE_DIR "/managed", boot)) {
                if (!matches(STATE_DIR "/snapshot", boot)) report(boot, "snapshot-error");
                else report(boot, sweep() == 0 ? "ok" : "write-error");
            } else report(boot, "idle");
            flock(operation, LOCK_UN);
        }
        /* ponytail: one 3s sweep; no netlink subscription, app hooks or restart supervisor. */
        struct timespec delay = {.tv_sec = 3};
        while (running && nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
    }
    close(operation);
    close(singleton);
    return 0;
}
