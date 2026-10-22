# JEP-380 pre-PR gate on valenthree — findings (2026-10-21)

Patched = jruby-upstream 10.1.2.0-SNAPSHOT (#9040 scope, same core/src as jruby-pr-candidate).
Stock = /usr/local/bin/jruby 10.1.0.0 (JNR). MRI = ruby 4.0.5. 40 logical CPUs.

## Clean (no patch-attributable difference)
- Kestowv regression_suite x3 each, alternating: identical counts every run, same known-flaky set.
- stress_uds.rb: 29.5M ops patched / 30.3M stock, 0 errors both.
- stress.rb: 257M ops patched / 248M stock, 0 errors both.
- mspec unixsocket 63 ex / 0 F 0 E patched (stock: 2 F 4 E); unixserver 0/0 patched (stock 2 F 1 E);
  ancillarydata and addrinfo identical failure sets (addrinfo: patched has 1 fewer failure).

## REGRESSION — address methods on the two JNR holdout sockets
`Socket.new(:UNIX, :STREAM)` and `Socket.socketpair` still return JNR channels, whose
addresses are `jnr.unixsocket.UnixSocketAddress`. `UnixDomain.isUnixAddress` and
`UnixDomain.pathOf` only recognize the JDK `UnixDomainSocketAddress`, so (probe:
addr_probe.rb):

| call on Socket.new(:UNIX,:STREAM) | stock / MRI | patched |
|---|---|---|
| connected `remote_address` | Addrinfo with server path | **Errno::ENOTCONN** |
| connected `getpeername` → unpack | server path | **""** |
| bound `local_address` | Addrinfo with bound path | **empty path** |
| bound `getsockname` → unpack | bound path | **ArgumentError: not an AF_UNIX sockaddr** |

Not caught by mspec (no spec covers address methods on a raw `Socket` UNIX stream) or by
Kestowv (0.5.6 channel.rb no longer dials with Socket.new; it never reads the address).
Likely fix: have `isUnixAddress`/`pathOf` also accept `jnr.unixsocket.UnixSocketAddress`
for as long as the holdouts exist.

## Pre-existing, shape changed — unbound AF_INET `Socket#local_address`
Stock: NullPointerException. Patched: `Addrinfo: UNIX` (afamily 1, wrong). MRI: `0.0.0.0 TCP`.
Root cause predates the PR (local_address falls through to the unix branch when the inet
address is null), but the patch turns a loud crash into a silently wrong answer; source of the
2 extra `socket/local_address_spec.rb` FAILs (stock has them as ERRORs).
