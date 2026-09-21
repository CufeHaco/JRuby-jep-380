#!/usr/bin/env ruby
# One-process TCPServer / TCPSocket ping-pong. Not part of #9040.
#   ruby tcp_demo.rb

require "socket"

host = "127.0.0.1"
port = 0

server = TCPServer.new(host, port)
port = server.addr[1]

t = Thread.new do
  c = server.accept
  c.send("echo:#{c.recv(1024)}", 0)
  c.close
  server.close
end

c = TCPSocket.new(host, port)
c.send("hello", 0)
puts c.recv(1024)
c.close
t.join
