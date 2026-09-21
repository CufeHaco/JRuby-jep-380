#!/usr/bin/env ruby
# jruby_sockets.rb - Easy-to-use Unix socket helpers for JRuby and MRI
#
# Features:
# - Auto-cleanup of stale sockets
# - Auto-reconnect on errors
# - Simple high-level API
# - Signal handling (Ctrl+C)
# - Works on both MRI and JRuby
#
# Usage:
#   require_relative 'lib/jruby_sockets'
#
#   # Simple server
#   unix_server('/tmp/my.sock') { |msg, client|
#     client.send("Echo: #{msg}", 0)
#   }.start
#
#   # Simple client
#   client = unix_client('/tmp/my.sock')
#   puts client.request("hello")
#   client.close

require 'socket'

module JRubySockets
  VERSION = '1.0.0'

  # ================================================================
  # Server - Simple Unix socket server with auto-cleanup
  # ================================================================
  class Server
    attr_reader :path, :running, :clients_served

    def initialize(path, auto_cleanup: true)
      @path = path
      @server = nil
      @running = false
      @auto_cleanup = auto_cleanup
      @clients_served = 0
      @on_message = nil
      @on_connect = nil
      @on_error = nil

      setup_cleanup if @auto_cleanup
    end

    # Set message handler
    def on_message(&block)
      @on_message = block
      self
    end

    # Set connection handler
    def on_connect(&block)
      @on_connect = block
      self
    end

    # Set error handler (optional, defaults to continue)
    def on_error(&block)
      @on_error = block
      self
    end

    # Start server (blocking)
    def start
      cleanup_socket
      @server = UNIXServer.new(@path)
      @running = true

      while @running
        begin
          ready = IO.select([@server], nil, nil, 1.0)
          next unless ready

          client = @server.accept
          @on_connect.call(client) if @on_connect

          handle_client(client)

        rescue IOError
          break
        rescue => e
          if @on_error
            @on_error.call(e)
          else
            # Default: log and continue
            warn "Server error: #{e.message}"
          end
        end
      end

      stop
    end

    # Stop server
    def stop
      @running = false
      @server.close if @server && !@server.closed?
      cleanup_socket
    end

    private

    def setup_cleanup
      trap('INT') { stop; exit(0) }
      trap('TERM') { stop; exit(0) }
      at_exit { stop }
    end

    def cleanup_socket
      if File.exist?(@path)
        # Try to detect if socket is stale
        begin
          test = UNIXSocket.new(@path)
          test.close
          # Socket is alive, don't delete
          raise "Socket #{@path} is in use"
        rescue Errno::ECONNREFUSED, Errno::ENOENT
          # Stale socket, safe to delete
          File.delete(@path)
        end
      end
    end

    def handle_client(client)
      msg = client.recv(8192)
      return client.close if msg.nil? || msg.empty?

      # Auto-shutdown command
      if msg.strip.upcase == 'SHUTDOWN'
        client.send("Shutting down...\n", 0)
        client.close
        @running = false
        return
      end

      # Call user handler
      if @on_message
        @on_message.call(msg, client)
      else
        # Default echo
        client.send("Echo: #{msg}", 0)
      end

      @clients_served += 1
      client.close
    end
  end

  # ================================================================
  # Client - Simple Unix socket client with auto-reconnect
  # ================================================================
  class Client
    attr_reader :path, :socket

    def initialize(path, auto_reconnect: true, max_retries: 3)
      @path = path
      @socket = nil
      @auto_reconnect = auto_reconnect
      @max_retries = max_retries
      connect
    end

    # Connect (or reconnect)
    def connect
      retries = 0
      begin
        @socket = UNIXSocket.new(@path)
      rescue Errno::ECONNREFUSED, Errno::ENOENT => e
        retries += 1
        if @auto_reconnect && retries <= @max_retries
          sleep(0.1 * retries)
          retry
        else
          raise e
        end
      end
      self
    end

    # Send message
    def send(msg, flags = 0)
      begin
        @socket.send(msg, flags)
      rescue => e
        close
        connect if @auto_reconnect
        raise e unless @auto_reconnect
        @socket.send(msg, flags)
      end
    end

    # Receive message
    def recv(maxlen = 8192)
      begin
        @socket.recv(maxlen)
      rescue => e
        close
        connect if @auto_reconnect
        raise e unless @auto_reconnect
        @socket.recv(maxlen)
      end
    end

    # Send and receive (common pattern)
    def request(msg, maxlen = 8192)
      send(msg)
      recv(maxlen)
    end

    # Check if connected
    def connected?
      @socket && !@socket.closed?
    end

    # Close connection
    def close
      @socket.close if @socket && !@socket.closed?
      @socket = nil
    end

    # Reconnect
    def reconnect
      close
      connect
    end
  end

  # ================================================================
  # One-shot helpers for quick operations
  # ================================================================
  class << self
    # Quick server (one client then exit)
    def serve_once(path, &block)
      File.delete(path) if File.exist?(path)
      server = UNIXServer.new(path)
      client = server.accept
      msg = client.recv(8192)
      response = block.call(msg)
      client.send(response, 0) if response
      client.close
      server.close
      File.delete(path)
    end

    # Quick client request
    def request(path, message, maxlen = 8192)
      socket = UNIXSocket.new(path)
      socket.send(message, 0)
      response = socket.recv(maxlen)
      socket.close
      response
    end

    # Send shutdown command
    def shutdown(path)
      request(path, 'SHUTDOWN')
    end

    # Clean up stale socket file
    def cleanup(path)
      return unless File.exist?(path)

      begin
        sock = UNIXSocket.new(path)
        sock.close
        raise "Socket #{path} is in use, cannot clean up"
      rescue Errno::ECONNREFUSED, Errno::ENOENT
        # Stale socket
        File.delete(path)
      end
    end

    # Create server with auto-cleanup
    def server(path)
      cleanup(path) rescue nil
      UNIXServer.new(path)
    end

    # Create client with retries
    def client(path, retries: 3)
      attempts = 0
      begin
        UNIXSocket.new(path)
      rescue Errno::ECONNREFUSED, Errno::ENOENT => e
        attempts += 1
        if attempts < retries
          sleep 0.1
          retry
        else
          raise e
        end
      end
    end
  end
