#!/usr/bin/env ruby
# Rigorous UNIXSocket round-trip benchmark for #9040 (JEP-380 vs JNR).
#
# Every implementation gets a warmup phase (JIT compilation, class loading,
# buffer allocation) that is discarded before the timed phase starts — a
# cold-start number is not a JRuby number. Reports throughput plus p50/p99
# latency, not just a mean, since a single average hides GC-pause tails.
#
#   irb  --noecho -f < benchmark_jep380_vs_jnr.rb     # MRI reference
#   jirb --noecho -f < benchmark_jep380_vs_jnr.rb     # stock JRuby (JNR)

require "socket"

# SOCK_STREAM gives no message boundaries: a single send/recv call is not
# guaranteed to move the whole payload once it's bigger than the socket's
# buffer. Drain fully in both directions or large sizes desync and EPIPE.
def send_all(sock, data)
  sent = 0
  sent += sock.send(data.byteslice(sent, 65536), 0) while sent < data.bytesize
end

def recv_all(sock, size)
  buf = +""
  buf << sock.recv(size - buf.bytesize) while buf.bytesize < size
  buf
end

def warmup_and_bench(label, size, warmup:, iterations:)
  path = "/tmp/jep380-bench.#{Process.pid}.#{rand(1 << 16)}.sock"
  File.delete(path) if File.exist?(path)
  payload = "X" * size
  total_rounds = warmup + iterations

  server = Thread.new do
    s = UNIXServer.new(path)
    c = s.accept
    total_rounds.times { send_all(c, recv_all(c, size)) }
    c.close
    s.close
  end

  sleep 0.01 until File.exist?(path)
  c = UNIXSocket.new(path)

  # Discarded: let the JIT compile the hot send/recv path before timing.
  warmup.times { send_all(c, payload); recv_all(c, size) }

  samples = Array.new(iterations)
  iterations.times do |i|
    t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    send_all(c, payload)
    recv_all(c, size)
    samples[i] = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0
  end

  c.close
  server.join
  File.delete(path) if File.exist?(path)

  samples.sort!
  total = samples.sum
  mean_ms = (total / samples.size) * 1000
  p50_ms  = samples[samples.size / 2] * 1000
  p99_ms  = samples[[(samples.size * 0.99).to_i, samples.size - 1].min] * 1000
  throughput = samples.size / total

  printf "%-6s size=%7d  n=%6d  %10.1f msg/s   mean=%7.4fms  p50=%7.4fms  p99=%7.4fms\n",
         label, size, iterations, throughput, mean_ms, p50_ms, p99_ms
end

engine = defined?(RUBY_ENGINE_VERSION) ? "#{RUBY_ENGINE} #{RUBY_ENGINE_VERSION}" : RUBY_VERSION
puts "#{engine}  (#{RUBY_PLATFORM})  pid=#{Process.pid}"
puts "-" * 92

[
  ["100B", 100],
  ["1KB", 1024],
  ["10KB", 10_240],
  ["100KB", 102_400],
].each do |label, size|
  warmup_and_bench(label, size, warmup: 500, iterations: 2000)
end
