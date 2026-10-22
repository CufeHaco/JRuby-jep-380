require "socket"
N = Integer(ENV.fetch("N", "200000"))
size = Integer(ENV.fetch("SIZE", "100"))
path = "/tmp/kjm.#{Process.pid}.sock"
srv = UNIXServer.new(path); c = UNIXSocket.new(path); s = srv.accept
pa, pb = UNIXSocket.pair
p = "X" * size
t = ->(name, a, b, &blk) {
  3.times { |r|
    t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    N.times { blk.(a, b) }
    dt = Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0
    puts format("%-24s round%d %.3f us/iter", name, r, dt / N * 1e6) if r > 0
  }
}
t.("named send/recv", c, s) { |a, b| a.send(p, 0); b.recv(size) }
t.("named syswrite/sysread", c, s) { |a, b| a.syswrite(p); b.sysread(size) }
t.("named write/readpartial", c, s) { |a, b| a.write(p); b.readpartial(size) }
t.("pair send/recv", pa, pb) { |a, b| a.send(p, 0); b.recv(size) }
File.delete(path)
