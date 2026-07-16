---
description: "Measure whether past tempdoc or enforcer work actually paid off, without fixing anything or starting new work. Rare — run manually only."
disable-model-invocation: true
---

Your task is to measure whether past work actually paid off. Sample about 10 tempdocs that were closed/implemented at least 60 days ago — spread them across the number range, don't cherry-pick, and include at least a couple of heavy ones. For each, determine from the current tree and git history whether its output is (a) alive on main and consumed by something, (b) superseded or deleted, or (c) inert — present but nothing consumes or validates it. Also note whether anything ever validated the work's value after it shipped: a measurement, a user-visible outcome, a real consumer. Report the conversion rate, the biggest write-off, and any work stream that keeps reopening without converging.

Do not fix anything, do not start new tempdocs, and do not turn findings into new process — this is measurement only.

Some runs of this task may target the enforcement layer instead of tempdocs: sample ~15 enforcers (hooks, discipline gates, CI check scripts) and determine for each whether it has ever caught a real defect — not a false positive, and not a defect in the apparatus itself — then report the retire-candidates; the same measurement-only rules apply.

At the end summarise for me shortly in plain language whether our planning output is converting into durable value, and the single change that would most improve the rate.
