# JRuby UNIXServer speaking length-prefixed MessagePack frames. Runs on
# the patched JEP-380 UnixDomain backend from this PR. Shared by every
# cross-runtime client in this repo: msgpack_c_client.c, msgpack_asm_client.asm,
# msgpack_py_client.py, msgpack_rust_client/.
require "socket"
require "java"
require "msgpack"

path = ARGV[0] || "/tmp/jep380-spinel.sock"
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
  len_bytes = recv_all(sock, 4)
  len = len_bytes.bytes.reduce(0) { |acc, b| (acc << 8) | b }
  recv_all(sock, len)
end

def write_frame(sock, payload)
  len = payload.bytesize
  header = [(len >> 24) & 0xff, (len >> 16) & 0xff, (len >> 8) & 0xff, len & 0xff].pack("C4")
  send_all(sock, header + payload)
end

puts "SERVER: #{RUBY_ENGINE} #{RUBY_ENGINE_VERSION rescue RUBY_VERSION} UNIXServer (JEP-380) at #{path}"
puts "SERVER: UnixDomain.bits=#{Java::OrgJrubyExtSocket::UnixDomain.bits}"

server = UNIXServer.new(path)
STDOUT.flush
client = server.accept
puts "SERVER: accepted connection"

3.times do |i|
  payload = read_frame(client)
  msg = MessagePack.unpack(payload)
  puts "SERVER: received seq=#{msg["seq"]} engine=#{msg["engine"]} msg_len=#{msg["msg"].bytesize}"

  reply = { "engine" => "JRuby", "seq" => msg["seq"], "msg" => "JRuby-echo:#{msg["msg"]}" }
  write_frame(client, reply.to_msgpack)
  puts "SERVER: replied seq=#{i}"
end

client.close
server.close
File.delete(path) if File.exist?(path)
puts "SERVER: done"
