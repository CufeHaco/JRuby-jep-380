#!/usr/bin/env ruby
# First process binds /tmp/unix_hub.sock; later processes join.
#   irb -r ./unix_hub.rb
#   hub_send(user, msg); hub_broadcast(msg); hub_online

require "fileutils"
require "thread"
require "set"
require "socket"

module UnixHub
  USER = (ENV["USER"] || "user").gsub(/[^\w]/, "_")
  PATH = "/tmp/unix_hub.sock".freeze

  @clients   = Set.new
  @listeners = Set.new
  @mutex     = Mutex.new
  @server    = nil
  @client    = nil
  @is_server = false

  def self.start_server!
    return if File.exist?(PATH)
    @server = UNIXServer.new(PATH)
    File.chmod(0o666, PATH)
    Thread.new { server_loop }
    sleep 0.2
  end

  def self.connect!
    return if @client && !@client.closed?
    100.times do
      begin
        @client = UNIXSocket.new(PATH)
        @client.send("JOIN:#{USER}\n", 0)
        Thread.new { read_loop }
        return
      rescue Errno::ECONNREFUSED, IOError
        sleep 0.1
      end
    end
    raise "Failed to connect to UnixHub"
  end

  def self.read_line(s)
    data = s.recv(8192)
    return nil if data.nil? || data.empty?
    data.split("\n", 2).first
  end

  def self.server_loop
    loop { Thread.new { handle_client(@server.accept) } }
  end

  def self.read_loop
    loop do
      line = read_line(@client)
      break unless line
      handle_message(line)
    end
  rescue
    reconnect!
  end

  def self.reconnect!
    @client&.close
    sleep 0.3
    connect!
  end

  def self.handle_message(msg)
    case msg
    when /^MSG:([^:]+):(.*)/ then @listeners.each { |b| b.call($1, $2) }
    when /^JOIN:(\w+)/       then @clients.add($1) unless $1 == USER
    when /^LEAVE:(\w+)/      then @clients.delete($1)
    end
  end

  def self.handle_client(sock)
    line = read_line(sock)
    return sock.close unless line
    ident = line[/:(.*)/, 1]
    return sock.close unless ident

    @clients.add(ident)
    broadcast("JOIN:#{ident}", except: ident)

    loop do
      line = read_line(sock)
      break unless line
      broadcast(line, except: ident) if line.start_with?("MSG:")
    end
  ensure
    @clients.delete(ident) if ident
    broadcast("LEAVE:#{ident}") if ident
    sock.close
  end

  def self.broadcast(msg, except: nil)
    return unless @client && !@client.closed?
    @client.send("#{msg}\n", 0) unless except == USER
  end

  def self.send(to, text)      = broadcast("MSG:#{to}:#{text}")
  def self.broadcast_all(text) = broadcast("MSG:*:#{text}")
  def self.on_message(&b)      = @listeners.add(b)
  def self.online              = @clients.to_a
  def self.me                  = USER
  def self.server?             = @is_server

  @mutex.synchronize do
    if File.exist?(PATH)
      connect!
    else
      @is_server = true
      start_server!
      connect!
    end
  end

  at_exit { @client&.close }
end

def hub_send(to, msg)     = UnixHub.send(to, msg)
def hub_broadcast(msg)    = UnixHub.broadcast_all(msg)
def hub_on(&b)            = UnixHub.on_message(&b)
def hub_online            = UnixHub.online
def hub_me                = UnixHub.me
def hub_server?           = UnixHub.server?

true
