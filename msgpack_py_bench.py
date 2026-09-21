#!/usr/bin/env python3
"""Throughput benchmark: N round trips of a small fixed message over one
warm connection to jruby_msgpack_bench_server.rb.

    PYTHONPATH=/home/cufe/.local/pylibs python3 msgpack_py_bench.py /path/to/socket N
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
    path = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 2000

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(path)
    msg = {"engine": "Python", "seq": 0, "msg": "ping"}

    t0 = time.monotonic()
    for _ in range(n):
        write_frame(sock, msg)
        read_frame(sock)
    t1 = time.monotonic()

    secs = t1 - t0
    print(f"BENCH(Python): n={n} time={secs:.4f}s throughput={n / secs:.1f} msg/s "
          f"mean={(secs / n) * 1000:.4f}ms")
    sock.close()


if __name__ == "__main__":
    main()
