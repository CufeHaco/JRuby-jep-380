#!/usr/bin/env ruby
# CRuby client for jruby_msgpack_server_txrx.rb -- two separate socket
# channels (c2s for outgoing, s2c for incoming) instead of one
# bidirectional connection.
#   ruby msgpack_cruby_client_txrx.rb /path/to/c2s.sock /path/to/s2c.sock
require "socket"
require "msgpack"

c2s_path = ARGV[0]
s2c_path = ARGV[1]

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

sleep 0.05 until File.exist?(c2s_path) && File.exist?(s2c_path)
c2s = UNIXSocket.new(c2s_path)
s2c = UNIXSocket.new(s2c_path)
puts "CLIENT(CRuby-txrx): connected c2s=#{c2s_path} s2c=#{s2c_path}"

messages = ["hello #0 from CRuby-txrx", "second message from CRuby-txrx", "X" * 5000]
messages.each_with_index do |msg, seq|
  write_frame(c2s, { "engine" => "CRuby", "seq" => seq, "msg" => msg })
  reply = read_frame(s2c)

  expected = { "engine" => "JRuby", "seq" => seq, "msg" => "JRuby-echo:#{msg}" }
  ok = reply == expected
  puts "CLIENT(CRuby-txrx): seq=#{seq} sent=#{msg.bytesize}B reply=#{reply["msg"].bytesize}B " \
       "engine=#{reply["engine"]} match=#{ok}"
  raise "MISMATCH on seq #{seq}" unless ok
end

c2s.close
s2c.close
puts "CLIENT(CRuby-txrx): all messages round-tripped correctly"
