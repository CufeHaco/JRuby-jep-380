# Evidence

Test and benchmark runs behind the JEP-380 PR, all on valenthree (40 threads, OpenJDK 25),
reference = MRI 4.0.5. Up to the rebase: patched build = jruby/jruby `0e6e01b` + the diffs now in
`patches/base-0e6e01b/`, stock = JRuby 10.1.0.0 (JNR sockets). After it: patched = `94156c4` + the
current `patches/` diffs, baseline = plain `94156c4` built the same way.

| folder | what |
|---|---|
| `*-pre-pr-gate/` | Pre-PR gates, oldest first: MRI address/EOF/INET probes, `library/socket` mspec per class (patched vs stock), `SUMMARY.txt`. Kestowv regression/stress logs are not included (Kestowv is private); their counts are in each `SUMMARY.txt`. |
| `20261022-bench-jep380-vs-jnr/` | First benchmark, unpinned, 5 alternating rounds. |
| `20261022-bench-long-pinned/` | Pinned (cores 2-5), shuffled sizes, 10 rounds, 2000-iteration warm-up -- small sizes still in JIT warm-up. |
| `20261022-bench-long-pinned-warm25k/` | Same with a 25000-iteration warm-up (the numbers to quote), plus the per-call, ping-pong, warm-up-curve, sysread and wake-up test scripts. |
| `20261022-poll-fix-specs/` | core/io, core/thread, kernel select/sleep, library/socket, timeout, io-wait specs after the `sysread` poll fast path (patched vs stock). |
| `20261022-rebase-94156c4/` | After rebasing onto `94156c4`: 25 spec suites, rebase vs plain master (`SPECS-SUMMARY.txt`, `specs/`), the pinned benchmark vs master's JNR (`bench-aggregate.txt`), and the scheduler smoke test. |
| `20261022-1238-rebase-gate/` | Pre-PR gate on the rebased build vs plain master: probes, socket mspec, and Kestowv counts in `SUMMARY.txt` (re-run after installing msgpack into the fresh builds; 5 extra rounds for the teardown flake). |

See `INTERPRETER.md` Landmines 17-20 for what each run found.
