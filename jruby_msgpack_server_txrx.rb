# JRuby UNIXServer variant using two separate socket channels instead of
# one bidirectional connection -- a dedicated client->server channel (c2s)
# and a dedicated server->client channel (s2c), like a tx/rx pair. Runs on
# the patched JEP-380 UnixDomain backend from this PR, so this also proves
# the implementation handles two independent concurrent listen/accept
# cycles between the same two processes, not just one.
#
#   jruby_msgpack_server_txrx.rb /path/to/c2s.sock /path/to/s2c.sock
require "socket"
require "java"
require "msgpack"

c2s_path = ARGV[0] || "/tmp/jep380-txrx-c2s.sock"
s2c_path = ARGV[1] || "/tmp/jep380-txrx-s2c.sock"
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
  len_bytes = recv_all(sock, 4)
  len = len_bytes.bytes.reduce(0) { |acc, b| (acc << 8) | b }
  recv_all(sock, len)
end

def write_frame(sock, payload)
  len = payload.bytesize
  header = [(len >> 24) & 0xff, (len >> 16) & 0xff, (len >> 8) & 0xff, len & 0xff].pack("C4")
  send_all(sock, header + payload)
end

puts "SERVER: #{RUBY_ENGINE} #{RUBY_ENGINE_VERSION rescue RUBY_VERSION} tx/rx UNIXServer (JEP-380)"
puts "SERVER: UnixDomain.bits=#{Java::OrgJrubyExtSocket::UnixDomain.bits}"
puts "SERVER: c2s=#{c2s_path} s2c=#{s2c_path}"

c2s_server = UNIXServer.new(c2s_path)
s2c_server = UNIXServer.new(s2c_path)
STDOUT.flush

c2s = c2s_server.accept
puts "SERVER: accepted c2s connection"
s2c = s2c_server.accept
puts "SERVER: accepted s2c connection"

3.times do |i|
  payload = read_frame(c2s)
  msg = MessagePack.unpack(payload)
  puts "SERVER: [c2s] received seq=#{msg["seq"]} engine=#{msg["engine"]} msg_len=#{msg["msg"].bytesize}"

  reply = { "engine" => "JRuby", "seq" => msg["seq"], "msg" => "JRuby-echo:#{msg["msg"]}" }
  write_frame(s2c, reply.to_msgpack)
  puts "SERVER: [s2c] replied seq=#{i}"
end

c2s.close
s2c.close
c2s_server.close
s2c_server.close
[c2s_path, s2c_path].each { |p| File.delete(p) if File.exist?(p) }
puts "SERVER: done"
