require "socket"
path = "/tmp/kjsr.#{Process.pid}.sock"
srv = UNIXServer.new(path); c = UNIXSocket.new(path); s = srv.accept
p = "X" * 100
t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
1_500_000.times { c.syswrite(p); s.sysread(100) }
puts (Process.clock_gettime(Process::CLOCK_MONOTONIC) - t0) / 1.5
File.delete(path)
