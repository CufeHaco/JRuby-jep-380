require "socket"
# block-form pairs (upstream) on FFM pairs (ours)
r = UNIXSocket.pair { |a, b| a.write("x"); [b.read(1), a.class] }; puts "UNIXSocket.pair block: #{r.inspect}"
r = UNIXSocket.pair(:DGRAM) { |a, b| a.send("dg", 0); b.recv(10) }; puts "UNIXSocket.pair(:DGRAM) block: #{r.inspect}"
r = Socket.pair(:UNIX, :STREAM) { |a, b| a.write("y"); b.read(1) }; puts "Socket.pair block: #{r.inspect}"
s0 = nil; Socket.pair(:UNIX, :STREAM) { |a, b| s0 = a }; puts "pair closed after block: #{s0.closed?}"
path = "/tmp/kjs.#{Process.pid}.sock"; srv = UNIXServer.new(path)
c = UNIXSocket.new(path); fd = srv.sysaccept; s = Socket.for_fd(fd); c.write("z"); puts "sysaccept + for_fd: #{s.read(1).inspect} chan=#{c.to_java.getChannel.java_class.name rescue '-'}"
c2 = UNIXSocket.new(path); a = srv.accept; puts "accept: #{a.class} #{a.to_java.getChannel.java_class.name rescue '-'}"
File.delete(path)
if Fiber.respond_to?(:set_scheduler)
  begin
    require "fiber"
    sched_lib = Dir[File.join(File.dirname(RbConfig.ruby), "../spec/ruby/core/fiber/fixtures/scheduler.rb")].first ||
                Dir["#{ENV['U']}/spec/ruby/**/fiber/fixtures/scheduler.rb"].first || Dir["#{ENV['U']}/spec/**/scheduler.rb"].first
    puts "scheduler fixture: #{sched_lib.inspect}"
  end
end
