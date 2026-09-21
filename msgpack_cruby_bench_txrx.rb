#!/usr/bin/env ruby
# Throughput benchmark: N round trips of a small fixed message over two
# separate warm connections (c2s, s2c), for comparison against
# msgpack_cruby_bench.rb's single-socket number.
#   ruby msgpack_cruby_bench_txrx.rb /path/to/c2s.sock /path/to/s2c.sock N
require "socket"
require "msgpack"

c2s_path, s2c_path = ARGV[0], ARGV[1]
n = (ARGV[2] || "2000").to_i

def recv_all(sock, size)
  buf = +""
  buf << sock.recv(size - buf.bytesize) while buf.bytesize < size
  buf
end

def send_all(sock, data)
  sent = 0
  sent += sock.send(data.byteslice(sent, 65536), 0) while sent < data.bytesize
end

def write_frame(sock, obj)
  payload = obj.to_msgpack
  send_all(sock, [payload.bytesize].pack("N") + payload)
end

def read_frame(sock)
  len = recv_all(sock, 4).unpack1("N")
  MessagePack.unpack(recv_all(sock, len))
end

c2s = UNIXSocket.new(c2s_path)
s2c = UNIXSocket.new(s2c_path)
msg = { "engine" => "CRuby", "seq" => 0, "msg" => "ping" }

t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
n.times do
  write_frame(c2s, msg)
  read_frame(s2c)
end
t1 = Process.clock_gettime(Process::CLOCK_MONOTONIC)

secs = t1 - t0
printf("BENCH(CRuby-txrx): n=%d time=%.4fs throughput=%.1f msg/s mean=%.4fms\n",
       n, secs, n / secs, (secs / n) * 1000)
c2s.close
s2c.close
