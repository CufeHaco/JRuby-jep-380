# Benchmark-mode JRuby UNIXServer for the per-language throughput clients
# (msgpack_*_bench.*). No per-message printing -- that would skew timing
# on the server side too. Runs on the patched JEP-380 UnixDomain backend.
#   jruby_msgpack_bench_server.rb /path/to/socket N
require "socket"
require "msgpack"

path = ARGV[0]
n = (ARGV[1] || "2000").to_i
File.delete(path) if File.exist?(path)

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

server = UNIXServer.new(path)
STDOUT.flush
client = server.accept

n.times do |i|
  msg = read_frame(client)
  write_frame(client, { "engine" => "JRuby", "seq" => i, "msg" => "echo:#{msg["msg"]}" })
end

client.close
server.close
File.delete(path) if File.exist?(path)
puts "SERVER: bench done (n=#{n})"
