# Benchmark-mode JRuby UNIXServer for the tx/rx throughput clients --
# two separate socket connections (c2s, s2c) instead of one bidirectional
# one. No per-message printing, same as jruby_msgpack_bench_server.rb.
#   jruby_msgpack_bench_server_txrx.rb /path/to/c2s.sock /path/to/s2c.sock N
require "socket"
require "msgpack"

c2s_path = ARGV[0]
s2c_path = ARGV[1]
n = (ARGV[2] || "2000").to_i
[c2s_path, s2c_path].each { |p| File.delete(p) if File.exist?(p) }

def recv_all(sock, size)
  buf = +""
  buf << sock.recv(size - buf.bytesize) while buf.bytesize < size
  buf
end

def send_all(sock, data)
  sent = 0
  sent += sock.send(data.byteslice(sent, 65536), 0) while sent < data.bytesize
end

def read_frame(sock)
  len = recv_all(sock, 4).unpack1("N")
  MessagePack.unpack(recv_all(sock, len))
end

def write_frame(sock, obj)
  payload = obj.to_msgpack
  send_all(sock, [payload.bytesize].pack("N") + payload)
end

c2s_server = UNIXServer.new(c2s_path)
s2c_server = UNIXServer.new(s2c_path)
STDOUT.flush
c2s = c2s_server.accept
s2c = s2c_server.accept

n.times do |i|
  msg = read_frame(c2s)
  write_frame(s2c, { "engine" => "JRuby", "seq" => i, "msg" => "echo:#{msg["msg"]}" })
end

c2s.close
s2c.close
c2s_server.close
s2c_server.close
[c2s_path, s2c_path].each { |p| File.delete(p) if File.exist?(p) }
puts "SERVER: tx/rx bench done (n=#{n})"
