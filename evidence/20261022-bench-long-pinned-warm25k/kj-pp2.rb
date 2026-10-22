require "socket"
N = 5000; size = 100; p = "X" * size
def conn(size)
  path = "/tmp/kjp2.#{Process.pid}.#{rand(1<<30)}.sock"
  srv = UNIXServer.new(path); c = UNIXSocket.new(path); s = srv.accept
  th = Thread.new { loop { m = s.recv(size); break if m.nil? || m.empty?; s.send(m, 0) } }
  [c, th, path, srv]
end
12.times { |r|
  c, th, path, srv = conn(size)
  lat = Array.new(N)
  N.times { |i| t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC); c.send(p, 0); c.recv(size); lat[i] = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0 }
  lat.sort!
  print format("c%d:%.1f ", r, lat[N/2]*1e6)
  c.close; th.join; srv.close; File.delete(path)
}
puts
