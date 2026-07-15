---
title: AI-runtime lifecycle — why local fixes come out wrong
type: tempdocs
status: "open — opened as a takeover brief; no analysis performed yet. First move (§4) is the capability probe, which decides this doc's own scope."
created: 2026-07-15
author: "agent (opened from the 0.2.0 release round; owner-directed 2026-07-15)"
related: [734, 726, 730]
---

# 737 — AI-runtime lifecycle: why local fixes come out wrong

## 1. Purpose

The AI-runtime lifecycle is the area of this codebase where a correct-looking local
fix is most likely to be wrong. **Establish why, and remove the cause** — so that a
reader of any one surface can tell what state the system is in and whether an action
will succeed, without cross-referencing three modules, and so that the wrong answer
becomes hard to express rather than merely currently-absent.

The purpose is deliberately held at that altitude. The known defects span four
different layers (§3). A purpose naming any one of them — "fix the mode enum", "fix
the capability", "rewrite `BrainSurface`" — pre-decides the diagnosis, and **the
diagnosis is the deliverable**. This doc's own scope is one of its findings: if the
inverted capability (§3b) turns out to be a class rather than an instance, the
subject was never inference modes.

## 2. Done criterion (falsifiable — do not soften this into a discussion)

A fresh agent, given a state and a proposed user action, can predict **from one
place** whether that action succeeds — and a change that breaks that property is
caught by a gate, not by a reviewer noticing.

Today this fails. That is not a hypothesis; see §3e.

## 3. Evidence

These are the **symptoms the purpose exists to explain**, not the work items. If the
analysis is right, most should fall out as consequences of one cause rather than
needing individual fixes. Verify every line below against source before building on
it — several were verified during the 0.2.0 round, but this doc is dated history the
moment it is written.

### 3a. There is no `offline` mode on the wire

Runtime modes are `online | indexing | starting | transitioning`; the operation
accepts `{"mode":"online"|"indexing"}` (`CoreOperationCatalog.java:183`, verified
round 3). The UI has spent its life naming a state the domain model does not contain.
So "Shut Down AI" is not a shutdown — it is `switchInference('indexing')`, a cold
start of the embedding backend that happens to stop chat.

**Hypothesis worth testing first (not a conclusion):** "inference mode" may be
conflating two orthogonal axes — chat engine up/down, and embedding backend
warm/cold — into one linear enum. That would explain why "Shut Down" had to be
spelled `indexing`. If true, the subsystem is unfixable in place for a boring
reason: it models one dimension where there are two.

### 3b. The capability algebra is inverted

`core.switch-inference-mode` requires `RequiredCapability.InferenceOnline`
(`CoreOperationCatalog.java:803`), which resolves to
`capabilities.inference().available()` (`HeadAssembly.java:1211`). **The operation
requires the postcondition it exists to establish.** Every path through it is dead:
Simple mode's "Resume Chat AI", Advanced mode's "Online" button
(`BrainSurface.ts:1911`), and — pre-existing — offline → "Start AI"
(`BrainSurface.ts:1085`), the last long masked by auto-activation after Install AI.
Users see `Required capability unavailable: inference-online`
(`OperationExecutorImpl.java:317`).

A typo produces one wrong symbol. This reads like requirements were assigned by
pattern-matching the operation's *name* rather than reasoning about its pre- and
postconditions — which is why §4 probes for a class.

### 3c. Presentation had two authorities for one fact

`statusConfig` in `BrainSurface.ts` was a hand-maintained fork of
`aiEngineHeadline`/`aiEngineTone` (`state/aiVerdict.ts:239-295`) and had drifted:
`indexing` rendered "AI Online" in green while health reported degraded. Torn out in
`75bebc99` — but the fork existed at all, which is the datum.

### 3d. A user can be put there with no click

`VduOfflineTriggerSampler` can drive a user into the state with no interaction.
Combined with §3b, that is an inescapable state entered involuntarily.

