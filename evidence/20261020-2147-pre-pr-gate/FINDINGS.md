# JEP-380 pre-PR gate rerun after address-helper fix (2026-10-21)

Fix: `UnixDomain.isUnixAddress` / `pathOf` accept `jnr.unixsocket.UnixSocketAddress` again,
for the two JNR holdouts (`Socket.new(:UNIX,:STREAM)`, `Socket.socketpair`). Mirrored to
JRuby-jep-380 and jruby-pr-candidate; INTERPRETER.md Landmine 2 updated. Patched jar sha256 dbb50eceec22dcb5…

- addr_probe: patched == stock on every UNIX line (holdout remote_address, getpeername,
  bound local_address, getsockname all return real paths again). Only remaining diff is the
  pre-existing unbound-AF_INET local_address case (stock NPE, patched wrong AF_UNIX, MRI 0.0.0.0).
- mspec: unixsocket/unixserver 0 F 0 E patched; ancillarydata, addrinfo identical or better;
  socket/ differs only by the 2 local_address AF_INET examples above. basicsocket hangs
  (300 s timeout) on BOTH runtimes — pre-existing, not patch-related.
- Kestowv regression x3 each: identical counts patched vs stock every run.
- stress_uds 30.0M ops / stress 258.7M ops patched, 0 errors (stock 29.2M / 226.2M, 0 errors).
