#!/usr/bin/env ruby
# Regenerates asm_data.inc for msgpack_asm_client.asm: precomputed
# MessagePack frame bytes for the 3 test messages, plus the expected
# echoed-reply payload for each. The assembly client only does the
# genuinely dynamic part (real-time reply parsing/verification) -- see
# the comment at the top of msgpack_asm_client.asm.
#   ruby gen_asm_data.rb > asm_data.inc
#   ruby gen_asm_data.rb txrx > asm_data_txrx.inc

def mp_str(s)
  len = s.bytesize
  if len <= 31
    [0xa0 | len].pack("C") + s
  elsif len <= 255
    [0xd9, len].pack("CC") + s
  else
    [0xda, len].pack("Cn") + s
  end
end

def frame(seq, engine, msg)
  payload = +""
  payload << [0x83].pack("C")
  payload << mp_str("engine")
  payload << mp_str(engine)
  payload << mp_str("seq")
  payload << [seq & 0x7f].pack("C")
  payload << mp_str("msg")
  payload << mp_str(msg)
  [payload.bytesize].pack("N") + payload
end

def nasm_bytes(name, bytes)
  lines = bytes.each_byte.each_slice(20).map { |chunk| "    db " + chunk.map(&:to_s).join(", ") }
  puts "#{name}:"
  puts lines.join("\n")
  puts "#{name}_len: equ $-#{name}"
  puts
end

messages = if ARGV[0] == "txrx"
  [
    "hello #0 from asm txrx",
    "second message from asm txrx",
    "X" * 5000
  ]
else
  [
    "hello #0 from asm",
    "second message from asm",
    "X" * 5000
  ]
end

messages.each_with_index do |msg, i|
  f = frame(i, "C", msg)
  nasm_bytes("out_frame#{i}", f)
  echo = "JRuby-echo:#{msg}"
  nasm_bytes("expected_msg#{i}", echo)
end
