# JEP-380 pre-PR gate — full spec/ruby/library/socket pass (2026-10-21)

Patched jar sha256 5f568e7d34fb87c5…; PR diff: JRuby-jep-380/patches/jruby-jep380.diff
(25 files vs jruby/jruby 0e6e01b, applies cleanly). See INTERPRETER.md Landmines 17–18.

| spec/ruby/library/socket | patched | stock 10.1.0.0 |
|---|---|---|
| addrinfo | 534 ex, 0F 0E | 10F 38E |
| ancillarydata | 101 ex, 0F 0E | 101E (class missing) |
| basicsocket | 415 ex, 0F 0E | hangs (timeout) |
| constants | 0F 0E | 2F |
| ipsocket | 0F 0E | 12F 1E |
| option | 0F 0E | 3E |
| socket | 385 ex, 0F 0E | 35F 181E (317 ex run) |
| tcpserver | 1F (Thread#backtrace labeling, JRuby core, not socket) | 6F 12E |
| tcpsocket | 0F 0E | 8F 10E |
| udpsocket | 0F 0E | 9F 5E |
| unixserver / unixsocket | 0F 0E | 2F1E / 2F4E |

MRI 4.0.5 on this host: every directory 0F 0E (so all failures above were JRuby's).

Probes vs MRI: inet_probe, eof_probe, addr_probe all IDENTICAL.
Kestowv regression x3 each: identical counts patched vs stock every run.
stress_uds 29.8M ops / stress 268.0M ops patched, 0 errors (stock 29.5M / 258.2M, 0 errors).
