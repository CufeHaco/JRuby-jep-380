#!/usr/bin/env ruby
# Two-process line chat over one UNIX socket.
#   ruby chat_demo.rb server
#   ruby chat_demo.rb client

require "socket"

path = ENV.fetch("SOCK", "/tmp/jep380-chat.sock")
mode = ARGV[0] || "server"

case mode
when "server"
  File.delete(path) if File.exist?(path)
  server = UNIXServer.new(path)
  peer = server.accept
  puts "peer connected"
when "client"
  peer = UNIXSocket.new(path)
else
  abort "usage: #{$PROGRAM_NAME} server|client"
end

reader = Thread.new do
  while (line = peer.gets)
    print "< #{line}"
  end
rescue IOError, Errno::EPIPE
end

begin
  while (line = $stdin.gets)
    peer.write(line)
  end
ensure
  peer.close
  reader.join
  File.delete(path) if mode == "server" && File.exist?(path)
end
