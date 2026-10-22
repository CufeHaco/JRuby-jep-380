require 'socket'
def t(label) = (r = yield; puts "#{label.ljust(34)} #{r.inspect}") rescue puts("#{label.ljust(34)} #{$!.class}: #{$!.message[0,70]}")
path = "/tmp/kj380addr-#{$$}.sock"
srv = UNIXServer.new(path)
c = Socket.new(:UNIX, :STREAM); c.connect(Socket.pack_sockaddr_un(path)); a = srv.accept
t("holdout client remote_address") { c.remote_address.inspect }
t("holdout client getpeername")    { Socket.unpack_sockaddr_un(c.getpeername) }
t("holdout client local_address")  { c.local_address.inspect }
b = Socket.new(:UNIX, :STREAM); bp = path + ".b"; b.bind(Socket.pack_sockaddr_un(bp))
t("holdout bound local_address")   { b.local_address.inspect }
t("holdout bound getsockname")     { Socket.unpack_sockaddr_un(b.getsockname) }
x, y = Socket.socketpair(:UNIX, :STREAM)
t("socketpair local_address")      { x.local_address.inspect }
u = UNIXSocket.new(path); srv.accept
t("UNIXSocket#peeraddr")           { u.peeraddr }
t("UNIXSocket#remote_address")     { u.remote_address.inspect }
t("UNIXServer#local_address")      { srv.local_address.inspect }
t("UNIXServer#path")               { srv.path }
t("unbound INET local_address")    { Socket.new(:INET, :STREAM).local_address.inspect }
[path, bp].each { File.delete(_1) rescue nil }
