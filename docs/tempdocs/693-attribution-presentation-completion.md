---
title: "Attribution presentation completion: list-aware citation weave, dangling-mark hygiene, exception marking"
type: tempdoc
status: open
created: 2026-07-07
related: [565, 687, 690]
---

# 693 — Attribution presentation completion

## Context

Tempdoc 565 §15 built the one citation weave (per-sentence matching → superscript marks +
grounding-tier spans); 687 inverted the marking (grounded prose plain, exceptions marked)
and normalized model-written "[n]" tokens. Live use exposed three gaps (687 F2/F4 plus a
deferred item):

1. **Bullet-list answers defeat the sentence matcher** — sources are retrieved but ZERO
   marks are woven, leaving the model's "[n]" dangling with no path from claim to source.
2. **A bare trailing "[n]" line** escapes `stripTrailingCitationBlock`'s shape.
3. **Uncited-prose exception marking is deferred** pending a live claim-score
   distribution sample (687 P2's own retirement condition: noisy exception marks are
   worse than none).

## Decision (design level)

1. **List-aware segmentation**: the weave treats each list item (and heading) as a
   matchable unit alongside prose sentences, through the same normalized-match
   machinery — one segmentation authority, no parallel list matcher.
2. **Dangling-mark hygiene**: extend the trailing-block stripper's shape to a bare
   "[n]"-only line; in-prose dangling tokens with no matching citation record stay
   visible and unlinked — an honest signal of an unbacked reference, never fabricated
   into a link.
3. **Exception marking for uncited prose** lands only after the score sample; the
   threshold comes from measured distributions and unifies on the one tier authority
   (`groundingClass`).
4. **Candidate (not built until the scorer can carry it)**: a distinct CONTRADICTED
   tier, per the external-landscape note in 687's research annex.

## Supersedes / orphans

Extends 565 §15 in place (same authority); nothing orphaned. When item 3 lands, 687's
"deferred" note is satisfied and gains a forward-pointer.
