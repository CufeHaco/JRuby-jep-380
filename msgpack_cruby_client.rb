#!/usr/bin/env ruby
# CRuby client for the JRuby JEP-380 UNIXServer (jruby_msgpack_server.rb),
# using the real msgpack gem over one bidirectional connection.
#   ruby msgpack_cruby_client.rb /path/to/socket
require "socket"
require "msgpack"

path = ARGV[0]

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
  header = [payload.bytesize].pack("N")
  send_all(sock, header + payload)
end

def read_frame(sock)
  len = recv_all(sock, 4).unpack1("N")
  MessagePack.unpack(recv_all(sock, len))
end

sleep 0.05 until File.exist?(path)
sock = UNIXSocket.new(path)
puts "CLIENT(CRuby): connected to #{path}"

messages = ["hello #0 from CRuby", "second message from CRuby", "X" * 5000]
messages.each_with_index do |msg, seq|
  write_frame(sock, { "engine" => "CRuby", "seq" => seq, "msg" => msg })
  reply = read_frame(sock)

  expected = { "engine" => "JRuby", "seq" => seq, "msg" => "JRuby-echo:#{msg}" }
  ok = reply == expected
  puts "CLIENT(CRuby): seq=#{seq} sent=#{msg.bytesize}B reply=#{reply["msg"].bytesize}B " \
       "engine=#{reply["engine"]} match=#{ok}"
  raise "MISMATCH on seq #{seq}" unless ok
end

sock.close
puts "CLIENT(CRuby): all messages round-tripped correctly"
