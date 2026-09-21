/*
 * Throughput benchmark: N round trips of a small fixed message over two
 * separate warm connections (c2s outgoing, s2c incoming) to
 * jruby_msgpack_bench_server_txrx.rb, for comparison against
 * msgpack_c_bench.c's single-bidirectional-socket number.
 *
 * Usage: msgpack_c_bench_txrx /path/to/c2s.sock /path/to/s2c.sock N
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

static size_t mp_write_str(unsigned char *out, const char *s, size_t len) {
    unsigned char *p = out;
    *p++ = 0xa0 | (unsigned char)len;
    memcpy(p, s, len);
    return (p - out) + len;
}

static size_t build_frame(unsigned char *out, int seq, const char *msg, size_t msg_len) {
    unsigned char *p = out + 4;
    unsigned char *start = p;
    *p++ = 0x83;
    p += mp_write_str(p, "engine", 6);
    p += mp_write_str(p, "C", 1);
    p += mp_write_str(p, "seq", 3);
    *p++ = (unsigned char)(seq & 0x7f);
    p += mp_write_str(p, "msg", 3);
    p += mp_write_str(p, msg, msg_len);
    size_t payload_len = p - start;
    out[0] = (unsigned char)((payload_len >> 24) & 0xff);
    out[1] = (unsigned char)((payload_len >> 16) & 0xff);
    out[2] = (unsigned char)((payload_len >> 8) & 0xff);
    out[3] = (unsigned char)(payload_len & 0xff);
    return 4 + payload_len;
}

static void send_all(int fd, const void *buf, size_t len) {
    const unsigned char *p = (const unsigned char *)buf;
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, p + sent, len - sent, 0);
        if (n <= 0) { perror("send"); exit(1); }
        sent += (size_t)n;
    }
}

static void recv_all(int fd, void *buf, size_t len) {
    unsigned char *p = (unsigned char *)buf;
    size_t got = 0;
    while (got < len) {
        ssize_t n = recv(fd, p + got, len - got, 0);
        if (n <= 0) { perror("recv"); exit(1); }
        got += (size_t)n;
    }
}

static int connect_unix(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror("connect"); exit(1); }
    return fd;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s /path/to/c2s.sock /path/to/s2c.sock N\n", argv[0]);
        return 1;
    }
    int n = atoi(argv[3]);

    int c2s_fd = connect_unix(argv[1]);
    int s2c_fd = connect_unix(argv[2]);

    const char *msg = "ping";
    unsigned char out_buf[64];
    unsigned char len_hdr[4];
    unsigned char reply_buf[256];
    size_t frame_len = build_frame(out_buf, 0, msg, strlen(msg));

    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (int i = 0; i < n; i++) {
        send_all(c2s_fd, out_buf, frame_len);
        recv_all(s2c_fd, len_hdr, 4);
        size_t reply_len = ((size_t)len_hdr[0] << 24) | ((size_t)len_hdr[1] << 16) |
                            ((size_t)len_hdr[2] << 8) | (size_t)len_hdr[3];
        recv_all(s2c_fd, reply_buf, reply_len);
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);

    double secs = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9;
    printf("BENCH(C-txrx): n=%d time=%.4fs throughput=%.1f msg/s mean=%.4fms\n",
           n, secs, n / secs, (secs / n) * 1000.0);

    close(c2s_fd);
    close(s2c_fd);
    return 0;
}
