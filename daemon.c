#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/file.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/un.h>

#ifndef PROC_CONF_DIR
#define PROC_CONF_DIR "/proc/sys/net/ipv6/conf"
#endif
#define PROC_ALL_PATH PROC_CONF_DIR "/all/disable_ipv6"
#define PROC_DEFAULT_PATH PROC_CONF_DIR "/default/disable_ipv6"
#define SOCKET_PATH "/data/adb/modules/ipv6_ctrl/ipv6_daemon.sock"

#define CMD_MAX 128
#define JSON_MAX 192
#define PATH_MAX_LOCAL 512
#define SELECT_TIMEOUT_SEC 3

static int target_state = 0;
static const char *socket_path = SOCKET_PATH;
static volatile sig_atomic_t running = 1;

static const char *state_name(int value)
{
    return value == 1 ? "disabled" : value == 0 ? "enabled" : "mixed";
}

static int set_cloexec(int fd)
{
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0) {
        return -1;
    }
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static int read_state_file(const char *path)
{
    char c = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    ssize_t n;
    do {
        n = read(fd, &c, 1);
    } while (n < 0 && errno == EINTR);

    close(fd);

    if (n != 1 || (c != '0' && c != '1')) {
        return -1;
    }
    return c == '1' ? 1 : 0;
}

static int write_state_file(const char *path, int value)
{
    if (read_state_file(path) == value) {
        return 0;
    }
    char c = value == 1 ? '1' : '0';
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    ssize_t n;
    do {
        n = write(fd, &c, 1);
    } while (n < 0 && errno == EINTR);

    close(fd);
    return n == 1 && read_state_file(path) == value ? 0 : -1;
}

static int apply_all_interfaces(int value)
{
    int hard_failed = 0;

    /* Preserve the legacy module's global scope; the trial script is separate. */
    if (write_state_file(PROC_ALL_PATH, value) != 0) {
        hard_failed = 1;
    }
    if (write_state_file(PROC_DEFAULT_PATH, value) != 0) {
        hard_failed = 1;
    }

    DIR *dir = opendir(PROC_CONF_DIR);
    if (!dir) {
        return -1;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        char path[PATH_MAX_LOCAL];

        if (entry->d_name[0] == '.' || strcmp(entry->d_name, "all") == 0 ||
            strcmp(entry->d_name, "default") == 0) {
            continue;
        }

        int len = snprintf(path, sizeof(path), "%s/%s/disable_ipv6",
                           PROC_CONF_DIR, entry->d_name);
        if (len <= 0 || (size_t)len >= sizeof(path)) {
            hard_failed = 1;
            continue;
        }

        if (write_state_file(path, value) != 0) {
            hard_failed = 1;
        }
    }

    closedir(dir);
    return hard_failed ? -1 : 0;
}

static void trim_command(char *s)
{
    char *start = s;
    while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n') {
        start++;
    }

    if (start != s) {
        memmove(s, start, strlen(start) + 1);
    }

    size_t len = strlen(s);
    while (len > 0) {
        char c = s[len - 1];
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
            break;
        }
        s[--len] = '\0';
    }
}

