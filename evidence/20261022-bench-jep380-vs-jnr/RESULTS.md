# JEP-380 vs JNR — UNIXSocket round-trip benchmark (2026-10-22, valenthree, 40 CPUs)

benchmark_jep380_vs_jnr.rb (500 warmup + 2000 timed round-trips per size), 5 alternating
rounds patched/stock, -Xshare:off; MRI 4.0.5 one round for reference. Patched = final build
with the socket + backtrace-label patches. Medians of 5 rounds:

| size | patched msg/s | stock JNR | MRI | patched vs JNR | p50 ms (pat/JNR) | p99 ms (pat/JNR) |
|---|---|---|---|---|---|---|
| 100B | 19,024 | 18,478 | 62,551 | 1.03x | 0.0498/0.0515 | 0.0902/0.0907 |
| 1KB | 29,104 | 25,942 | 40,859 | 1.12x | 0.0340/0.0369 | 0.0602/0.0645 |
| 10KB | 18,873 | 14,948 | 16,264 | 1.26x | 0.0518/0.0654 | 0.0692/0.0865 |
| 100KB | 4,380 | 3,039 | 4,764 | 1.44x | 0.2122/0.2698 | 0.3284/0.6428 |

Per-round ratios: 100B 1.11 1.05 1.00 1.03 0.84 · 1KB 0.99 0.87 1.16 1.33 0.81 ·
10KB 1.25 1.13 1.41 1.20 1.21 · 100KB 1.32 1.47 1.56 1.34 1.44.
Small sizes are within noise on this run; 10KB+ is consistently faster. 100B is the first
size measured in each process (both runtimes lower than 1KB) — likely still warming.
