# Evidence

Test and benchmark runs behind the JEP-380 PR, all on valenthree (40 threads, OpenJDK 25),
patched build = jruby/jruby `0e6e01b` + `patches/jruby-jep380.diff` + `patches/jruby-backtrace-labels.diff`,
stock = JRuby 10.1.0.0 (JNR sockets), reference = MRI 4.0.5.

| folder | what |
|---|---|
| `*-pre-pr-gate/` | Pre-PR gates, oldest first: MRI address/EOF/INET probes, `library/socket` mspec per class (patched vs stock), `SUMMARY.txt`. Kestowv regression/stress logs are not included (Kestowv is private); their counts are in each `SUMMARY.txt`. |
| `20261022-bench-jep380-vs-jnr/` | First benchmark, unpinned, 5 alternating rounds. |
| `20261022-bench-long-pinned/` | Pinned (cores 2-5), shuffled sizes, 10 rounds, 2000-iteration warm-up -- small sizes still in JIT warm-up. |
| `20261022-bench-long-pinned-warm25k/` | Same with a 25000-iteration warm-up (the numbers to quote), plus the per-call, ping-pong, warm-up-curve, sysread and wake-up test scripts. |
| `20261022-poll-fix-specs/` | core/io, core/thread, kernel select/sleep, library/socket, timeout, io-wait specs after the `sysread` poll fast path (patched vs stock). |

See `INTERPRETER.md` Landmines 17-20 for what each run found.
