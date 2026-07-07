---
title: "Fallback-constant conformance to config defaults: make the class 'a hardcoded fallback claims to match the ResolvedConfig documented default but has silently diverged' structurally unshippable — one conformance test that constructs a default ResolvedConfig and asserts every registered fallback literal against it. Motivated by a confirmed live instance (HybridSearchOps low-signal fallbacks 10/0.3 vs builder defaults 3/0.25, found 2026-07-06) and by the measured base rate that makes recurrence likely (~78% of ~560 operational constants carry no provenance; the fallback-mirrors-default pattern exists at several sites). Fixing the one instance without the check just resets the clock."
type: tempdocs
status: "open — scoped, not started. NOTE for the implementing session: the seed instance (HybridSearchOps 10/0.3 vs 3/0.25) requires deciding which value is INTENDED before aligning — the eval/search-quality register owns that call; do not silently pick one."
created: 2026-07-07
author: agent session 2026-07-07 (class identified during the 682 publish; seed instance verified first-hand and inbox-logged 2026-07-06)
category: stabilization / configuration / test-substrate
related:
  - 682-inherited-constants-stabilization-batch   # sibling: same root (provenance-less constants), different class (duplication vs fallback-drift)
---

# 685 — Fallback-constant conformance to config defaults

## The class, stated structurally

Several code sites carry a hardcoded fallback used when resolved config is absent, with a
comment asserting it "matches the ResolvedConfig documented default." Nothing checks that
assertion, so the two values drift independently — and the no-config fallback path then runs
different behavior than the documented defaults, silently, on exactly the code path least
likely to be observed (fresh installs / minimal configs).

**Confirmed instance [verified 2026-07-06 on main]:** `HybridSearchOps` low-signal fallbacks
`DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL = 10` / `DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL = 0.3`
versus `ResolvedConfigBuilder` defaults `3` / `0.25` — the fallback fusion parameters differ
from the documented defaults by >3x on one axis. (Inbox item exists; the fix needs an owner
decision on which value is intended — see status note.)

## The work

1. **Inventory the class:** grep for fallback constants whose comments reference
   ResolvedConfig/EnvRegistry defaults (and the reverse: builder defaults that name a
   consumer-side mirror). Expected small (single digits of sites).
2. **One conformance test** (natural home: the configuration module's test sourceset, or a
   small test per consumer module if layering forbids importing the consumers): construct the
   default `ResolvedConfig` (no overrides) and assert each registered fallback literal equals
   the corresponding resolved default. Follow the existing cross-boundary conformance-test
   patterns (the repo already does cross-tree-read drift tests, e.g. the FE/BE capacity test
   added by 682 in `bootIntentStreamBridge.test.ts`).
3. **Reconcile the seed instance** — after the owner decision on intended values (10/0.3 vs
   3/0.25). The conformance test lands red against whichever side is wrong and forces the
   explicit choice; that is the point.
4. **A one-line authoring convention** in the code comment at each site: fallbacks that
   mirror a config default must be registered in the conformance test (the test's own failure
   message should say this).

## Explicitly out of scope

- Any value *tuning* (which low-signal cap/weight is right is a search-quality call with the
  relevance ratchet as the referee).
- Retroactive provenance annotation of unrelated constants (682's out-of-scope stance holds).
- Config-system redesign; this is one test class plus comment lines.

## Acceptance / verification

A deliberate mutation of either side of any registered pair fails the test with a message
naming both sites (mutation-verify at least the seed pair). The seed instance is reconciled
with a recorded decision. `./gradlew.bat build -x test` + configuration/affected module tests
green.
