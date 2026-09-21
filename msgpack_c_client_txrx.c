/*
 * C client for jruby_msgpack_server_txrx.rb -- two separate socket
 * connections (c2s for outgoing frames, s2c for incoming frames) instead
 * of one bidirectional connection, like a tx/rx pair. Encoding/decoding
 * logic is identical to msgpack_c_client.c.
 *
 * Usage: msgpack_c_client_txrx /path/to/c2s.sock /path/to/s2c.sock
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

static size_t mp_write_str(unsigned char *out, const char *s, size_t len) {
    unsigned char *p = out;
    if (len <= 31) {
        *p++ = 0xa0 | (unsigned char)len;
    } else if (len <= 255) {
        *p++ = 0xd9;
        *p++ = (unsigned char)len;
    } else {
        *p++ = 0xda;
        *p++ = (unsigned char)((len >> 8) & 0xff);
        *p++ = (unsigned char)(len & 0xff);
    }
    memcpy(p, s, len);
    return (p - out) + len;
}

static size_t build_frame(unsigned char *out, int seq, const char *engine,
                           const char *msg, size_t msg_len) {
    unsigned char *p = out + 4;
    unsigned char *start = p;
    *p++ = 0x83;
    p += mp_write_str(p, "engine", 6);
    p += mp_write_str(p, engine, strlen(engine));
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

static size_t mp_read_str_header(const unsigned char *p, size_t *len_out) {
    unsigned char tag = p[0];
    if (tag >= 0xa0 && tag <= 0xbf) {
        *len_out = tag & 0x1f;
        return 1;
    } else if (tag == 0xd9) {
        *len_out = p[1];
        return 2;
    } else if (tag == 0xda) {
        *len_out = ((size_t)p[1] << 8) | p[2];
        return 3;
    }
    fprintf(stderr, "unexpected string tag 0x%02x\n", tag);
    exit(1);
}

static void parse_reply(const unsigned char *payload, char *engine_out,
                         int *seq_out, char *msg_out, size_t msg_out_cap) {
    const unsigned char *p = payload;
    size_t len;

    p += 1;

    p += mp_read_str_header(p, &len); p += len;
    { size_t hlen = mp_read_str_header(p, &len); p += hlen;
      memcpy(engine_out, p, len); engine_out[len] = 0; p += len; }

    p += mp_read_str_header(p, &len); p += len;
    *seq_out = p[0] & 0x7f;
    p += 1;

    p += mp_read_str_header(p, &len); p += len;
    { size_t hlen = mp_read_str_header(p, &len); p += hlen;
      if (len >= msg_out_cap) { fprintf(stderr, "msg too large for buffer\n"); exit(1); }
      memcpy(msg_out, p, len); msg_out[len] = 0; }
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
    if (fd < 0) { perror("socket"); exit(1); }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("connect");
        exit(1);
    }
    return fd;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s /path/to/c2s.sock /path/to/s2c.sock\n", argv[0]);
        return 1;
    }

    int c2s_fd = connect_unix(argv[1]);
    int s2c_fd = connect_unix(argv[2]);
    printf("CLIENT(C-txrx): connected c2s=%s s2c=%s\n", argv[1], argv[2]);

    static char big_payload[5001];
    memset(big_payload, 'X', 5000);
    big_payload[5000] = 0;

    const char *messages[3];
    messages[0] = "hello #0 from real C txrx";
    messages[1] = "second message from C txrx";
    messages[2] = big_payload;

    unsigned char out_buf[16 + 65536];
    unsigned char len_hdr[4];
    unsigned char *reply_buf = malloc(65536);

    for (int seq = 0; seq < 3; seq++) {
        size_t msg_len = strlen(messages[seq]);
        size_t frame_len = build_frame(out_buf, seq, "C", messages[seq], msg_len);
        send_all(c2s_fd, out_buf, frame_len);

        recv_all(s2c_fd, len_hdr, 4);
        size_t reply_len = ((size_t)len_hdr[0] << 24) | ((size_t)len_hdr[1] << 16) |
                            ((size_t)len_hdr[2] << 8) | (size_t)len_hdr[3];
        recv_all(s2c_fd, reply_buf, reply_len);

        char engine[64];
        int reply_seq;
        char msg_out[6000];
        parse_reply(reply_buf, engine, &reply_seq, msg_out, sizeof(msg_out));

        char expected[6100];
        snprintf(expected, sizeof(expected), "JRuby-echo:%s", messages[seq]);
        int ok = (strcmp(engine, "JRuby") == 0) && (reply_seq == seq) &&
                 (strcmp(msg_out, expected) == 0);

        printf("CLIENT(C-txrx): seq=%d sent=%zuB reply=%zuB engine=%s match=%s\n",
               seq, msg_len, strlen(msg_out), engine, ok ? "true" : "FALSE");
        if (!ok) {
            fprintf(stderr, "MISMATCH on seq %d\n", seq);
            return 1;
        }
    }

    close(c2s_fd);
    close(s2c_fd);
    printf("CLIENT(C-txrx): all messages round-tripped correctly\n");
    return 0;
}
