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
#include <sys/inotify.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>

#define PROC_CONF_DIR "/proc/sys/net/ipv6/conf"
#define PROC_ALL_PATH PROC_CONF_DIR "/all/disable_ipv6"
#define PROC_DEFAULT_PATH PROC_CONF_DIR "/default/disable_ipv6"
#define SOCKET_PATH "/data/adb/modules/ipv6_ctrl/ipv6_daemon.sock"

#define CMD_MAX 128
#define JSON_MAX 192
#define PATH_MAX_LOCAL 512
#define SELECT_TIMEOUT_SEC 3
#define RATE_WINDOW_SEC 1
#define RATE_LIMIT_EVENTS 3
#define COOLDOWN_SEC 4

static int target_state = 0;

static const char *state_name(int value)
{
    return value == 1 ? "disabled" : "enabled";
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
    return n == 1 ? 0 : -1;
}

static int apply_all_interfaces(int value)
{
    int hard_failed = 0;

    /* Keep global/default writes first; the interface sweep may duplicate them. */
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

        if (entry->d_name[0] == '.') {
            continue;
        }

        int len = snprintf(path, sizeof(path), "%s/%s/disable_ipv6",
                           PROC_CONF_DIR, entry->d_name);
        if (len <= 0 || (size_t)len >= sizeof(path)) {
            continue;
        }

        (void)write_state_file(path, value);
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

static void build_status_json(char *json, size_t size)
{
    int actual = read_state_file(PROC_ALL_PATH);
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

        int actual = read_state_file(PROC_ALL_PATH);
        json_format(json, size,
                    "{\"ok\":true,\"state\":\"%s\",\"target_enforced\":%s}\n",
                    state_name(target_state),
                    actual == target_state ? "true" : "false");
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

    char cmd[CMD_MAX];
    ssize_t n;
    do {
        n = recv(client_fd, cmd, sizeof(cmd) - 1, 0);
    } while (n < 0 && errno == EINTR);

    char json[JSON_MAX];
    if (n <= 0) {
        json_format(json, sizeof(json), "{\"ok\":false,\"error\":\"empty command\"}\n");
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

    unlink(SOCKET_PATH);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(SOCKET_PATH) >= sizeof(addr.sun_path)) {
        close(fd);
        return -1;
    }
    strcpy(addr.sun_path, SOCKET_PATH);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }

    (void)chmod(SOCKET_PATH, 0666);

    if (listen(fd, 8) != 0) {
        close(fd);
        unlink(SOCKET_PATH);
        return -1;
    }

    return fd;
}

static int add_watch(int inotify_fd)
{
    /* Only watch all/disable_ipv6; timeout polling covers missed interface churn. */
    return inotify_add_watch(inotify_fd, PROC_ALL_PATH,
                             IN_MODIFY | IN_CLOSE_WRITE | IN_ATTRIB |
                             IN_DELETE_SELF | IN_MOVE_SELF);
}

static bool drain_inotify(int fd)
{
    char buf[sizeof(struct inotify_event) + 256];
    bool needs_rewatch = false;

    for (;;) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            size_t off = 0;
            while (off + sizeof(struct inotify_event) <= (size_t)n) {
                struct inotify_event *ev = (struct inotify_event *)(buf + off);
                if (ev->mask & (IN_IGNORED | IN_DELETE_SELF | IN_MOVE_SELF)) {
                    needs_rewatch = true;
                }
                off += sizeof(struct inotify_event) + ev->len;
            }
            continue;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        break;
    }

    return needs_rewatch;
}

static bool rate_limited(void)
{
    static time_t window_start = -1;
    static int events = 0;

    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        ts.tv_sec = time(NULL);
    }

    time_t now = ts.tv_sec;
    if (window_start < 0 || now - window_start >= RATE_WINDOW_SEC) {
        window_start = now;
        events = 0;
    }

    events++;
    if (events > RATE_LIMIT_EVENTS) {
        /* Stubborn vendor netd loops should not pin a CPU core. */
        sleep(COOLDOWN_SEC);
        if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
            window_start = ts.tv_sec;
        } else {
            window_start = time(NULL);
        }
        events = 0;
        return true;
    }

    return false;
}

static void enforce_if_needed(void)
{
    /* Timeout path is intentionally cheap: read all first, sweep only on drift. */
    int actual = read_state_file(PROC_ALL_PATH);
    if (actual >= 0 && actual != target_state) {
        (void)apply_all_interfaces(target_state);
    }
}

int main(void)
{
    signal(SIGPIPE, SIG_IGN);

    int initial = read_state_file(PROC_ALL_PATH);
    target_state = initial < 0 ? 0 : initial;

    /* IPC owns all user-visible output; the daemon itself stays quiet. */
    int server_fd = setup_socket();
    if (server_fd < 0) {
        return 1;
    }

    int inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotify_fd < 0) {
        inotify_fd = inotify_init();
        if (inotify_fd >= 0) {
            (void)set_cloexec(inotify_fd);
            int flags = fcntl(inotify_fd, F_GETFL);
            if (flags >= 0) {
                (void)fcntl(inotify_fd, F_SETFL, flags | O_NONBLOCK);
            }
        }
    }

    int watch_fd = -1;
    if (inotify_fd >= 0) {
        watch_fd = add_watch(inotify_fd);
    }

    for (;;) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(server_fd, &rfds);

        int max_fd = server_fd;
        if (inotify_fd >= 0) {
            FD_SET(inotify_fd, &rfds);
            if (inotify_fd > max_fd) {
                max_fd = inotify_fd;
            }
        }

        struct timeval timeout;
        timeout.tv_sec = SELECT_TIMEOUT_SEC;
        timeout.tv_usec = 0;

        int ready = select(max_fd + 1, &rfds, NULL, NULL, &timeout);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }

        if (ready == 0) {
            enforce_if_needed();
            continue;
        }

        if (FD_ISSET(server_fd, &rfds)) {
            handle_socket_client(server_fd);
        }

        if (inotify_fd >= 0 && FD_ISSET(inotify_fd, &rfds)) {
            if (drain_inotify(inotify_fd)) {
                watch_fd = -1;
            }
            if (!rate_limited()) {
                (void)apply_all_interfaces(target_state);
            }

            if (watch_fd < 0) {
                watch_fd = add_watch(inotify_fd);
            }
        }
    }

    if (inotify_fd >= 0) {
        close(inotify_fd);
    }
    close(server_fd);
    unlink(SOCKET_PATH);
    return 1;
}
