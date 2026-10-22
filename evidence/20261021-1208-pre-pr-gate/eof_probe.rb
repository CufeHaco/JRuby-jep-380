require 'socket'
def t(label) = (r = yield; puts "#{label.ljust(36)} #{r.inspect}") rescue puts("#{label.ljust(36)} #{$!.class}")
def pair_tcp; s = TCPServer.new('127.0.0.1', 0); c = TCPSocket.new('127.0.0.1', s.addr[1]); a = s.accept; s.close; [a, c]; end
def pair_unix; p = "/tmp/kj380eof-#{$$}-#{rand(1e9)}.sock"; s = UNIXServer.new(p); c = UNIXSocket.new(p); a = s.accept; s.close; File.delete(p); [a, c]; end
{ 'tcp' => -> { pair_tcp }, 'unix named' => -> { pair_unix }, 'UNIXSocket.pair' => -> { UNIXSocket.pair },
  'Socket.socketpair' => -> { Socket.socketpair(:UNIX, :STREAM) } }.each do |kind, mk|
  a, b = mk.(); t("#{kind} recv_nonblock no data") { a.recv_nonblock(10) }
  t("#{kind} recv_nonblock no data ex:false") { a.recv_nonblock(10, exception: false) }
  t("#{kind} read_nonblock no data") { a.read_nonblock(10) }
  b.write("hi"); sleep 0.05; t("#{kind} recv_nonblock data") { a.recv_nonblock(10) }
  b.write("yo"); sleep 0.05; t("#{kind} read_nonblock data") { a.read_nonblock(10) }
  t("#{kind} write_nonblock until full") { n = 0; loop { n += b.write_nonblock("x" * 65536) }; n } 
  t("#{kind} write_nonblock full ex:false") { b.write_nonblock("x" * 65536, exception: false) }
  t("#{kind} drain") { got = 0; loop { got += a.read_nonblock(1 << 20).bytesize }; got > 0 }
  b.close; sleep 0.05
  t("#{kind} recv_nonblock EOF") { a.recv_nonblock(10) }
  t("#{kind} recv_nonblock EOF ex:false") { a.recv_nonblock(10, exception: false) }
  t("#{kind} read_nonblock EOF") { a.read_nonblock(10) }
  t("#{kind} read_nonblock EOF ex:false") { a.read_nonblock(10, exception: false) }
  t("#{kind} recv EOF") { a.recv(10) }
  a.close
end
u1, u2 = Socket.new(:INET, :DGRAM), Socket.new(:INET, :DGRAM); u1.bind(Addrinfo.udp('127.0.0.1', 0)); u2.connect(u1.local_address)
u2.send("", 0); sleep 0.05; t("udp empty datagram recv_nonblock") { u1.recv_nonblock(10) }
t("unix dgram empty recv_nonblock") { x, y = UNIXSocket.pair(:DGRAM); y.send("", 0); sleep 0.05; x.recv_nonblock(10) }
t("udp read_nonblock empty datagram") { u2.send("", 0); sleep 0.05; u1.read_nonblock(10) }
t("udp recv_nonblock no data") { u1.recv_nonblock(10) }
def pr(l, a) = puts("#{l.ljust(36)} #{a.inspect} protocol=#{a.protocol}")
s = TCPServer.new('127.0.0.1', 0); c = TCPSocket.new('127.0.0.1', s.addr[1])
pr("tcp local_address", c.local_address); pr("tcp remote_address", c.remote_address)
u = UDPSocket.new; u.connect('127.0.0.1', s.addr[1]); pr("udp local_address", u.local_address); pr("udp remote_address", u.remote_address)
s6 = TCPServer.new('::1', 0); c6 = TCPSocket.new('::1', s6.addr[1]); pr("tcp6 local_address", c6.local_address)
