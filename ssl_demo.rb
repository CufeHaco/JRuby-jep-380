#!/usr/bin/env ruby
# ssl_demo.rb - SSL/TLS Socket Demonstration
#
# Shows how to use SSL/TLS for encrypted TCP communication
# using Ruby's OpenSSL library.
#
# Usage:
#   Terminal 1: ruby ssl_demo.rb server
#   Terminal 2: ruby ssl_demo.rb client
#
# Features demonstrated:
# - SSL/TLS server with certificates
# - SSL/TLS client connection
# - Encrypted communication
# - Certificate verification options

require 'socket'
require 'openssl'

HOST = 'localhost'
PORT = 8443

# Generate self-signed certificate for demo
def generate_self_signed_cert
  # Generate RSA key
  key = OpenSSL::PKey::RSA.new(2048)

  # Create certificate
  cert = OpenSSL::X509::Certificate.new
  cert.version = 2
  cert.serial = 1
  cert.subject = OpenSSL::X509::Name.parse("/C=US/ST=State/L=City/O=Demo/CN=#{HOST}")
  cert.issuer = cert.subject
  cert.public_key = key.public_key
  cert.not_before = Time.now
  cert.not_after = Time.now + (365 * 24 * 60 * 60) # 1 year

  # Sign certificate
  cert.sign(key, OpenSSL::Digest.new('SHA256'))

  [cert, key]
end

def run_server
  puts "=" * 60
  puts "SSL/TLS Demo - Server"
  puts "=" * 60
  puts
  puts "Generating self-signed certificate..."

  # Generate certificate for demo
  cert, key = generate_self_signed_cert

  puts "Certificate generated:"
  puts "  Subject: #{cert.subject}"
  puts "  Valid from: #{cert.not_before}"
  puts "  Valid until: #{cert.not_after}"
  puts

  # Create SSL context
  ssl_context = OpenSSL::SSL::SSLContext.new
  ssl_context.cert = cert
  ssl_context.key = key
  ssl_context.ssl_version = :TLSv1_2_server

  # Create TCP server
  tcp_server = TCPServer.new(HOST, PORT)
  ssl_server = OpenSSL::SSL::SSLServer.new(tcp_server, ssl_context)

  puts "SSL/TLS server listening on #{HOST}:#{PORT}"
  puts "Using TLS 1.2 protocol"
  puts "Press Ctrl+C to stop"
  puts

  begin
    loop do
      # Accept SSL connection
      puts "Waiting for client..."
      ssl_client = ssl_server.accept

      puts "Client connected!"
      puts "  Cipher: #{ssl_client.cipher[0]}"
      puts "  Protocol: #{ssl_client.ssl_version}"
      puts

      # Read message
      message = ssl_client.gets
      puts "Received (encrypted): #{message.strip}"

      # Send response
      response = "Echo (encrypted): #{message}"
      ssl_client.puts(response)
      puts "Sent: #{response.strip}"
      puts

      ssl_client.close
    end
  rescue Interrupt
    puts "\nShutting down server..."
  ensure
    ssl_server.close rescue nil
    tcp_server.close rescue nil
  end
end

def run_client
  puts "=" * 60
  puts "SSL/TLS Demo - Client"
  puts "=" * 60
  puts

  begin
    puts "Connecting to #{HOST}:#{PORT}..."

    # Create TCP socket
    tcp_socket = TCPSocket.new(HOST, PORT)

    # Create SSL context
    ssl_context = OpenSSL::SSL::SSLContext.new
    ssl_context.ssl_version = :TLSv1_2_client

    # For demo purposes, don't verify certificate
    # In production, you should verify certificates!
    ssl_context.verify_mode = OpenSSL::SSL::VERIFY_NONE

    # Wrap socket with SSL
    ssl_socket = OpenSSL::SSL::SSLSocket.new(tcp_socket, ssl_context)
    ssl_socket.sync_close = true
    ssl_socket.connect

    puts "SSL/TLS connection established!"
    puts "  Cipher: #{ssl_socket.cipher[0]}"
    puts "  Protocol: #{ssl_socket.ssl_version}"
    puts

    # Send message
    message = "Hello from SSL client!"
    puts "Sending (encrypted): #{message}"
    ssl_socket.puts(message)

    # Receive response
    response = ssl_socket.gets
    puts "Received (encrypted): #{response.strip}"
    puts

    ssl_socket.close
    puts "Connection closed successfully"

  rescue => e
    puts "Error: #{e.message}"
    puts e.backtrace.first(5)
  end
end

def run_info
  puts "=" * 60
  puts "SSL/TLS Demo - Information"
  puts "=" * 60
  puts

  puts "OpenSSL Version: #{OpenSSL::OPENSSL_VERSION}"
  puts "OpenSSL Library Version: #{OpenSSL::OPENSSL_LIBRARY_VERSION}"
  puts

  puts "Available SSL/TLS methods:"
  OpenSSL::SSL::SSLContext::METHODS.each do |method|
    puts "  - #{method}"
  end
  puts

  puts "Available ciphers: #{OpenSSL::Cipher.ciphers.size} total"
  puts "Sample ciphers:"
  OpenSSL::Cipher.ciphers.first(10).each do |cipher|
    puts "  - #{cipher}"
  end
  puts
end

def run_benchmark
  puts "=" * 60
  puts "SSL/TLS Performance Benchmark"
  puts "=" * 60
  puts

  # Start server in background thread
  server_thread = Thread.new do
    cert, key = generate_self_signed_cert
    ssl_context = OpenSSL::SSL::SSLContext.new
    ssl_context.cert = cert
    ssl_context.key = key
    ssl_context.ssl_version = :TLSv1_2_server

    tcp_server = TCPServer.new(HOST, PORT)
    ssl_server = OpenSSL::SSL::SSLServer.new(tcp_server, ssl_context)

    loop do
      ssl_client = ssl_server.accept
      message = ssl_client.gets
      ssl_client.puts("Response: #{message}")
      ssl_client.close
    end
  end

  # Give server time to start
  sleep 1

  puts "Benchmarking SSL/TLS connections..."
  iterations = 50

  start_time = Time.now

  iterations.times do |i|
    tcp_socket = TCPSocket.new(HOST, PORT)
    ssl_context = OpenSSL::SSL::SSLContext.new
    ssl_context.verify_mode = OpenSSL::SSL::VERIFY_NONE
    ssl_socket = OpenSSL::SSL::SSLSocket.new(tcp_socket, ssl_context)
    ssl_socket.connect
    ssl_socket.puts("Request #{i}")
    ssl_socket.gets
    ssl_socket.close
  end

  elapsed = Time.now - start_time

  puts "Results:"
  puts "  Iterations: #{iterations}"
  puts "  Total time: #{elapsed.round(3)}s"
  puts "  Average per connection: #{(elapsed / iterations * 1000).round(2)}ms"
  puts "  Connections per second: #{(iterations / elapsed).round(1)}"
  puts

  # Cleanup
  server_thread.kill
  puts "Benchmark complete!"
end

# Main
case ARGV[0]
when 'server'
  run_server
when 'client'
  run_client
when 'info'
  run_info
when 'benchmark'
  run_benchmark
else
  puts "Usage: ruby ssl_demo.rb [server|client|info|benchmark]"
  puts
  puts "Examples:"
  puts "  Terminal 1: ruby ssl_demo.rb server"
  puts "  Terminal 2: ruby ssl_demo.rb client"
  puts
  puts "  ruby ssl_demo.rb info       # Show SSL/TLS information"
  puts "  ruby ssl_demo.rb benchmark  # Run performance test"
  exit 1
end
