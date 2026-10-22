require 'socket'
def t(label) = (r = yield; puts "#{label.ljust(26)} #{r.inspect}") rescue puts("#{label.ljust(26)} #{$!.class}: #{$!.message[0,80]}")
[[:INET, :STREAM, 0], [:INET, :STREAM, Socket::IPPROTO_TCP], [:INET, :DGRAM, 0], [:INET6, :STREAM, 0], [:INET6, :DGRAM, 0]].each do |f, ty, pr|
  s = Socket.new(f, ty, pr)
  a = (s.local_address rescue $!)
  if a.is_a?(Addrinfo)
    puts "#{f} #{ty} #{pr}".ljust(26) + " #{a.inspect} afamily=#{a.afamily} pfamily=#{a.pfamily} socktype=#{a.socktype} protocol=#{a.protocol} ip=#{(a.ip_address rescue '-')} port=#{(a.ip_port rescue '-')}"
  else
    puts "#{f} #{ty} #{pr}".ljust(26) + " #{a.class}: #{a.message[0,80]}"
  end
  t("  getsockname unpack") { Socket.unpack_sockaddr_in(s.getsockname) }
  s.close
end
s = Socket.new(:INET, :STREAM); s.bind(Addrinfo.tcp("127.0.0.1", 0))
puts "bound INET".ljust(26) + " #{s.local_address.inspect} protocol=#{s.local_address.protocol}"
