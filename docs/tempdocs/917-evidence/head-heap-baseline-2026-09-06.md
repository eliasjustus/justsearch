# 917 evidence: Head heap-flag baseline (2026-09-06)

Analyzer output (`tmp/analyze-head-baseline.cjs` over the raw artifacts archived at `F:\justsearch-public\tmp\lane-f-baseline-2026-09-06\`). Interpretation and setup: tempdoc 917 §Derisk results 1. Phases are local time (+02:00); the Head is the `head` role, the `worker` role also caught the extraction child (~216 MB) so its minima are not the Worker JVM.

Warm restart (dev-runner `--skip-build`, hot-reload on): http_first_response 3107 ms, head READY 3107 ms, worker READY 8016 ms from launcher spawn; three `Metadata GC Threshold` full GCs in the first 18 s (22, 24, 40 ms).

```
# GC (SerialGC, -Xmx512m)
- uptime covered: 942 s; pauses: 8 (young 4, full 4)
- young pause ms: p50 10.1 · p95 34.3 · max 34.3 · total 53
- full pauses: Metadata GC Threshold 112M->13M 23.0ms @1s; Metadata GC Threshold 86M->16M 24.1ms @1s; CodeCache GC Threshold 53M->42M 71.0ms @157s; CodeCache GC Threshold 109M->64M 60.9ms @745s
- causes: {"Metadata GC Threshold":2,"Allocation Failure":4,"CodeCache GC Threshold":2}
- live heap after GC (M): min 13 · max 68 · last 68 of 491M committed

# Working set (MB) per role
- idle_first60s: head n=27 min 377 · p50 377 · max 377 | worker n=27 min 4067 · p50 4068 · max 4073
- ingest_enrich: head n=53 min 380 · p50 416 · max 423 | worker n=106 min 216 · p50 4261 · max 4393
- search_and_agent: head n=53 min 423 · p50 425 · max 436 | worker n=106 min 216 · p50 2580 · max 4333
- restart_warm: head n=10 min 177 · p50 294 · max 319 | worker n=12 min 56 · p50 1175 · max 4023
- head threads first/last: 241/87; head CPU seconds consumed over window: -4

# Search latency (POST /api/knowledge/search, sequential, ms)
- search-load-after-enrich.csv: n=60 p50 1598 · p95 1920 · max 1950
- search-load-during-enrich.csv: n=60 p50 258 · p95 485 · max 578
```
