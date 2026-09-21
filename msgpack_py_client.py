#!/usr/bin/env python3
"""Python client for the JRuby JEP-380 UNIXServer (jruby_msgpack_server.rb),
using the real `msgpack` library (not hand-rolled encoding, unlike the C
and assembly clients) over a length-prefixed frame protocol.

    PYTHONPATH=/home/cufe/.local/pylibs python3 msgpack_py_client.py /path/to/socket
"""
import socket
import struct
import sys
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
    header = struct.pack(">I", len(payload))
    send_all(sock, header + payload)


def read_frame(sock):
    header = recv_all(sock, 4)
    (length,) = struct.unpack(">I", header)
    payload = recv_all(sock, length)
    return msgpack.unpackb(payload, raw=False)


def main():
    path = sys.argv[1]
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(path)
    print(f"CLIENT(Python): connected to {path} (msgpack {msgpack.version})")

    messages = [
        "hello #0 from Python",
        "second message from Python",
        "X" * 5000,
    ]

    for seq, msg in enumerate(messages):
        write_frame(sock, {"engine": "Python", "seq": seq, "msg": msg})
        reply = read_frame(sock)

        expected = {"engine": "JRuby", "seq": seq, "msg": f"JRuby-echo:{msg}"}
        ok = reply == expected
        print(f"CLIENT(Python): seq={seq} sent={len(msg)}B "
              f"reply={len(reply['msg'])}B engine={reply['engine']} match={ok}")
        if not ok:
            raise AssertionError(f"mismatch on seq {seq}: {reply!r} != {expected!r}")

    sock.close()
    print("CLIENT(Python): all messages round-tripped correctly")


if __name__ == "__main__":
    main()
