require "socket"; require "timeout"
path = "/tmp/kjw.#{Process.pid}.sock"
srv = UNIXServer.new(path)
def pair(srv, path) = [UNIXSocket.new(path), srv.accept]
res = {}
c, s = pair(srv, path)
t = Thread.new { s.sysread(10) rescue $!.class }; sleep 0.2; st = t.status; t.raise(RuntimeError, "x"); res[:raise] = [st, t.value]
t = Thread.new { s.sysread(10) }; sleep 0.2; t.kill; res[:kill] = t.join(2) ? :joined : :hung
res[:timeout] = (Timeout.timeout(0.3) { s.sysread(10) } rescue $!.class)
t = Thread.new { s.sysread(10) rescue $!.class }; sleep 0.2; s.close; res[:close] = t.join(2) ? t.value : :hung
c.close; c, s = pair(srv, path)
t = Thread.new { s.sysread(5) }; sleep 0.2; c.write("hello"); res[:data] = t.value
c.close; res[:eof] = (s.sysread(5) rescue $!.class)
c, s = pair(srv, path); res[:read_nonblock] = s.read_nonblock(5, exception: false); c.write("ab"); sleep 0.05; res[:read_nonblock2] = s.read_nonblock(5)
res[:select_none] = IO.select([s], nil, nil, 0).inspect; c.write("z"); res[:select_ready] = IO.select([s], nil, nil, 1)&.first&.size
r, w = IO.pipe; t = Thread.new { r.sysread(3) }; sleep 0.1; w.write("abc"); res[:pipe] = t.value
res.each { |k, v| puts "#{k}: #{v.inspect}" }
File.delete(path)
