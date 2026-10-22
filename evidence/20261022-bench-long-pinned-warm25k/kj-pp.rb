require "socket"
N = Integer(ENV.fetch("N", "50000")); size = Integer(ENV.fetch("SIZE", "100"))
path = "/tmp/kjp.#{Process.pid}.sock"
srv = UNIXServer.new(path); c = UNIXSocket.new(path); s = srv.accept
p = "X" * size
th = Thread.new { loop { m = s.recv(size); break if m.nil? || m.empty?; s.send(m, 0) } }
5.times { |r|
  lat = Array.new(N)
  N.times { |i| t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC); c.send(p, 0); c.recv(size); lat[i] = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0 }
  lat.sort!
  puts format("round%d p50=%.2fus p90=%.2fus p99=%.2fus", r, lat[N/2]*1e6, lat[N*9/10]*1e6, lat[N*99/100]*1e6) if r >= 2
}
c.close; th.join; File.delete(path)
