---
title: "Installer composition observability (idea sketch, not chartered): make installer byte-composition visible on an ongoing basis instead of discovered by archaeology"
type: tempdocs
status: "idea sketch (2026-07-21) — not chartered, not designed, not decided. Surfaced by a /theorize pass on tempdoc 772. No investigation beyond what's written here has been done."
created: 2026-07-21
author: agent (theorization pass, follow-up from tempdoc 772)
category: distribution / installer / tooling
related:
  - 772-installer-payload-composition  # the investigation that surfaced this idea
---

> This is a sketch of an idea, not a proposal. It exists so the idea has a home and a number, not
> because it has been designed, scoped, or approved. Nothing here should be implemented without a
> real design pass first.

# 773 — installer composition observability (idea sketch)

## The observation that prompted this

Tempdoc 772 investigated what belongs in the base Windows installer versus a consent-gated download.
In the course of that investigation, two of its three largest findings — a bundled dependency jar
carrying ~200 MB of unusable Linux native binaries, and a WebView2 offline installer roughly 60%
bigger than its own upstream tooling's documented estimate — were found only because someone happened
to re-download and manually byte-count a real installer artifact by hand, more than once, during an
unrelated takeover investigation. Nothing about installer composition is visible on an ongoing basis
today: there is no build or CI step that reports what is inside a given release's installer, or how
that composition changed since the last one.

That is a gap in kind, not just in this one instance — the next accidental multi-hundred-megabyte
dependency would be found the same way: by chance, when someone happens to go looking for something
else, an unknown amount of time after it first shipped.

## The idea, loosely

Something that reports installer composition as a matter of course, rather than as an investigation:

- A script that extracts a built installer (or reads its staging directory before NSIS packs it) and
  emits a byte-size breakdown by top-level component and, for jars/archives with embedded native
  binaries, by platform — the same shape of table tempdoc 772 built by hand in §F/§G/§H.
- Run it as part of (or immediately after) the installer build, so the numbers exist for every build,
  not just when someone asks.
- Compare against the previous release's numbers and flag any component that grew unexpectedly, the
  same way a dependency-size or bundle-size regression check works in other ecosystems.

## Why this wasn't designed here

This is a monitoring/tooling concern, not a payload-composition decision — a different kind of work
from what tempdoc 772 charters, and folding it in would have overloaded an already-large tempdoc.
It's also not obviously worth building: the two findings that prompted this idea were caught by a
one-time investigation just fine, and a lightweight one-time investigation repeated occasionally
might be cheaper than maintaining an ongoing check, depending on how often the underlying
dependencies actually churn. That tradeoff hasn't been evaluated.

## What isn't decided

Everything: whether this is worth building at all, what "unexpectedly" should mean for a size
regression threshold, whether it belongs in CI (cost, cadence) or as a manual/periodic script, how it
would interact with the CUDA-variant / ONNX-model builds that are conditionally included, and whether
per-platform jar-content breakdown is common enough across the dependency tree to generalize beyond
the one `onnxruntime_gpu` instance that prompted this. None of this has been investigated — this
tempdoc is the idea's placeholder, not its design.
