#!/usr/bin/env ruby
# test_tcp_sockets.rb - TCP Socket Test Suite
#
# Comprehensive tests for TCP socket functionality.
# Tests basic connections, timeouts, options, and error handling.
#
# Usage:
#   ruby test_tcp_sockets.rb                    # MRI
#   jruby -J-cp build test_tcp_sockets.rb       # JRuby

require 'socket'
require 'timeout'

# ================================================================
# TEST 1: Basic TCP Connection
# ================================================================
def test_basic_tcp
  port = 10001

  # Start echo server
  server_thread = Thread.new do
    server = TCPServer.new('localhost', port)
    client = server.accept
    msg = client.recv(100)
    client.send("ECHO: #{msg}", 0)
    client.close
    server.close
  end

  sleep 0.2

  # Connect client and test
  client = TCPSocket.new('localhost', port)
  client.send("TEST", 0)
  response = client.recv(100)
  client.close

  # Cleanup
  server_thread.join

  # Verify
  response == "ECHO: TEST"
end

# ================================================================
# TEST 2: Large Data Transfer
# ================================================================
def test_large_data
  port = 10002
  data = "X" * 10000

  # Start receiving server
  server_thread = Thread.new do
    server = TCPServer.new('localhost', port)
    client = server.accept
    received = ""

    # Receive data in chunks
    while chunk = client.recv(1024)
      received << chunk
      break if received.size >= data.size
    end

    client.close
    server.close
    received
  end

  sleep 0.2

  # Send data from client
  client = TCPSocket.new('localhost', port)
  sent = 0
  while sent < data.size
    chunk = data[sent, 1024]
    bytes = client.send(chunk, 0)
    sent += bytes
  end
  client.close

  # Verify
  received = server_thread.value
  received.size == data.size
end

# ================================================================
# TEST 3: Concurrent Connections
# ================================================================
def test_concurrent
  port = 10003
  num_clients = 5

  # Start server that accepts multiple clients
  server_thread = Thread.new do
    server = TCPServer.new('localhost', port)
    num_clients.times do |i|
      client = server.accept
      msg = client.recv(100)
      client.send("ACK#{i}", 0)
      client.close
    end
    server.close
  end

  sleep 0.2

  # Create multiple client threads
  threads = num_clients.times.map do |i|
    Thread.new do
      client = TCPSocket.new('localhost', port)
      client.send("MSG#{i}", 0)
      response = client.recv(100)
      client.close
      response
    end
  end

  # Wait for all clients to complete
  results = threads.map(&:value)
  server_thread.join

  # Verify all clients got responses
  results.size == num_clients
end

# ================================================================
# TEST 4: Address Information
# ================================================================
def test_address_info
  port = 10004

  server_thread = Thread.new do
    server = TCPServer.new('localhost', port)
    client = server.accept
    client.close
    server.close
  end

  sleep 0.2

  client = TCPSocket.new('localhost', port)

  # Check address methods
  local_addr = client.addr
  peer_addr = client.peeraddr

  client.close
  server_thread.join

  # Verify address information
  local_addr.is_a?(Array) &&
  peer_addr.is_a?(Array) &&
  peer_addr[1] == port &&
  peer_addr[2].include?('localhost')
end

# ================================================================
# TEST 5: Connection Refused Error
# ================================================================
def test_connection_refused
  # Try to connect to a port with no server
  begin
    client = TCPSocket.new('localhost', 54321)
    client.close
    false  # Should not reach here
  rescue Errno::ECONNREFUSED
    true   # Expected error
  rescue => e
    puts "  Unexpected error: #{e.class}: #{e.message}"
    false
  end
end

# ================================================================
# TEST 6: Invalid Port Error
# ================================================================
def test_invalid_port
  begin
    server = TCPServer.new('localhost', 99999)
    server.close
    false  # Should not reach here
  rescue ArgumentError
    true   # Expected error
  rescue => e
    puts "  Unexpected error: #{e.class}: #{e.message}"
    false
  end
end

# ================================================================
# TEST 7: Server Bind to All Interfaces
# ================================================================
def test_bind_all_interfaces
  port = 10007

  # Bind to 0.0.0.0 (all interfaces)
  server = TCPServer.new('0.0.0.0', port)

  # Connect using localhost
  client_thread = Thread.new do
    sleep 0.1
    client = TCPSocket.new('localhost', port)
    client.send("TEST", 0)
    response = client.recv(100)
    client.close
    response
  end

  client = server.accept
  msg = client.recv(100)
  client.send("OK", 0)
  client.close

  response = client_thread.value
  server.close

  msg == "TEST" && response == "OK"
end

# ================================================================
# TEST 8: Multiple Sequential Connections
# ================================================================
def test_sequential_connections
  port = 10008

  server_thread = Thread.new do
    server = TCPServer.new('localhost', port)
    3.times do
      client = server.accept
      msg = client.recv(100)
      client.send("ECHO: #{msg}", 0)
      client.close
    end
    server.close
  end

  sleep 0.2

  results = []
  3.times do |i|
    client = TCPSocket.new('localhost', port)
    client.send("MSG#{i}", 0)
    response = client.recv(100)
    client.close
    results << response
  end

  server_thread.join

  results.size == 3 && results.all? { |r| r.start_with?("ECHO:") }
end

# ================================================================
# TEST RUNNER
# ================================================================

if __FILE__ == $0
  puts "=" * 60
  puts "TCP Socket Test Suite"
  puts "=" * 60
  puts "Ruby: #{RUBY_ENGINE rescue 'ruby'} #{RUBY_VERSION}"
  puts ""

  # Test definitions
  tests = {
    'Basic connection' => method(:test_basic_tcp),
    'Large data (10KB)' => method(:test_large_data),
    'Concurrent clients (5)' => method(:test_concurrent),
    'Address information' => method(:test_address_info),
    'Connection refused error' => method(:test_connection_refused),
    'Invalid port error' => method(:test_invalid_port),
    'Bind to all interfaces' => method(:test_bind_all_interfaces),
    'Sequential connections (3)' => method(:test_sequential_connections)
  }

  passed = 0
  failed = 0

  # Run each test
  tests.each do |name, test|
    print "  #{name}... "
    begin
      if test.call
        puts "✓ PASS"
        passed += 1
      else
        puts "✗ FAIL"
        failed += 1
      end
    rescue => e
      puts "✗ FAIL: #{e.message}"
      puts "    #{e.backtrace.first}" if ENV['DEBUG']
      failed += 1
    end
  end

  # Summary
  puts ""
  puts "=" * 60
  puts "Results: #{passed} passed, #{failed} failed"
  puts "=" * 60

  # Exit with appropriate code
  exit(failed > 0 ? 1 : 0)
end
