#!/usr/bin/env ruby
# UNIXSocket / UNIXServer checks against whatever the runtime ships.
#   ruby test_sockets.rb
#   jruby test_sockets.rb

require "socket"

def with_sock
  path = "/tmp/test_uds.#{Process.pid}.#{rand(1 << 16)}.sock"
  File.delete(path) if File.exist?(path)
  yield path
ensure
  File.delete(path) if File.exist?(path)
end

def test_basic
  with_sock do |path|
    t = Thread.new do
      server = UNIXServer.new(path)
      c = server.accept
      c.send("ECHO:#{c.recv(100)}", 0)
      c.close
      server.close
    end
    sleep 0.05 until File.exist?(path)
    c = UNIXSocket.new(path)
    c.send("TEST", 0)
    ok = c.recv(100) == "ECHO:TEST"
    c.close
    t.join
    ok
  end
end

def test_large_data
  data = "X" * 10_000
  with_sock do |path|
    t = Thread.new do
      server = UNIXServer.new(path)
      c = server.accept
      received = +""
      while received.bytesize < data.bytesize
        chunk = c.recv(1024)
        break if chunk.nil? || chunk.empty?
        received << chunk
      end
      c.close
      server.close
      received
    end
    sleep 0.05 until File.exist?(path)
    c = UNIXSocket.new(path)
    sent = 0
    while sent < data.bytesize
      n = c.send(data.byteslice(sent, 1024), 0)
      sent += n
    end
    c.close
    t.value.bytesize == data.bytesize
  end
end

def test_concurrent
  n = 5
  with_sock do |path|
    t = Thread.new do
      server = UNIXServer.new(path)
      n.times do
        c = server.accept
        c.send("ACK:#{c.recv(100)}", 0)
        c.close
      end
      server.close
    end
    sleep 0.05 until File.exist?(path)
    results = n.times.map do |i|
      Thread.new do
        c = UNIXSocket.new(path)
        c.send("MSG#{i}", 0)
        r = c.recv(100)
        c.close
        r
      end
    end.map(&:value)
    t.join
    results.size == n && results.all? { |r| r.start_with?("ACK:") }
  end
end

if $PROGRAM_NAME == __FILE__
  tests = {
    "basic" => method(:test_basic),
    "large 10KB" => method(:test_large_data),
    "concurrent 5" => method(:test_concurrent)
  }
  failed = 0
  tests.each do |name, fn|
    ok = begin
      fn.call
    rescue => e
      warn "#{name}: #{e.class}: #{e.message}"
      false
    end
    puts "#{ok ? "ok" : "FAIL"}  #{name}"
    failed += 1 unless ok
  end
  exit(failed.zero? ? 0 : 1)
end
