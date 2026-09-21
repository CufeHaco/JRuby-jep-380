#!/usr/bin/env ruby
# One-process UNIXServer / UNIXSocket ping-pong.
#   ruby demo.rb
#   jruby demo.rb

require "socket"

path = "/tmp/jep380-demo.#{Process.pid}.sock"
File.delete(path) if File.exist?(path)

server_thread = Thread.new do
  server = UNIXServer.new(path)
  client = server.accept
  msg = client.recv(1024)
  client.send("echo:#{msg}", 0)
  client.close
  server.close
end

begin
  sleep 0.05 until File.exist?(path)
  sock = UNIXSocket.new(path)
  sock.send("hello", 0)
  puts sock.recv(1024)
  sock.close
  server_thread.join
ensure
  File.delete(path) if File.exist?(path)
end