### 3e. Two agents fixed this wrong — the observation this doc must explain

This is the primary evidence, and it is stronger than the defect count.

1. **A surface fix that could not work.** `75bebc99` gave `indexing` its own branch
   returning "Resume Chat AI" → `switchInference('online')`, tore out the §3c fork,
   and shipped with five unit tests, four proven red by revert. It was **still dead** —
   the cause was §3b, two layers down. The tests asserted the branch *returned* the
   right action, never that it could *execute* (`wrong-gate`). The implementer even
   observed the exact error live and rationalised it as a local environment gap.
2. **A rescue fix that did the opposite of its own javadoc.** PR #185's
   `maybeAutoStartRebuildForLegacyUnattestedVectors` stated "we deliberately do not
   back-stamp… the only safe rescue is a real re-embed", then transitioned to
   REBUILDING without re-marking — so nothing was queued, certification fired on the
   first read, and the fingerprint was stamped over vectors nobody re-embedded. Its
   test passed because it **hand-wrote the missing re-embed** and fed
   `checkRebuildCompletion` hardcoded zeros (`unreachable-seed-green`). Repaired in
   the 0.2.0 merge (`c1dbc34c`) by extracting the ordering into
   `EmbeddingRecoveryOps.rescueBlockedLegacyIndex` and forcing callers through it with
   two ArchUnit rules.

Both were careful readers. Both were confidently wrong. **A subsystem that produces
confidently-wrong fixes from careful readers is telling you something about itself,
and that is what this document is trying to hear.**

## 4. First move — the capability probe (do this before framing anything else)

Walk the whole operation catalog and ask of every operation: **does it require a
capability that it itself establishes?**

- If `core.switch-inference-mode` is alone → this is an inference-lifecycle tempdoc.
- If there are others → the subject is the **capability algebra**, not inference
  modes, and the scope changes completely.

This is cheap, mechanical, and decides what we are actually writing about. Run it
before committing to a frame. If it finds a class, consider whether the invariant
belongs in a gate (the pattern is mechanically checkable: an operation's
`RequiredCapability` set must not contain a capability the operation's own success
establishes).

## 5. Then: what are the actual states?

Not the UI's vocabulary, not the current enum — the states the system **genuinely
occupies** (no model installed; model installed, engine down; engine starting; engine
up; embedding backend cold; embedding backend warm; …), and which transitions a user
may legitimately request from each. Name them from source and from live observation,
not from either existing model.

## 6. Explicitly NOT in scope

- **"Rewrite" is not in the purpose.** It is a conclusion the analysis either earns
  or does not. Naming it up front means every finding gets read as evidence for a
  verdict already reached. If §5 shows two axes wearing one enum, the rewrite writes
  itself and needs no advocate.
- **The 0.2.0 release blocker is not this doc's job.** §3b needs a minimal correct
  unblock shipped separately, so the release does not wait on the thinking and the
  thinking is not compressed by the release. Track it on the release branch / 734.
- **This is not a findings list.** §3 is evidence, not a work queue. Do not open this
  doc by fixing F-6.
- **Do not re-derive urgency.** Per `structural-defects-no-repeat`, one documented
  silent bug proves a bug-class; here there are two independent wrong fixes. Critique
  this doc's substance — wrong diagnosis, wrong mechanism, wrong scope — not its
  timing.

## 7. Handling note for whoever takes this over

The agent that opened this doc **was wrong about this subsystem repeatedly** on
2026-07-15 — it "corrected" the owner's framing of the Simple-mode trap twice, from
confident code reads, and was wrong both times. Every claim in §3 is therefore worth
re-verifying from primary sources rather than inherited. That is not modesty; it is
the same property the doc exists to explain, observed from the inside.

Suggested route: `/theorize` on §3e (why do careful fixes come out wrong here?) →
the §4 probe → `/research` the real states (§5) → `/design` → `/derisk` → `/plan`.
Stop at plan; do not implement from this doc without the owner's word.
