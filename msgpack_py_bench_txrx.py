#!/usr/bin/env python3
"""Throughput benchmark: N round trips of a small fixed message over two
separate warm connections (c2s, s2c), for comparison against
msgpack_py_bench.py's single-socket number.

    PYTHONPATH=/home/cufe/.local/pylibs python3 msgpack_py_bench_txrx.py \
        /path/to/c2s.sock /path/to/s2c.sock N
"""
import socket
import struct
import sys
import time
import msgpack


def send_all(sock, data):
    sock.sendall(data)


def recv_all(sock, size):
    buf = b""
    while len(buf) < size:
        chunk = sock.recv(size - len(buf))
        if not chunk:
            raise ConnectionError("peer closed early")
        buf += chunk
    return buf


def write_frame(sock, obj):
    payload = msgpack.packb(obj, use_bin_type=False)
    send_all(sock, struct.pack(">I", len(payload)) + payload)


def read_frame(sock):
    (length,) = struct.unpack(">I", recv_all(sock, 4))
    return msgpack.unpackb(recv_all(sock, length), raw=False)


def main():
    c2s_path, s2c_path = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 2000

    c2s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    c2s.connect(c2s_path)
    s2c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s2c.connect(s2c_path)
    msg = {"engine": "Python", "seq": 0, "msg": "ping"}

    t0 = time.monotonic()
    for _ in range(n):
        write_frame(c2s, msg)
        read_frame(s2c)
    t1 = time.monotonic()

    secs = t1 - t0
    print(f"BENCH(Python-txrx): n={n} time={secs:.4f}s throughput={n / secs:.1f} msg/s "
          f"mean={(secs / n) * 1000:.4f}ms")
    c2s.close()
    s2c.close()


if __name__ == "__main__":
    main()
