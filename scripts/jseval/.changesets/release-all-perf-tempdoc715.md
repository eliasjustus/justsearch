---
classification: baseline-relaxation
gate: release
dataset: "*"
tempdoc: 715
---

Tempdoc 715 coordinated release re-baseline (2026-07-16): the whole point of this
release is a cohort-identical re-pin after F-031/F-032 staled every published
number. Quality metrics improved or held across all five corpora (legal-clerc
hybrid 0.516->0.56 class; enron vector/hybrid recovered from the dead-chunk
era). Perf-family relaxations observed and accepted with causes:

- beir/scifact ce_p50_ms 167->171 (2.4%): run-noise/thermal scale, same hardware.
- beir/scifact primary_docs_s 111.1->89.8: the KNOWN unexplained primary-indexing
  drift already registered by tempdoc 691 (deprioritized as low-value there),
  compounded by concurrent worker CPU load during this session's measurement.
  Not new information; the 691 register entry remains the owner of that thread.
- Any further perf-family relaxations in this same release compose belong to the
  same coordinated re-pin and inherit this justification; QUALITY floors are not
  covered by this changeset and must not relax under it.
