#!/usr/bin/env python3
"""Python client for jruby_msgpack_server_txrx.rb -- two separate socket
connections (c2s outgoing, s2c incoming) instead of one bidirectional
connection.

    PYTHONPATH=/home/cufe/.local/pylibs python3 msgpack_py_client_txrx.py \
        /path/to/c2s.sock /path/to/s2c.sock
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
    c2s_path, s2c_path = sys.argv[1], sys.argv[2]

    c2s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    c2s.connect(c2s_path)
    s2c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s2c.connect(s2c_path)
    print(f"CLIENT(Python-txrx): connected c2s={c2s_path} s2c={s2c_path}")

    messages = [
        "hello #0 from Python txrx",
        "second message from Python txrx",
        "X" * 5000,
    ]

    for seq, msg in enumerate(messages):
        write_frame(c2s, {"engine": "Python", "seq": seq, "msg": msg})
        reply = read_frame(s2c)

        expected = {"engine": "JRuby", "seq": seq, "msg": f"JRuby-echo:{msg}"}
        ok = reply == expected
        print(f"CLIENT(Python-txrx): seq={seq} sent={len(msg)}B "
              f"reply={len(reply['msg'])}B engine={reply['engine']} match={ok}")
        if not ok:
            raise AssertionError(f"mismatch on seq {seq}: {reply!r} != {expected!r}")

    c2s.close()
    s2c.close()
    print("CLIENT(Python-txrx): all messages round-tripped correctly")


if __name__ == "__main__":
    main()
