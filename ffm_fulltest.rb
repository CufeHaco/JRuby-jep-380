# Exercises every FFM (JEP-454) capability UnixDomainNative provides, not
# just the socketpair fast path exercised implicitly by UNIXSocket.pair.
# In particular, sendIo/recvIo (SCM_RIGHTS fd-passing via raw sendmsg(2)/
# recvmsg(2)) are not wired into any public Ruby method by this PR's
# patches yet -- calling them directly here proves the FFM implementation
# itself is correct, independent of whether/when it gets plumbed in.
require "socket"
require "java"

native = Java::OrgJrubyExtSocket::UnixDomainNative
domain = Java::OrgJrubyExtSocket::UnixDomain

puts "=== capability probe ==="
puts "UnixDomain.bits=#{domain.bits} hasJep380=#{domain.hasJep380} hasNativeFileno=#{domain.hasNativeFileno}"
puts "UnixDomainNative.bits=#{native.bits} available=#{native.available} hasPair=#{native.hasPair} " \
     "hasScm=#{native.hasScm} hasGetsockname=#{native.hasGetsockname} probeError=#{native.probeError.inspect}"

puts "\n=== UNIXSocket.pair (should use FFM socketpair(2), not the temp-path fallback) ==="
a, b = UNIXSocket.pair
a.send("via-ffm-socketpair", 0)
reply = b.recv(64)
puts "pair round-trip: #{reply.inspect} (fds: a=#{a.fileno} b=#{b.fileno})"
raise "pair round-trip failed" unless reply == "via-ffm-socketpair"

puts "\n=== UnixDomainNative.pathOf (FFM getsockname(2)) ==="
path = "/tmp/jep380-ffm-getsockname.sock"
File.delete(path) if File.exist?(path)
server = UNIXServer.new(path)
ffm_path = native.pathOf(server.fileno)
puts "bound path (Ruby-level)  = #{server.local_address.unix_path.inspect}"
puts "bound path (FFM getsockname) = #{ffm_path.inspect}"
raise "FFM getsockname mismatch" unless ffm_path == path
server.close
File.delete(path) if File.exist?(path)

puts "\n=== UnixDomainNative.sendIo/recvIo (FFM SCM_RIGHTS fd-passing) ==="
payload_path = "/tmp/jep380-ffm-scm-payload.txt"
File.write(payload_path, "fd passed over SCM_RIGHTS via raw FFM sendmsg/recvmsg")
passed_file = File.open(payload_path, "r")

c, d = UNIXSocket.pair
rc = native.sendIo(c.fileno, passed_file.fileno)
puts "sendIo returned #{rc} bytes"
received_fd = native.recvIo(d.fileno)
puts "recvIo returned fd=#{received_fd}"
raise "recvIo returned an invalid fd" if received_fd < 0

received_io = IO.for_fd(received_fd, "r")
content = received_io.read
puts "content read back through the passed fd: #{content.inspect}"
raise "SCM_RIGHTS fd-passing produced wrong content" unless content == File.read(payload_path)

received_io.close
passed_file.close
c.close
d.close
File.delete(payload_path)

puts "\nALL FFM CHECKS PASSED"