end

# ================================================================
# Convenience methods at top level
# ================================================================

def unix_server(path, **opts, &block)
  server = JRubySockets::Server.new(path, **opts)
  server.on_message(&block) if block_given?
  server
end

def unix_client(path, **opts)
  JRubySockets::Client.new(path, **opts)
end

# Aliases for backward compatibility
def clean_server(path)
  JRubySockets.server(path)
end

def clean_client(path)
  JRubySockets.client(path)
end
# ================================================================
# TCP Socket Support
# ================================================================

module JRubySockets
  # ================================================================
  # TCPServer - Simple TCP server with auto-cleanup
  # ================================================================
  class TCPServer
    attr_reader :host, :port, :running, :clients_served

    def initialize(host, port, auto_cleanup: true, **options)
      @host = host
      @port = port
      @server = nil
      @running = false
      @auto_cleanup = auto_cleanup
      @clients_served = 0
      @options = options
      @on_message = nil
      @on_connect = nil
      @on_error = nil

      setup_cleanup if @auto_cleanup
    end

    # Set message handler
    def on_message(&block)
      @on_message = block
      self
    end

    # Set connection handler
    def on_connect(&block)
      @on_connect = block
      self
    end

    # Set error handler
    def on_error(&block)
      @on_error = block
      self
    end

    # Start server (blocking)
    def start
      @server = ::TCPServer.new(@host, @port)
      @running = true

      while @running
        begin
          ready = IO.select([@server], nil, nil, 1.0)
          next unless ready

          client = @server.accept
          @on_connect.call(client) if @on_connect

          handle_client(client)

        rescue IOError
          break
        rescue => e
          if @on_error
            @on_error.call(e)
          else
            warn "Server error: #{e.message}"
          end
        end
      end

      stop
    end

    # Stop server
    def stop
      @running = false
      @server.close if @server && !@server.closed?
    end

    private

    def setup_cleanup
      trap('INT') { stop; exit(0) }
      trap('TERM') { stop; exit(0) }
      at_exit { stop }
    end

    def handle_client(client)
      msg = client.recv(8192)
      return client.close if msg.nil? || msg.empty?

      # Auto-shutdown command
      if msg.strip.upcase == 'SHUTDOWN'
        client.send("Shutting down...\n", 0)
        client.close
        @running = false
        return
      end

      # Call user handler
      if @on_message
        @on_message.call(msg, client)
      else
        # Default echo
        client.send("Echo: #{msg}", 0)
      end

      @clients_served += 1
      client.close
    end
  end

  # ================================================================
  # TCPClient - Simple TCP client with auto-reconnect
  # ================================================================
  class TCPClient
    attr_reader :host, :port, :socket

    def initialize(host, port, auto_reconnect: true, max_retries: 3, **options)
      @host = host
      @port = port
      @socket = nil
      @auto_reconnect = auto_reconnect
      @max_retries = max_retries
      @options = options
      connect
    end

    # Connect (or reconnect)
    def connect
      retries = 0
      begin
        @socket = ::TCPSocket.new(@host, @port, @options)
      rescue Errno::ECONNREFUSED, Errno::ETIMEDOUT => e
        retries += 1
        if @auto_reconnect && retries <= @max_retries
          sleep(0.1 * retries)
          retry
        else
          raise e
        end
      end
      self
    end

    # Send message
    def send(msg, flags = 0)
      begin
        @socket.send(msg, flags)
      rescue => e
        close
        connect if @auto_reconnect
        raise e unless @auto_reconnect
        @socket.send(msg, flags)
      end
    end

    # Receive message
    def recv(maxlen = 8192)
      begin
        @socket.recv(maxlen)
      rescue => e
        close
        connect if @auto_reconnect
        raise e unless @auto_reconnect
        @socket.recv(maxlen)
      end
    end

    # Send and receive (common pattern)
    def request(msg, maxlen = 8192)
      send(msg)
      recv(maxlen)
    end

    # Check if connected
    def connected?
      @socket && !@socket.closed?
    end

    # Close connection
    def close
      @socket.close if @socket && !@socket.closed?
      @socket = nil
    end

    # Reconnect
    def reconnect
      close
      connect
    end
  end

  # ================================================================
  # ConnectionPool - TCP connection pooling
  # ================================================================
  class ConnectionPool
    attr_reader :host, :port, :size, :connections

    def initialize(host, port, size: 10, **options)
      @host = host
      @port = port
      @size = size
      @options = options
      @connections = []
      @available = []
      @mutex = Mutex.new
      @condition = ConditionVariable.new

      # Pre-create connections
      @size.times { @available << create_connection }
    end

    # Execute block with a connection from the pool
    def with_connection(&block)
      conn = checkout
      begin
        result = block.call(conn)
        checkin(conn)
        result
      rescue => e
        # Connection failed, create new one
        conn.close rescue nil
        checkin(create_connection)
        raise e
      end
    end

    # Get a connection from the pool
    def checkout
      @mutex.synchronize do
        while @available.empty?
          @condition.wait(@mutex)
        end
        conn = @available.pop

        # Verify connection is still good
        unless conn && !conn.closed?
          conn = create_connection
        end

        conn
      end
    end

    # Return a connection to the pool
    def checkin(conn)
      @mutex.synchronize do
        @available << conn if conn && !conn.closed?
        @condition.signal
      end
    end

    # Close all connections in the pool
    def close_all
      @mutex.synchronize do
        @available.each do |conn|
          conn.close rescue nil
        end
        @available.clear
      end
    end

    # Get pool statistics
    def stats
      @mutex.synchronize do
        {
          size: @size,
          available: @available.size,
          in_use: @size - @available.size
        }
      end
    end

    private

    def create_connection
      ::TCPSocket.new(@host, @port)
    end
  end

  # ================================================================
  # One-shot TCP helpers
  # ================================================================
  class << self
    # Quick TCP request
    def tcp_request(host, port, message, maxlen = 8192, **options)
      socket = ::TCPSocket.new(host, port, options)
      socket.send(message, 0)
      response = socket.recv(maxlen)
      socket.close
      response
    end

    # Send shutdown command to TCP server
    def tcp_shutdown(host, port)
      tcp_request(host, port, 'SHUTDOWN')
    end
  end
end

# ================================================================
# TCP Convenience methods at top level
# ================================================================

def tcp_server(host, port, **opts, &block)
  server = JRubySockets::TCPServer.new(host, port, **opts)
  server.on_message(&block) if block_given?
  server
end

def tcp_client(host, port, **opts)
  JRubySockets::TCPClient.new(host, port, **opts)
end

def tcp_pool(host, port, size: 10, **opts)
  JRubySockets::ConnectionPool.new(host, port, size: size, **opts)
end
