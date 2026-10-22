# JEP-380 pre-PR gate — after basicsocket nonblock/EOF + address-protocol fixes (2026-10-21)

Patched jar sha256 1cbca6fffe5680d9… (jruby-upstream == jruby-pr-candidate source). See INTERPRETER.md Landmine 17.

## Fixed in this build
Patch regressions:
- named UNIXSocket `recv_nonblock` with no data raised IOError (FFM read saw EAGAIN) -> EAGAIN
- `UNIXSocket.pair` `read_nonblock` always EAGAIN (data or EOF) -> real non-blocking read; EOF -> EOFError
- `UNIXSocket.pair` `recv_nonblock` at EOF returned "" -> nil; `write_nonblock` blocked -> EAGAIN when full
Pre-existing (stock too):
- `recv_nonblock` at EOF raised EAGAIN forever on every stream socket (basicsocket/recv_nonblock_spec hang) -> nil
- empty UDP datagram: recv_nonblock "" / read_nonblock EOFError; connected send("") now actually sends
- bound `local_address`/`remote_address` protocol 6/17 -> 0 like MRI

## Results
- eof_probe vs MRI: identical except `UNIXSocket.pair(:DGRAM)` (creates a stream pair; stock same, left as is).
- inet_probe vs MRI: 0 differences. addr_probe vs stock: only the unbound-INET case (stock NPE).
- mspec: unixsocket/unixserver 0F0E; socket/ 35F 174E (stock 35F 181E); addrinfo 9F (stock 10F); ancillarydata same.
- basicsocket: whole dir now completes on patched (24 files, 12 F, 234 E); stock still hangs (timeout), so the
  directory-level only-patched list in this run is meaningless. Per-file comparison (run separately):
  patched equal or better in every file; local_address/remote_address 0F (stock 3F each),
  connect_address 3F (stock 5F1E), getsockopt 6E (stock 10E), recv_nonblock 4F (stock hangs).
  Remaining basicsocket failures are identical on stock: recvmsg/sendmsg/getpeereid not implemented, ioctl String
  arg, send/recv flags, TTL/CORK/V6ONLY options, buffer encoding, some EDESTADDRREQ/ENOTCONN cases.
- Kestowv regression x3 each: identical every run. stress_uds 30.4M / stress 256.6M ops patched, 0 errors.
