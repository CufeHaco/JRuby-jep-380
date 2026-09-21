#!/usr/bin/env ruby
# Ping-pong throughput on UNIXSocket. Not a product benchmark.
#   ruby benchmark.rb

require "socket"

def bench(label, n, size)
  path = "/tmp/jep380-bench.#{Process.pid}.sock"
  File.delete(path) if File.exist?(path)
  payload = "X" * size

  t = Thread.new do
    s = UNIXServer.new(path)
    c = s.accept
    n.times { c.send(c.recv(size + 16), 0) }
    c.close
    s.close
  end

  sleep 0.05 until File.exist?(path)
  c = UNIXSocket.new(path)
  t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  n.times { c.send(payload, 0); c.recv(size + 16) }
  dt = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0
  c.close
  t.join
  File.delete(path) if File.exist?(path)

  printf "%s  n=%d size=%d  %.1f msg/s  %.3f ms rtt\n",
         label, n, size, n / dt, (dt / n) * 1000
end

puts "#{RUBY_ENGINE} #{RUBY_VERSION}"
bench("100B", 1000, 100)
bench("1KB", 500, 1024)
bench("10KB", 100, 10_240)
