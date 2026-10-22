# JEP-380 vs JNR — long pinned/shuffled UNIXSocket benchmark (2026-10-22)

Builds: patched = jruby-upstream with jruby-jep380.diff + jruby-backtrace-labels.diff;
stock = same base (0e6e01b) JNR path; MRI 4.0.5 as reference.
Method: `numactl --membind=0 taskset -c 2,3,4,5` (physical cores, socket 0), 10 rounds
patched/stock alternating in shuffled order, random seed per run, size order shuffled per run,
one discarded warm-up pass over all sizes, Queue ready-signal after UNIXServer.new.
Host: shared (25 users), load avg 3.07–3.86 on 40 threads during the run. Scripts and raw logs here.

| size  | patched msg/s med (min–max) | JNR msg/s med (min–max) | MRI med | ratio of medians | per-round ratio med (min–max) | p50 ms pat/JNR | p99 ms pat/JNR |
|-------|------------------|------------------|-------|-------|------------------|---------------|---------------|
| 100B  | 58207 (49401–95148) | 70515 (45507–94199) | 66024 | 0.83x | 1.00 (0.54–1.42) | 0.0159/0.0138 | 0.0277/0.0249 |
| 1KB   | 44415 (41240–73945) | 52711 (28432–68774) | 59021 | 0.84x | 0.94 (0.64–1.54) | 0.0202/0.0175 | 0.0352/0.0283 |
| 10KB  | 22906 (21046–33210) | 20858 (19692–24251) | 18792 | 1.10x | 1.12 (0.87–1.66) | 0.0384/0.0415 | 0.0626/0.0946 |
| 100KB | 3955 (3838–4091)    | 3172 (3096–3224)    | 5569  | 1.25x | 1.26 (1.22–1.32) | 0.2148/0.2723 | 0.4850/0.6552 |

Findings
- 100KB: 1.22–1.32x in every round, p99 −26%. Tight, reproducible; above the original 10–20% claim.
- 10KB: ~1.10x median, p99 −34%; inside the claim, some round noise.
- 100B/1KB: statistical tie. Per-round ratio medians 1.00/0.94 with 0.5–1.5x spread; stock ranges 2x wide
  from shared-host noise. The 0.42x/0.78x seen in the single surviving earlier round is NOT reproduced.
  Patched p50 is ~2–3µs higher at small sizes — per-call fixed overhead (FFM/poll path) is the lead to look at.
- No consistent position-in-order effect after the warm-up pass (100KB flat across positions).
- MRI still beats both JRuby builds at 100KB (5569 msg/s) and 1KB.

Verdict: "10–20% faster than JNR" holds for ≥10KB payloads (10–26%); small messages are at parity, not faster.
