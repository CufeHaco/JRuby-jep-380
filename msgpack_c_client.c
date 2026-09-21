/*
 * Real C client for the JRuby JEP-380 UNIXServer (jruby_msgpack_server.rb),
 * speaking length-prefixed MessagePack frames. Encodes/decodes a 3-field
 * map {"engine"=>.., "seq"=>.., "msg"=>..} using plain fixmap/fixstr/str8/
 * str16/fixint per the MessagePack spec -- no library, no line-based
 * protocol, so there's no text-length limit like the earlier stdio-based
 * approach hit.
 *
 * Usage: msgpack_c_client /path/to/socket
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

/* Builds {"engine"=>engine, "seq"=>seq, "msg"=>msg} (seq assumed 0-127). */
static size_t build_frame(unsigned char *out, int seq, const char *engine,
                           const char *msg, size_t msg_len) {
    unsigned char *p = out + 4; /* reserve 4 bytes for the length header */
    unsigned char *start = p;
    *p++ = 0x83; /* fixmap, 3 entries */
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

/* Parses {"engine"=>.., "seq"=>.., "msg"=>..} in that fixed field order. */
static void parse_reply(const unsigned char *payload, char *engine_out,
                         int *seq_out, char *msg_out, size_t msg_out_cap) {
    const unsigned char *p = payload;
    size_t len;

    p += 1; /* fixmap header */

    p += mp_read_str_header(p, &len); p += len; /* key "engine" */
    { size_t hlen = mp_read_str_header(p, &len); p += hlen;
      memcpy(engine_out, p, len); engine_out[len] = 0; p += len; }

    p += mp_read_str_header(p, &len); p += len; /* key "seq" */
    *seq_out = p[0] & 0x7f;
    p += 1;

    p += mp_read_str_header(p, &len); p += len; /* key "msg" */
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

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s /path/to/socket\n", argv[0]);
        return 1;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, argv[1], sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("connect");
        return 1;
    }
    printf("CLIENT(C): connected to %s\n", argv[1]);

    static char big_payload[5001];
    memset(big_payload, 'X', 5000);
    big_payload[5000] = 0;

    const char *messages[3];
    messages[0] = "hello #0 from real C";
    messages[1] = "second message from C";
    messages[2] = big_payload;

    unsigned char out_buf[16 + 65536];
    unsigned char len_hdr[4];
    unsigned char *reply_buf = malloc(65536);

    for (int seq = 0; seq < 3; seq++) {
        size_t msg_len = strlen(messages[seq]);
        size_t frame_len = build_frame(out_buf, seq, "C", messages[seq], msg_len);
        send_all(fd, out_buf, frame_len);

        recv_all(fd, len_hdr, 4);
        size_t reply_len = ((size_t)len_hdr[0] << 24) | ((size_t)len_hdr[1] << 16) |
                            ((size_t)len_hdr[2] << 8) | (size_t)len_hdr[3];
        recv_all(fd, reply_buf, reply_len);

        char engine[64];
        int reply_seq;
        char msg_out[6000];
        parse_reply(reply_buf, engine, &reply_seq, msg_out, sizeof(msg_out));

        char expected[6100];
        snprintf(expected, sizeof(expected), "JRuby-echo:%s", messages[seq]);
        int ok = (strcmp(engine, "JRuby") == 0) && (reply_seq == seq) &&
                 (strcmp(msg_out, expected) == 0);

        printf("CLIENT(C): seq=%d sent=%zuB reply=%zuB engine=%s match=%s\n",
               seq, msg_len, strlen(msg_out), engine, ok ? "true" : "FALSE");
        if (!ok) {
            fprintf(stderr, "MISMATCH on seq %d\n", seq);
            return 1;
        }
    }

    close(fd);
    printf("CLIENT(C): all messages round-tripped correctly\n");
    return 0;
}
