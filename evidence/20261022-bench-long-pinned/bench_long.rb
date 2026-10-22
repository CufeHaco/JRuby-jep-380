#!/usr/bin/env ruby
# Longer UNIXSocket round-trip benchmark (JEP-380 vs JNR): every size is warmed
# up in one discarded pass first (so the first size measured isn't still JIT-cold),
# then timed in a shuffled order; the seed comes from BENCH_SEED and is printed.
require "socket"

SIZES = { "100B" => 100, "1KB" => 1024, "10KB" => 10_240, "100KB" => 102_400 }
WARMUP = Integer(ENV.fetch("BENCH_WARMUP", "2000"))
ITERS  = Integer(ENV.fetch("BENCH_ITERS", "5000"))
seed   = Integer(ENV.fetch("BENCH_SEED", rand(1 << 30).to_s))
order  = SIZES.keys.shuffle(random: Random.new(seed))

def send_all(sock, data)
  sent = 0
  sent += sock.send(data.byteslice(sent, 65536), 0) while sent < data.bytesize
end

def recv_all(sock, size)
  buf = +""
  buf << sock.recv(size - buf.bytesize) while buf.bytesize < size
  buf
end

def run(size, rounds, timed)
  path = "/tmp/jep380-long.#{Process.pid}.#{rand(1 << 30)}.sock"
  File.delete(path) if File.exist?(path)
  payload = "X" * size
  ready = Queue.new
  server = Thread.new do
    s = UNIXServer.new(path)
    ready << true # listening: the socket file exists from bind(2), before listen(2)
    c = s.accept
    rounds.times { send_all(c, recv_all(c, size)) }
    c.close
    s.close
  end
  ready.pop
  c = UNIXSocket.new(path)
  samples = timed ? Array.new(rounds) : nil
  rounds.times do |i|
    t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC) if timed
    send_all(c, payload)
    recv_all(c, size)
    samples[i] = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0 if timed
  end
  c.close
  server.join
  File.delete(path) if File.exist?(path)
  samples
end

engine = defined?(RUBY_ENGINE_VERSION) ? "#{RUBY_ENGINE} #{RUBY_ENGINE_VERSION}" : RUBY_VERSION
puts "#{engine} seed=#{seed} order=#{order.join(',')} warmup=#{WARMUP} iters=#{ITERS}"

SIZES.each_value { |size| run(size, WARMUP, false) } # discarded warm-up pass, all sizes

order.each do |label|
  samples = run(SIZES.fetch(label), ITERS, true).sort
  total = samples.sum
  printf "%-6s msg/s=%.1f p50=%.5f p99=%.5f\n", label, samples.size / total,
         samples[samples.size / 2] * 1000, samples[[(samples.size * 0.99).to_i, samples.size - 1].min] * 1000
end
