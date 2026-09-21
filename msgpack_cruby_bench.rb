#!/usr/bin/env ruby
# Throughput benchmark: N round trips of a small fixed message over one
# warm connection to jruby_msgpack_bench_server.rb.
#   ruby msgpack_cruby_bench.rb /path/to/socket N
require "socket"
require "msgpack"

path = ARGV[0]
n = (ARGV[1] || "2000").to_i

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

sock = UNIXSocket.new(path)
msg = { "engine" => "CRuby", "seq" => 0, "msg" => "ping" }

t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
n.times do
  write_frame(sock, msg)
  read_frame(sock)
end
t1 = Process.clock_gettime(Process::CLOCK_MONOTONIC)

secs = t1 - t0
printf("BENCH(CRuby): n=%d time=%.4fs throughput=%.1f msg/s mean=%.4fms\n",
       n, secs, n / secs, (secs / n) * 1000)
sock.close
