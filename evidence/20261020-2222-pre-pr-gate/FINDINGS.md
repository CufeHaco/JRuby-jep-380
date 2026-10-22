# JEP-380 pre-PR gate — after address-helper + unbound-INET local_address fixes (2026-10-21)

Patched jar sha256 8d1860bc2a7eb223… (jruby-upstream == jruby-pr-candidate source).

Fixes in this build:
1. `UnixDomain.isUnixAddress`/`pathOf` accept JNR's `UnixSocketAddress` (holdout sockets).
2. Unbound inet `Socket#local_address` / `getsockname` report the family wildcard like MRI:
   `RubyBasicSocket.unboundInetAddress()` hook (RubySocket overrides from `soDomain`),
   `Addrinfo.unboundLocal` (protocol 0), `Addrinfo#inspect` labels protocol-0 inet by socktype.
   Fragments: patches/RubyBasicSocket_unixAddr.java, RubySocket_unboundInet.java, Addrinfo_unboundLocal.java.

Results:
- inet_probe vs MRI: identical for INET/INET6 x STREAM/DGRAM unbound (inspect, afamily, pfamily,
  socktype, protocol, ip, port, getsockname). Only diff: an already-bound socket's protocol
  (JRuby 6, MRI 0) — unchanged stock behavior, out of scope.
- addr_probe vs stock: identical on all UNIX lines; only diff is unbound INET (stock NPE, patched 0.0.0.0 TCP).
- mspec: no example fails on patched that passes on stock, in any directory.
  unixsocket/unixserver 0 F 0 E (stock 2F4E / 2F1E); socket/ 35 F 174 E (stock 35 F 181 E);
  ancillarydata identical; addrinfo 9 F (stock 10). basicsocket skipped: hangs on both runtimes.
- Kestowv regression x3: patched identical all 3 runs; stock run 2 had 5 extra FAILs (known flaky
  teardown/rescue set), runs 1 and 3 identical to patched.
- stress_uds 30.2M / stress 263.4M ops patched, 0 errors (stock 29.6M / 254.9M, 0 errors).