static void json_format(char *buf, size_t size, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

static void json_format(char *buf, size_t size, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    (void)vsnprintf(buf, size, fmt, ap);
    va_end(ap);
}

static void send_json(int fd, const char *json)
{
    size_t off = 0;
    size_t len = strlen(json);

    while (off < len) {
        ssize_t n = send(fd, json + off, len - off, MSG_NOSIGNAL);
        if (n > 0) {
            off += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        break;
    }
}

static int actual_state(void)
{
    DIR *dir = opendir(PROC_CONF_DIR);
    if (!dir) {
        return -1;
    }
    int state = read_state_file(PROC_ALL_PATH);
    int defaults = read_state_file(PROC_DEFAULT_PATH);
    if (state < 0 || defaults < 0) {
        closedir(dir);
        return -1;
    }
    if (state != defaults) {
        state = 2;
    }
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        char path[PATH_MAX_LOCAL];
        int len = snprintf(path, sizeof(path), "%s/%s/disable_ipv6",
                           PROC_CONF_DIR, entry->d_name);
        int value = len > 0 && (size_t)len < sizeof(path) ? read_state_file(path) : -1;
        if (value < 0) {
            state = -1;
            break;
        }
        if (value != state) {
            state = 2;
        }
    }
    closedir(dir);
    return state;
}

static void build_status_json(char *json, size_t size)
{
    int actual = actual_state();
    if (actual < 0) {
        json_format(json, size, "{\"ok\":false,\"error\":\"procfs unavailable\"}\n");
        return;
    }

    json_format(json, size,
                "{\"ok\":true,\"state\":\"%s\",\"target\":\"%s\",\"target_enforced\":%s}\n",
                state_name(actual), state_name(target_state),
                actual == target_state ? "true" : "false");
}

static void handle_command(const char *cmd, char *json, size_t size)
{
    if (strcmp(cmd, "GET_STATUS") == 0) {
        build_status_json(json, size);
        return;
    }

    if (strcmp(cmd, "ENABLE") == 0 || strcmp(cmd, "DISABLE") == 0) {
        int desired = strcmp(cmd, "DISABLE") == 0 ? 1 : 0;
        target_state = desired;

        if (apply_all_interfaces(target_state) != 0) {
            json_format(json, size, "{\"ok\":false,\"error\":\"write failed\"}\n");
            return;
        }

        build_status_json(json, size);
        return;
    }

    json_format(json, size, "{\"ok\":false,\"error\":\"unknown command\"}\n");
}

static void handle_socket_client(int server_fd)
{
    int client_fd;
    do {
        client_fd = accept(server_fd, NULL, NULL);
    } while (client_fd < 0 && errno == EINTR);

    if (client_fd < 0) {
        return;
    }
    (void)set_cloexec(client_fd);

    struct ucred peer;
    socklen_t peer_size = sizeof(peer);
    struct timeval limit = {.tv_sec = 1, .tv_usec = 0};
    if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) != 0 ||
        peer.uid != geteuid() ||
        setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &limit, sizeof(limit)) != 0 ||
        setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit)) != 0) {
        close(client_fd);
        return;
    }

    char cmd[CMD_MAX];
    ssize_t n;
    do {
        n = recv(client_fd, cmd, sizeof(cmd) - 1, 0);
    } while (n < 0 && errno == EINTR && running);

    char json[JSON_MAX];
    if (n <= 0) {
        json_format(json, sizeof(json), "{\"ok\":false,\"error\":\"empty command\"}\n");
    } else if (n == (ssize_t)sizeof(cmd) - 1 || memchr(cmd, '\0', (size_t)n)) {
        json_format(json, sizeof(json), "{\"ok\":false,\"error\":\"invalid command\"}\n");
    } else {
        cmd[n] = '\0';
        trim_command(cmd);
        if (cmd[0] == '\0') {
            json_format(json, sizeof(json), "{\"ok\":false,\"error\":\"empty command\"}\n");
        } else {
            handle_command(cmd, json, sizeof(json));
        }
    }

    send_json(client_fd, json);
    close(client_fd);
}

static int setup_socket(void)
{
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    (void)set_cloexec(fd);

    unlink(socket_path);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(socket_path) >= sizeof(addr.sun_path)) {
        close(fd);
        return -1;
    }
    strcpy(addr.sun_path, socket_path);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }

    if (chmod(socket_path, 0600) != 0 || listen(fd, 8) != 0) {
        close(fd);
        unlink(socket_path);
        return -1;
    }

    return fd;
}

static void stop_daemon(int signo)
{
    (void)signo;
    running = 0;
}

static time_t monotonic_seconds(void)
{
    struct timespec ts;
    return clock_gettime(CLOCK_MONOTONIC, &ts) == 0 ? ts.tv_sec : time(NULL);
}

int main(int argc, char **argv)
{
    if (argc > 2) {
        return 2;
    }
    if (argc == 2) {
        socket_path = argv[1];
    }

    /* Keep the lock inode: unlinking it would permit two owners of one path. */
    char lock_path[PATH_MAX_LOCAL];
    int len = snprintf(lock_path, sizeof(lock_path), "%s.lock", socket_path);
    if (len <= 0 || (size_t)len >= sizeof(lock_path)) {
        return 1;
    }
    umask(0077);
    int lock_fd = open(lock_path, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (lock_fd < 0) {
        return 1;
    }
    if (flock(lock_fd, LOCK_EX | LOCK_NB) != 0) {
        close(lock_fd);
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = stop_daemon;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) != 0 ||
        sigaction(SIGINT, &action, NULL) != 0) {
        close(lock_fd);
        return 1;
    }

    int initial = read_state_file(PROC_ALL_PATH);
    target_state = initial < 0 ? 0 : initial;
    int server_fd = setup_socket();
    if (server_fd < 0) {
        close(lock_fd);
        return 1;
    }

    /* ponytail: one bounded sweep every 3s avoids inotify feedback and cooldowns. */
    time_t next_sweep = monotonic_seconds() + SELECT_TIMEOUT_SEC;
    while (running) {
        time_t now = monotonic_seconds();
        if (now >= next_sweep) {
            (void)apply_all_interfaces(target_state);
            next_sweep = now + SELECT_TIMEOUT_SEC;
        }

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(server_fd, &rfds);
        struct timeval timeout = {
            .tv_sec = next_sweep - now,
            .tv_usec = 0,
        };
        int ready = select(server_fd + 1, &rfds, NULL, NULL, &timeout);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (ready > 0 && running) {
            handle_socket_client(server_fd);
        }
    }

    close(server_fd);
    unlink(socket_path);
    close(lock_fd);
    return running ? 1 : 0;
}
