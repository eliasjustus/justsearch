---
title: Release validation instruments and round scheduling — parity decomposition, charters, arrival-state coverage
status: "design settled 2026-07-17 (§Design); nothing implemented. Theorization + research + codebase investigation preserved above it. Written while 0.2.0 is blocked on 734 finding 5 (golden-parity, root cause unknown) and 749 (RAG chunk fallback). Headline: the parity instrument's overlap-as-gate methodology is the weak link; §Design Part A replaces it with a three-signal decomposition, of which the score-identity probe (A1) needs no product change and is finding 5's discriminating experiment."
created: 2026-07-17
updated: 2026-07-17
---

# Release/Sandbox workflow — round scheduling, diagnostic payloads, and arrival-state coverage

## Why this exists now

The 0.2.0 convergence (tempdoc 734, rounds 1–6) is the first full exercise of the
redesigned release workflow (`cut-a-release.md` loop + 728's derived-coverage
validation). The verdict on the *workflow itself* after six rounds: the inside of a
round is in good shape — derived coverage, empirical finalize checks, and the
independence structure all caught real things every round. The weak joint is
**between** rounds: what the loop does when a finding resists black-box explanation,
what an expensive check's failure carries with it, and which install states rounds
are drawn from. This doc theorizes improvement directions before any design is
settled.

**Relation:** `cut-a-release.md` owns the release loop (durable); 726 owns the asset
set; 728 owns the validation content architecture (IMPLEMENTED, closed); 734 is the
0.2.0 round record and hosts the "give the round a bite" design (Part F) plus its
measurement (Part G). This doc does **not** reopen any of those. It theorizes the
*next* layer of workflow improvement, with 734's six rounds as evidence. 737
(capability algebra) and 749 (RAG bug) are product work, out of scope here except as
evidence.

## The presenting evidence (from 734, condensed)

1. **Rounds 5 and 6 were both largely spent re-confirming finding 5** (golden-parity
   regression), and the record's own conclusion after round 6 is "needs source-level
   investigation, not another Sandbox round." Two expensive rounds (installer build,
   ~10 GB Install AI, hours of agent time) were used as a diagnostic instrument for a
   question a cheaper tier should own.
2. **The golden-parity check fails without attribution.** It reports `overlap 4/10`
   and nothing else — no per-stage trace, no leg attribution (BM25 vs dense vs
   reranker), nothing a root-cause dig can start from. Finding 5 has survived two
   rounds partly because each failure carries zero diagnosis.
3. **Its noise envelope was calibrated on the wrong axis.** The ≥9/10 envelope came
   from n=3 same-machine dev rebuilds; the check compares a dev-built HNSW index
   against a Sandbox-built one (different insertion history, virtualized GPU). 734's
   own help-docs experiment proved HNSW insertion-order sensitivity moves exactly the
   queries that fail (q04/q06/q08). Cross-environment variance was never sampled.
4. **Rounds burn time on reachability discovery.** Round 6 spent "a dozen-plus
   screenshot round-trips" hunting a `core.free-chat` entry point that may not exist
   as described, and found no GUI entry for `core.workflow-run` at all. The coverage
   brief says *what* to touch but not *how to reach it*.
5. **D.9's round-economics questions were asked and never answered** — nobody has
   measured where a round's hours go.
6. **The harness is its own software system with its own bug rate** — four bugs on
   its first refute-first review; `collect-evidence.ps1` still probed a wrong install
   path in round 6; the openai-compat cohort was credited against the wrong port by
   the round's self-report (caught only by the host-side re-run).

## Theorization — directions to weigh (options, not decisions)

### T1. Framing: the loop's weak joint is between rounds, not inside them

Rounds discover well and confirm expensively. The loop's steps (build → verify → fix
→ rebuild → converge) have no first-class **investigate** step, so an unexplained
finding defaults to "run another round and see." The generative framing: **round
scheduling is itself a tier-routing decision** — 728's "route each surface to its
cheapest sufficient tier" applied not to *checks* but to *the decision to run a
round at all*. The workflow already has the vocabulary; it just stops applying it at
the loop boundary.

### T2. A needs-round gate on round scheduling

Round 6's **pre-registration** (stating up front what the round is for and what each
open finding needs) was the closest the loop has come to this, and it worked — keep
it and sharpen it. Candidate mechanism: after any DO-NOT-QUALIFY, each open blocker
is explicitly classified **needs-round** (only a clean install / real GUI / real
external agent can answer it) or **needs-dig** (source-level or dev-stack work). A
new round is scheduled only when at least one blocker is needs-round — a
confirmatory round for a needs-dig blocker is the anti-pattern rounds 5→6 exhibited.

Honest counterweights:
- Round 6 *also* found 749, two MEDIUM UX findings, and a coverage discrepancy —
  "wasted" confirmatory rounds still discover, because a whole-product pass has
  by-catch value. The gate must weigh discovery value, not just the named blocker.
- A needs-dig classification must carry an owner and a timebox, or the gate becomes
  a place where releases stall indefinitely.
- The classification is a judgment call an agent will sometimes get wrong; the cost
  asymmetry (a wasted round ≈ hours; a wrongly-skipped round ≈ a missed
  environment-dependent bug) should bias toward running rounds when in doubt.

### T3. Fail with attribution: expensive-tier alarms must carry their own diagnosis

The golden-parity check is a **binary alarm** on the most expensive tier. Candidate
upgrades, cheapest first:

1. **Capture `searchTrace` per golden query** (the round already captures the
   responses; the trace is in the same payload or one flag away). A failure then
   ships with per-stage evidence: which retrieval leg's candidates diverged, whether
   fusion or rerank moved the tail.
2. **Leg-attribution diff at finalize**: `check_golden_parity.py` compares not just
   final top-10 overlap but per-leg candidate sets against the baseline's, so the
   failure message names the diverging stage instead of the symptom.
3. **An exhaustive (non-HNSW) KNN control**: run the golden queries with exact
   nearest-neighbor over the same vectors at baseline-generation and round time. If
   exact-KNN agrees while HNSW diverges, the "regression" is ANN graph variance;
   if exact-KNN diverges, the vectors themselves differ (real). This single control
   would have discriminated finding 5's two live hypotheses.
4. **Round-over-round comparison** as a standing diagnostic: rounds 5 and 6 diverged
   from the *dev* baseline similarly (q06 6/10→5/10, q08 3/10→4/10). If
   sandbox-to-sandbox is stable while sandbox-to-dev is not, the difference is
   environment-systematic, not noise — and **this is answerable today from the
   archived evidence in `tmp/sandbox-evidence/round5*/round6*`, with no new round.**

### T4. Calibration validity is a first-class property of any fail-closed check

The generalizable lesson under T3: **a fail-closed threshold is only as good as the
variance axis it was calibrated on.** The parity envelope sampled rebuild noise
(same machine, same corpus) but is applied across an environment boundary (dev
native GPU vs Sandbox virtualized GPU) and an index-history boundary (different
insertion order). Candidate rule for the harness: a threshold's calibration record
must name the axes it sampled, and applying it across an unsampled axis is itself a
finding. (734's calibration note in `sandbox-CLAUDE.md` is the right *kind* of
record; it just doesn't cover the axis the check actually spans.)

Alternative baseline strategies worth weighing, each with a real tradeoff:
- **Baseline generated in-sandbox** (same environment as the round): removes the
  cross-environment confound, but then parity no longer asserts "the install
  behaves like dev intended," only self-consistency.
- **Two baselines** (dev + previous finalized candidate): candidate-over-candidate
  parity detects regressions between releases without cross-env noise; dev parity
  becomes advisory rather than blocking.
- **Determinize the index build** (fixed insertion order / seeded HNSW) so overlap
  thresholds tighten legitimately — a product change, likely out of proportion for
  this purpose alone.

### T5. Reach affordances in the coverage brief

The brief prescribes *what* to touch; rounds re-pay the discovery cost of *how* every
time, and "I couldn't find it" is indistinguishable from "it doesn't exist" (round
6's free-chat and workflow-run hunts). Candidate: each surface/shape entry in
`governance/sandbox-coverage.v1.json` carries a **reach pointer** — a `data-testid`,
nav path, or composer-state recipe — derived or verified against frontend source
where possible (734 round 5 proved these are derivable: the Delegate control's
testid was found by reading `UnifiedChatView.ts`).

The second-order payoff is the interesting part: **a surface for which no reach
pointer can be derived is a product discoverability finding**, mechanically
surfaced at brief-generation time instead of costing a round an hour of GUI
hunting. (`core.workflow-run` has no GUI entry point — that fact fell out of round
6's time budget; a reach-pointer requirement would have surfaced it at stage time.)
Fork-risk caveat: hand-written reach pointers are a second authority that will
drift as the UI changes; they need either derivation from source or a cheap
verify-at-stage-time probe, or they decay into exactly the prose 728 killed.

### T6. Round modes should derive from supported arrival states, not only from what ships

728 made *surface* coverage derive from what ships. The **mode axis** (fresh-install
vs models-mapped) is still hand-chosen, and one real user arrival state has zero
coverage: **upgrade** — installing 0.2.0 over an existing 0.1.0 install
(ADR-0024 deliberately retains user data across uninstall/reinstall, and 0.1.0 has
real public users). Notably, the workflow's single strongest repro (A.1's
BLOCKED_LEGACY, round 2) came from an *accidental* non-fresh arrival state — round 2
reusing round 1's surviving data. The evidence says non-fresh states find real bugs;
the workflow currently reaches them only by accident.

Candidate: the round-mode policy enumerates supported arrival states (fresh,
upgrade-from-previous-release, reinstall-over-data), and a release's qualifying
set must include each at least once — the same coverage-follows-shipment discipline,
applied to the *from where* axis instead of the *what* axis. Cost caveat: each mode
is a full round; the set should stay minimal (fresh + upgrade is probably the whole
list for now).

### T7. Measure round economics before adding machinery

D.9 Q1 is still open: is the cost round *count* or round *duration*, and where do
the hours go? Rounds now emit timestamped evidence (screenshots, traces,
retrospectives), so a coarse time-attribution of rounds 5–6 (setup / install-wait /
coverage work / reachability hunting / findings investigation / write-up) is
answerable from the archive without new instrumentation. Any further harness
investment should be ranked against that distribution — e.g., if install-wait
dominates, reach affordances (T5) matter less than snapshot/restore of a
post-install sandbox image; if reachability hunting dominates, T5 is the highest
lever.

### T8. The harness needs a maintenance ceiling, and the retirement conditions need their day in court

The anti-lying machinery accretes (size floor, duplicate detection, retrospective
gate, evidence review, plants, per-check bite tests) and the harness's own bug rate
is material. Two commitments already exist on paper and should actually be executed
once 0.2.0 finalizes:

- **Evaluate P1–P3's retirement conditions** (728) and Part F/G's "a mechanism whose
  measured catch rate is zero gets deleted" (734) against the full 0.2.0 evidence.
  G.6 already convicted the size floor as a trivially-defeated byte proxy — that
  verdict should produce a deletion or a replacement, not a footnote.
- **Prefer deleting a weak mechanism over adding a compensating one.** The harness
  is the one part of the workflow with no consumer other than the workflow itself;
  it is where apparatus-for-its-own-sake would accumulate unnoticed.

### T9. Name the independence invariant so streamlining can't remove it

The workflow's most consistently productive property across all six rounds is
structural independence: a fresh-session verifier with no source access, plus a
mandatory **host-side mechanical re-run at finalize** that has contradicted the
round's self-report every time it ran (round 5: mislabeled screenshots,
activity-surface zero-evidence; round 6: openai-compat wrong-port, 26/28 not 28/28).
D.7's collision lesson points the same way (duplicated effort was the detector).
Any future efficiency work that merges the round's self-report with the finalize
check, lets the round see its own coverage bookkeeping, or drops the independent
re-run "because the round already reports it" removes the workflow's best-performing
control. Worth stating as an invariant in the durable mission doc, so it is violated
deliberately or not at all.

## Hidden assumptions worth surfacing

- **"Parity-with-dev" assumes dev is the ground truth.** The Sandbox result *is* the
  real-user result; if they systematically differ (T3.4), the right response might be
  to fix the product's environment sensitivity, not to make the Sandbox match dev.
- **The finalize criterion assumes findings are enumerable per round.** 749 was
  by-catch from a confirmatory round — the "zero blocking findings" bar is really
  "zero found," and the workflow's honesty about that (G.6: catch rate is a lower
  bound on blindness) should temper how much a single clean round certifies.
- **Fresh-install-first assumes the riskiest state is the empty one.** Round 2's
  accidental evidence says otherwise (T6).

## Principles (named; general structure deliberately not built beyond what §Design needs)

1. **Fail with attribution:** a check guarding an expensive tier must emit, on
   failure, enough evidence for a *cheaper* tier to begin root-causing — otherwise
   every failure taxes the expensive tier a second time. **Not a new invention
   here:** the product layer already enforces exactly this
   (`SearchReasonCode`/`SearchTrace.Degradation` + the reason-code CI gates); the
   parity check violated, one layer up, the pattern its own subject is held to.
   §Design Part A conforms rather than coining a parallel scheme. *Where else it
   applies:* any fail-closed harness/CI check whose failure message names a
   symptom without a component (candidate sweep, not built now). *Earns its keep
   if:* the next parity-class failure is root-caused host-side without spending a
   round on re-confirmation. *Retire if:* attribution payloads go unread across
   two releases — then they are ceremony and the binary alarm was enough.
2. **Calibration names its axes:** a fail-closed threshold records which variance
   axes its envelope sampled (population, held-constant factors, n); applying it
   across an unsampled axis is itself a finding, and the check should fail toward
   "uncalibrated for this population" rather than toward a phantom regression.
   *Where else it applies:* the 16 KiB screenshot size floor (calibrated on one
   round's file sizes — already convicted as a weak proxy by 734 G.6); any future
   jseval quality baseline applied to a new corpus or environment. *Earns its keep
   if:* a threshold challenged across a new axis gets recalibrated instead of
   trusted, at least once, because the provenance block made the gap visible.
   *Retire if:* provenance blocks never change a decision — then they are
   paperwork.
3. **Tier-route the round itself:** scheduling a round is a tier decision governed
   by the same "cheapest sufficient tier" rule as the checks inside it (§Design
   Part B's gate). *Earns its keep if:* confirmatory-only rounds (no needs-round
   blocker) stop occurring while discovery per round holds. *Retire if:* the gate
   is overridden more often than it is followed — then judgment was already doing
   the routing and the rule is friction.
4. **Coverage has two derivation axes:** *what ships* (728, done) and *from where
   users arrive* (§Design Part C starts it with upgrade mode). *Earns its keep
   if:* an arrival-state round finds a class fresh-install structurally cannot
   (round 2's accidental reinstall already did, pre-formalization). *Retire if:*
   two consecutive releases' upgrade rounds find nothing fresh rounds didn't —
   then the axis collapses back to fresh + the reinstall must-watch.

## Scope guards

- Does **not** reopen 728's content architecture, 726's asset set, or the release
  loop's outer shape — every direction above is an extension point (pre-registration
  → T2; `check_golden_parity.py` → T3/T4; the coverage register → T5; round-mode
  policy → T6).
- Product fixes (734 finding 5's actual root cause, 749) are explicitly not this
  doc's work — though T3.3/T3.4 name cheap experiments that would inform finding 5.
- Nothing here is committed to; the next turn is critique/selection, not
  implementation.

## Research (2026-07-17) — three bounded external passes

Scoped to the three directions where global practice could move the design: ANN
regression instrumentation (T3/T4), exploratory-testing process prior art (T2/T7),
and Windows Sandbox persistence (T7). Deliberately **not** re-researched: LLM-judge
independence (734 Part E already did it, with citations) and mature installer-QA
practice (upgrade matrices — T6 needs a decision, not literature). Findings from
three independent research agents, each requiring primary sources; UNVERIFIED flags
preserved.

### R1. ANN regression practice — the parity instrument is methodologically wrong, and the fix is standard

The most consequential result of the pass, because it bears directly on the live
release blocker (734 finding 5):

- **The established control is recall@k against exact brute-force KNN, not
  overlap between two ANN indexes.** ann-benchmarks and every major vendor harness
  compute ground truth by exact KNN over the corpus and measure each index's
  recall@k against that fixed reference. Comparing two approximate indexes *to each
  other* — which is exactly what `check_golden_parity.py` does — is used in the
  literature only as a descriptive stability metric, never as a threshold-gated
  regression signal; no published threshold or sample-size standard for it exists
  (the absence is itself the finding).
- **Published overlap numbers put the round's misses inside the benign band.** The
  closest quantitative study (arXiv:2407.08275, Jaccard@10 across embedding-model
  pairs, 25 queries/dataset) finds even the most similar configurations rarely
  exceed ~0.6 Jaccard at k=10 (~6-7/10 shared) and majority <0.5 — i.e., benign
  configuration differences routinely produce the 4–6/10 range the rounds measured.
  Caveat kept honest: 734's per-query calibration (q08 = 10/10 across every dev
  rebuild pair) is a stronger signal than a generic band — but that calibration
  sampled only the same-machine population (next bullet).
- **Lucene HNSW is documented non-deterministic**: concurrent graph build/merge
  (lucene#12660), and merges genuinely change the graph — "graphs produced by
  merging two segments are no longer necessarily equivalent to indexing one segment
  directly" (lucene#12050, committer text); Anserini documents run-to-run
  effectiveness variance as expected HNSW behavior. A deterministic path
  (single-threaded, single-segment, fixed insertion order) exists but is fragile.
- **Embedding floats themselves can differ across environments with byte-identical
  weights**: ONNX Runtime's CUDA EP defaults to `cudnn_conv_algo_search=EXHAUSTIVE`
  (runtime algorithm selection that can differ per device state) and `use_tf32=1`
  on Ampere+; driver/cuDNN version skew is independently sufficient for
  non-bit-identical outputs (onnxruntime#4611; PyTorch reproducibility notes).
  **UNVERIFIED:** numerical behavior of compute under Windows Sandbox GPU-PV
  specifically — no primary source either way. Note 734's fingerprint check covers
  *weights*, not *outputs*, so "fingerprint-identical" does not close this class.
- **The decomposition the instrument needs is cheap at this corpus size** (~5k
  vectors → exact KNN per query is milliseconds): compare each environment's HNSW
  results to its *own* exact-KNN truth. If the exact neighbor sets already differ
  between dev and sandbox, the divergence is upstream in the embeddings (TF32 /
  algo-search / driver — an environment-sensitivity question, possibly a real
  product concern); if exact sets match and only HNSW sets differ, it is graph
  nondeterminism (instrument noise, not a regression). This single control settles
  T3.3 as the right design and gives finding 5 its discriminating experiment.

**Implication for T4:** confirmed and sharpened — a ≥7/10 overlap gate calibrated on
n=3 same-machine rebuilds holds constant precisely the two variance sources the
fresh-install path changes (embedding floats, build/merge schedule). The sound
fixes: gate on recall-vs-exact-truth deltas (stable by construction), keep
overlap@k as a descriptive secondary, and if any overlap threshold survives,
calibrate it on the actual fresh-install population with a larger query sample
(literature uses 25+ queries; the golden set has 10).

Sources: [lucene#12050](https://github.com/apache/lucene/pull/12050) ·
[lucene#12660](https://github.com/apache/lucene/pull/12660) ·
[Elastic: HNSW merging](https://www.elastic.co/search-labs/blog/hnsw-graphs-speed-up-merging) ·
[Anserini dense retrieval (arXiv:2304.12139)](https://arxiv.org/pdf/2304.12139) ·
[ORT CUDA EP docs](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html) ·
[onnxruntime#4611](https://github.com/microsoft/onnxruntime/issues/4611) ·
[Beyond Benchmarks (arXiv:2407.08275)](https://arxiv.org/html/2407.08275v1) ·
[Qdrant benchmarks](https://qdrant.tech/benchmarks/)

### R2. The round workflow is Session-Based Test Management, independently reinvented — conform to its vocabulary

The round loop has re-derived SBTM (Jonathan & James Bach, STQE Nov/Dec 2000 —
primary source fetched) element for element: the round ≈ **session** (chartered,
uninterrupted, reviewable); round 6's pre-registration ≈ **charter** (declared
before execution, queued in a "hopper"); the gated retrospective ≈ **debrief**
(Bach's PROOF agenda: Past/Results/Obstacles/Outlook/Feelings); and T7's
time-attribution question is answered by **TBS metrics** (Test design & execution /
Bug investigation & reporting / Setup), including the diagnostic use ("testers
spending only a third of their time actually testing … suggests an ongoing
obstacle") and the simultaneity rule (measure what *interrupts* testing, not what
co-occurs). SBTM also sanctions **on-charter vs. on-opportunity** accounting — the
exact vocabulary for T2's by-catch counterweight (round 6's 749 discovery was
opportunity testing, and SBTM says to measure it, not suppress it).

Adopt with attribution rather than coining rivals: *charter*, *debrief*, *TBS*,
*on-opportunity*. T7's proposed buckets (setup / install-wait / coverage work /
investigation / write-up) are a domain refinement of TBS — name them as such. For
the pre-registration *rationale* (preventing post-hoc rationalization), the
complementary citation is scientific pre-registration (HARKing prevention); SBTM
supplies the mechanism, that literature the argument. **T2's needs-round gate has
no named prior art** — nearest framings are risk-based testing (Bach/Bolton RST:
assess risk before committing depth) and regression-test selection (Yoo & Harman
2012 survey); frame T2 as combining those, not as implementing a known method.
Attribution note: the STQE article is copyrighted (free download, no open license)
— use the terms freely, keep quotes short and attributed, paraphrase mechanics.

Sources: [SBTM original article (STQE 2000, PDF mirror)](https://www.ida.liu.se/~TDDD04/labs/2020/exploratory_testing/stqe-sbtm.pdf) ·
[Satisfice download page](https://www.satisfice.com/download/session-based-test-management) ·
[Yoo & Harman, STVR 2012](https://onlinelibrary.wiley.com/doi/abs/10.1002/stvr.430) ·
[RST methodology](https://rapid-software-testing.com/about-rapid-software-testing/) ·
[Preregistration (PLOS Biology)](https://journals.plos.org/plosbiology/article?id=10.1371%2Fjournal.pbio.3000690)

### R3. Windows Sandbox cannot snapshot — the fixed-cost lever is round-mode policy, not new machinery

- **No snapshot/save-state exists or is coming**: the official FAQ (updated
  2025-09) answers "How do I save the Sandbox state?" with "closing it deletes all
  … state," and the new `wsb.exe` CLI's full verb set (`start/list/exec/stop/
  share/connect/ip`) has no checkpoint/suspend. Architectural, not a gap — the
  sandbox composes a throwaway image from host system files. Do not plan around it.
- **Pre-staging the ~10 GB model set via a read-only `MappedFolder` is the
  documented-legitimate amortization** — a reparse point onto a host cache, nothing
  persists into the disposable image. The harness's existing `models-mapped` mode
  *is* this pattern; the real decision is **policy** (which rounds must exercise
  the true Install AI download — itself a coverage item — vs. which may map the
  cache). That folds into T2/T6: the download is a needs-round question for *some*
  rounds, not a fixed tax on *every* round.
- **Fallback if full amortization is ever needed**: Hyper-V golden image +
  `Checkpoint-VM` (standard practitioner pattern), at the cost of weakening
  "clean machine" to "restored-to-checkpoint." **Blocking UNVERIFIED:** whether
  GPU-PV (`Add-VMGpuPartitionAdapter`) delivers working CUDA in a client Hyper-V
  guest — needs a live spike before any commitment (one negative anecdote exists
  for Sandbox CUDA visibility, contradicted by this harness's own working vGPU
  rounds).
- **Headless lifecycle is now scriptable** (`wsb start/exec/stop`, with the
  documented caveat that `exec` returns no process output — results must be written
  to a shared folder, which the harness already does). **UNVERIFIED contradiction**
  worth a 2-minute live test: the FAQ still says one instance at a time while the
  CLI's `list` implies plural sessions.

Sources: [Sandbox FAQ](https://learn.microsoft.com/en-us/windows/security/application-security/application-isolation/windows-sandbox/windows-sandbox-faq) ·
[Sandbox CLI](https://learn.microsoft.com/en-us/windows/security/application-security/application-isolation/windows-sandbox/windows-sandbox-cli) ·
[.wsb config / MappedFolders](https://learn.microsoft.com/en-us/windows/security/application-security/application-isolation/windows-sandbox/windows-sandbox-configure-using-wsb-file) ·
[GPU partitioning (Server 2025)](https://learn.microsoft.com/en-us/windows-server/virtualization/hyper-v/gpu-partitioning) ·
[Checkpoint-VM](https://learn.microsoft.com/en-us/powershell/module/hyper-v/checkpoint-vm)

### What the research changes in the theorization

1. **T3/T4 are upgraded from "candidate direction" to "the instrument is wrong in a
   known way with a standard fix."** Exact-KNN recall decomposition replaces
   overlap-only parity as the end-state; overlap@k demotes to descriptive. This
   also hands 734 finding 5 its discriminating experiment (embeddings-differ vs.
   graph-differs) — runnable host-side against archived round evidence plus one
   dev-stack pass, no new round required, which is itself T2's thesis vindicated.
2. **T2/T7 gain established vocabulary and lose novelty risk** — adopt
   charter/debrief/TBS/on-opportunity from SBTM with attribution; only the
   needs-round gate is genuinely novel and should be framed as RST × test-selection.
3. **T7's snapshot branch is closed** (no Sandbox persistence, ever); the
   install-cost lever collapses into round-mode policy, reinforcing T6's
   arrival-state axis as the place that decision lives.

## Codebase investigation (2026-07-17) — facts the design stands on

Read-only pass over the harness and wire types (subagent, `file:line`-cited; the
two design-pivoting claims re-verified directly). What exists, and the surprises:

- **The golden baseline stores identities only** — `expectedTop10` basenames per
  query (`gen_golden_parity.py:251`), no scores, no trace. The round's raw captures
  *do* contain the full response incl. `searchTrace`; the dense-leg precondition is
  its only consumer (`check_golden_parity.py:293-313`).
- **Per-hit leg scores already exist on the wire**: `SearchTrace.HitStage(id, rank,
  score, detail)` per returned hit, with `legScores()` extracting sparse/dense/
  splade/fusion scores (`SearchTrace.java:152-167`). But there is **no query-level
  per-leg candidate list** — a candidate that lost at fusion leaves no trace, so leg
  top-ks cannot be reconstructed from the trace alone.
- **No exact/brute-force KNN path exists anywhere** — all vector retrieval is
  HNSW `KnnFloatVectorQuery` (`ReadPathOps.java:280-293`, `ChunkSearchOps.java:488`);
  no exact mode, no exhaustive fallback, and **no vector export surface**: vectors
  are non-stored `KnnFloatVectorField` (`WritePathOps.java:299`), readable only by
  Worker-internal Lucene readers, unreachable from Head by Hard Invariant #1.
- **The search API takes a `mode` parameter** (the golden queries are POSTed with
  `mode:"hybrid"`, `gen_golden_parity.py:87-99`) — single-leg captures are available
  through the existing public API.
- **The coverage register has no structured reach field** — reach mechanics live
  only in free-text `validateHow` prose (rich for `cohort:mcp` only);
  `derive_round_plan.py` emits what/how-to-judge but cannot emit how-to-reach, and
  **cannot derive `mustWatch` items at all** (no manifest key; self-documented at
  `derive_round_plan.py:172-184`).
- **No charter artifact and no time accounting exist** — `round-plan.md` is a
  post-shipment derived checklist, not a pre-committed charter; evidence carries a
  single start timestamp (`collect-evidence.ps1:77`); the retrospective gate
  (`check_coverage.py:71-113`) is keyword-presence over four topic groups, no
  timing.
- **Round-mode policy** lives at `sandbox-CLAUDE.md:20-23` (first + final
  qualifying rounds must be `fresh-install`; intermediate rounds may use
  `pre-staged-models`). **No upgrade-install mode exists**; the only adjacent
  machinery is the install-state fingerprint in `collect-evidence.ps1:130-164`
  (detection + warning, not a supported scenario) and the
  `warm-reinstall-over-existing-data` must-watch.
- **Failure attribution is an established, gate-enforced house pattern at the
  product layer**: `SearchReasonCode` (25 typed members) populates
  `SearchTrace.Degradation`'s reason fields, with
  `check-search-degradation-reason-codes.mjs` enforcing a closed vocabulary and
  mandated FE wording both directions. The parity check's ad-hoc `BLOCKING (…)`
  strings violate, one layer up, the pattern the product itself is held to.

## Design (settled 2026-07-17) — general; mechanism, not implementation

Five parts. Everything extends an existing seam; nothing replaces the 728
architecture. The instrument work (A) leads because the loop work (B) depends on
it: a scheduling gate is only as good as the payloads it routes on.

### Part A — Parity becomes a three-signal instrument (supersedes overlap-as-gate)

- **A1 — Score-identity probe (embedding-variance detector; no product change).**
  The baseline format grows from identities-only to identities + per-hit leg
  scores (already on the wire, `SearchTrace.HitStage`). A dense-leg score for a
  (query, doc) pair is a pure function of the two embeddings — so comparing dense
  scores on the *shared* (query, doc) pairs between baseline and round
  discriminates the two divergence classes research R1 identified: scores differ →
  embedding-output variance (TF32 / algo-search / driver — an environment-
  sensitivity question about the product); scores match while sets differ → HNSW
  selection variance (instrument noise, not a regression). This is finding 5's
  discriminating experiment, realized from existing wire data instead of the
  exact-KNN export the research assumed (explore-before-implementing paying off).
- **A2 — Per-leg capture (attribution; no product change).** Golden queries are
  additionally captured once per single retrieval mode via the existing public
  `mode` parameter, giving true per-leg top-10s that the trace structurally cannot
  provide (per-hit only; fusion-dropped candidates invisible). A parity failure
  then names the diverging leg instead of the symptom.
- **A3 — Exact-truth recall gate (end-state; needs a spike).** The
  literature-standard gate is per-environment recall vs. exact KNN. No exact path
  or vector export exists today, so A3 needs either Lucene's exact-search
  realization or a Worker-side, eval-gated exact scorer (Worker-owned per Hard
  Invariant #1, exposed over the existing gRPC→API path). Decided in principle;
  sequenced after A1/A2, whose evidence determines whether the remaining
  unexplained class justifies a new product surface. This is an evidence-ordering
  decision (the spike's input doesn't exist yet), not a wait-for-more-evidence
  deferral.
- **A4 — Demotion, provenance, typed reasons.** Overlap@k becomes a descriptive
  secondary, never the gate. Every threshold the checker applies carries a
  **calibration-provenance block** (population sampled, axes held constant, n) and
  fails toward "uncalibrated for this population" rather than toward a phantom
  regression. Blocking outputs conform to the reason-code house pattern — a small
  closed vocabulary of typed parity-blocked reasons in the report (lightweight
  conformance; no new CI gate).
- **Baseline consequence:** the format change invalidates existing
  `golden-parity.json` baselines — regeneration at next candidate build is part of
  landing A1, not optional.

### Part B — Charter, debrief, and the scheduling gate (SBTM-aligned)

- **Charter (per-round, staged).** A small generated artifact — reusing the
  launcher's existing generated-authority seam next to `validation-mode.md` —
  carrying: the round's purpose, each open blocker's **needs-round / needs-dig**
  classification, the chosen mode and why, and expectations. Round 6's
  pre-registration proved the practice; the charter makes it an artifact instead
  of tempdoc prose. `round-plan.md` stays as the derived coverage checklist — the
  charter is *why this round*, the plan is *what to touch*; they are complements.
- **Debrief.** The existing retrospective gate is extended to require a TBS-style
  self-report (Bach & Bach's Test/Bug/Setup accounting, adapted to: setup /
  install-wait / coverage work / findings investigation / write-up, plus
  on-charter vs. on-opportunity) — with attribution to SBTM, terms adopted, not
  reinvented. The mechanical floor: finalize computes a coarse timeline from
  evidence-file timestamps as the independent cross-check of the self-report
  (mechanize capture, keep judgment — 728 P2).
- **The scheduling gate (loop-owned).** `cut-a-release.md`'s loop gains one rule:
  a new round is scheduled only when at least one open blocker is classified
  needs-round, **or** it is the final qualifying round. Needs-dig classifications
  carry an owner and a timebox so the gate cannot stall a release indefinitely.
  On-opportunity value is measured (debrief), not used to justify confirmatory
  rounds.

### Part C — Arrival-state round modes

The launcher's mode set gains **`upgrade-from-release`**: stage the previous
public release's installer, install it, seed minimal data, then install the
candidate over it. The round-mode policy sentence extends to: a release's
qualifying set includes the existing fresh-install requirements **plus at least
one upgrade round** (0.2.0 is the first release with a real predecessor). The
existing install-state fingerprint becomes an assertion input in this mode
(prior-install-found is the *expected* state) instead of a warning. The
`warm-reinstall-over-existing-data` must-watch covers the reinstall state and
stays; upgrade-from-previous-version is a distinct state it does not cover.

### Part D — Reach pointers in the coverage register

Register entries gain an optional structured **`reach`** field (testid / nav
path / API recipe); the brief and `round-plan.md` emit it. A sandbox-tier GUI
surface with no derivable reach is emitted into the brief as **"entry point
unknown — locating it (or proving it absent) is a round deliverable"** — reported,
not fail-closed, because the two known cases (free-chat, workflow-run) are
genuinely unresolved product questions a stage-time block cannot answer.
Fork-risk control: a reach pointer must be verified against frontend source at
brief-generation time (testid greppable) or it is dropped with a warning — a
stale pointer is worse than none. Also in scope here: `mustWatch` entries join
`coverage-manifest.json` so `derive_round_plan.py` stops being structurally unable
to derive them (a real gap the investigation surfaced).

### Part E — Durable statements

- The **independence invariant** is written into the durable mission doc: the
  verifier's self-report is always paired with the host-side mechanical re-run at
  finalize; no future change merges them or lets the round see its own coverage
  bookkeeping. (It contradicted the self-report in both GUI rounds; it is the
  workflow's best-performing control.)
- **Post-0.2.0 retirement review**: execute 728 P1–P3's retirement conditions and
  734 Part F/G's falsifiers against the full release's evidence — including the
  already-convicted size floor, which gets replaced or deleted, not footnoted.

### What this design orphans (teardown owned by THIS tempdoc when parts land)

- **Overlap-as-sole-gate semantics** in `check_golden_parity.py` (threshold
  constants at `:64-66` and the pass/fail rule at `:321-348`) — demoted to
  descriptive output, not deleted. The three preconditions (fingerprint, corpus
  ratio, dense-leg) are sound and stay.
- **The "tolerance HAS now been calibrated" paragraph** in `sandbox-CLAUDE.md` —
  superseded (its n=3 same-machine population doesn't cover the axis the check
  spans); rewritten into A4's calibration-provenance form, preserving the
  underlying data as one named axis rather than deleting it.
- **Identities-only baseline format** (`golden-parity.json`) — regenerated under
  A1's format; old baselines are invalid, not grandfathered.
- **734's "re-check parity via a fresh round" routing pattern** for parity
  findings — superseded by A1/A2's host-side decomposition; 734's finding-5
  routing gets a pointer here.
- Nothing else is deleted: the register, brief generator, round plan, launcher,
  and retrospective gate are all extended in place.

### Decided vs. still open

**Decided:** A1/A2/A4 (instrument), B (charter/debrief/scheduling gate), C
(upgrade mode + policy), D (reach field, reported-not-blocking), E, and the orphan
list. **Still open (implementation-level or evidence-dependent):** A3's
realization (Lucene exact-search vs. Worker eval-only scorer — spike first, and
whether A1/A2's evidence leaves a class that needs it at all); the exact
charter/TBS field set; how much data an upgrade round seeds; whether reach-missing
ever graduates to fail-closed (only if the reported form demonstrably gets
ignored).

## Open questions

1. Does round-5 vs round-6 golden-capture comparison (T3.4, answerable from archives
   today) show sandbox-to-sandbox stability? The answer materially reorders T3/T4.
2. What does the time-attribution of rounds 5–6 (T7) actually show?
3. Is there a second cohort besides `openai-compat` whose requirement semantics
   ("via the product's own endpoint") aren't machine-checked? The `requiredRoutes`
   fix-shape from 728's F2 exists; sweeping the register for cohorts that need it is
   cheap.
4. ~~Where does the needs-round/needs-dig classification live?~~ **Answered by
   §Design Part B:** the gate rule is loop-owned (`cut-a-release.md`); the
   per-round classification record is the charter artifact (round-owned).
5. ~~(Post-research) The exact-KNN decomposition for finding 5.~~ **Superseded by
   §Design A1:** the same embeddings-vs-graph discrimination falls out of per-hit
   dense-leg score comparison on shared (query, doc) pairs — existing wire data,
   no vector export needed. Running A1 against the archived round-5/6 evidence +
   a score-bearing regenerated baseline is the highest-information next step for
   finding 5. Exact-KNN (A3) remains the end-state gate question, spike-gated.

## Derisk (2026-07-17) — all six load-bearing assumptions verified; probe dry-run produced real signal

Read-only pass (repo + archived round evidence + a scratchpad probe script); plan
pre-registered and approved. Verdicts:

- **U1 (captures carry the data):** YES — both rounds' `evidence/golden/*.json`
  contain per-hit `trace` arrays with `dense-retrieval` rank + score at full float
  precision (e.g. `0.57117754`), captured by the harness's plain POST. A1 runs
  retroactively against archives.
- **U2 (score semantics):** YES — the per-hit dense score is the dense leg's own
  raw Lucene KNN similarity recorded pre-fusion (`HitProvenanceProjector.java:46`
  stores `hit.score()` from the dense leg's result; emitted at
  `SearchResponseBuilder.java:296-298`). A pure function of the two embeddings.
- **U3 (single-leg modes):** YES — `parseModeOrDefault`
  (`SearchPipelinePresets.java:25-38`): `text`/`lexical`, `vector`, `splade`,
  `hybrid`. A2 needs zero product change.
- **U4/U4b (probe dry-run, round5 ↔ round6):** the decisive result. The two rounds
  agree with each other almost perfectly — **10/10 shared docs on every query,
  dense-score deltas ≤ 1.8e-4** across two different builds and sandbox sessions —
  while both diverge from the dev baseline **identically** (q04 6/10, q06 5/10,
  q08 4/10). The divergence 734 finding 5 measures is a **systematic dev-vs-sandbox
  difference, not round noise**; embedding inference inside the Sandbox is
  reproducible to ~1e-4. (Side signal: BM25 scores drift up to ~0.1 round-to-round —
  corpus-stats sensitivity, resolvable the same way once the baseline carries
  scores.) Shared-pair counts on the failing queries (4-6) are sufficient; the
  probe is not starved. This answers §Open question 1: sandbox-to-sandbox IS stable.
- **U5 (previous installer):** YES — `JustSearch_0.1.0_x64-setup.exe` is a GitHub
  release asset.
- **U6 (reach anchors):** YES — 200 distinct `data-testid`s in `shell-v0`,
  including surface-level ids.

**Confidence: 8.5/10.** Residuals: upgrade-mode installer behavior is only provable
in a live round; the charter/TBS field set may need one iteration; A3 stays
spike-gated (and the dry-run's "systematic + stable + enough shared pairs" result
makes it less likely A3 is ever needed). Recommended implementation: Opus (medium)
orchestrating, Sonnet workers for the bounded mechanical chunks.

## Implementation (2026-07-17)

Landed on `worktree-750-validation-instruments` (pre-PR). Parts A, B, C, D, E as
designed; A3 (exact-KNN gate) remains deliberately deferred and is now **less
likely to be needed** (see the end-to-end result below). Orchestrated: five
bounded Sonnet workers, orchestrator-owned policy prose, integration review, and
a non-ASCII diff check on every worker's output (all clean).

**What shipped, mapped to the design:**

- **Part A** — `golden_common.py` (one authority for identity/leg-score
  extraction, replacing a duplicate); `gen_golden_parity.py` baseline **v2**
  (per-doc leg scores, per-leg top-10s via `mode=vector|text|splade`, and a
  `calibration` block naming each threshold's population);
  `check_golden_parity.py` gains the score-identity probe, per-leg attribution,
  and typed `PARITY_*` reasons on every blocking path; `collect-evidence.ps1`
  captures each golden query per single retrieval mode. **Exit-code semantics are
  unchanged by construction and by proof** (below).
- **Part B** — `sandbox-launch.py --charter/--no-charter` (fail-closed);
  `check_coverage.py`'s retrospective gate additionally requires a TBS
  time-accounting section, plus a report-only evidence-timestamp timeline as the
  independent cross-check; `cut-a-release.md` carries the scheduling gate.
- **Part C** — `--upgrade-from` stages the previous release, records its SHA-256
  and the install/seed/upgrade sequence, and writes `ExpectPriorInstall: true` so
  `collect-evidence.ps1` asserts a prior install as *expected* rather than
  warning. Round-mode policy now requires >=1 upgrade round per release.
- **Part D** — `reach` in the coverage register (testids verified against
  frontend source at generation; a stale one is dropped with a warning), reach +
  `mustWatch` in the manifest, and `derive_round_plan.py` emitting both —
  closing its self-documented inability to derive must-watch items.
- **Part E** — the independence invariant and the calibration-provenance rewrite
  are in `sandbox-CLAUDE.md`; the post-0.2.0 retirement review is recorded below.

**Verification (all green):** 213/213 sandbox harness tests (the CI lane's own
command); `gen_coverage_brief.py --check` exit 0 on the real tree; staging
dry-runs per mode (fresh / pre-staged / upgrade / charter / missing-charter
fail-closed) asserted on staged contents; `./gradlew.bat build -x test` exit 0
(verified unpiped). Live: a clean dev stack (`clean: hard`), the sandbox's own
SciFact corpus ingested (5,189 docs incl. the 5 auto-ingested help docs —
corpus-matched to a real install, unlike the v1 baseline), enrichment to 100%
embeddings + SPLADE, `COMPATIBLE`; v2 baseline generated; the new checker run
against **round 6's real archived evidence**.

### The end-to-end run answered 734 finding 5's open question

**Equivalence first (the guard rail):** with the v1 baseline, the new checker
reproduces round 6's verdict exactly — q04 6/10, q06 5/10, q08 4/10, **exit 1** —
plus the `PARITY_UNCALIBRATED_POPULATION` notice. No recalibration rode along; the
gate did not move while the release is blocked on it.

**Then the v2 baseline decomposed the failure.** Controlled variables, all
verified rather than assumed: byte-identical model fingerprint (`f1d0f4ec...` on
both sides), both sides CUDA-EP on `model_fp16.onnx` (dev confirmed live in
`worker.log`; round 6 recorded GPU-FP16), same corpus (5,189 both sides). Result:

| Population | Dense-score delta on shared (query, doc) pairs |
|---|---|
| sandbox <-> sandbox (rounds 5 vs 6, different builds) | **<= 1.8e-4** |
| dev <-> sandbox (this run, round-6 evidence) | **1.7e-2 to 6.8e-2 on ALL 10 queries** |

Two to three orders of magnitude apart, with identical weights. **The embeddings
themselves differ between the dev and Sandbox environments** — so finding 5 is
**embedding-output variance, not HNSW selection variance and not a ranking-code
regression in the candidate.** The 10/10 flag rate is what makes it legible: the
divergence is systematic and environment-level; only the near-tie semantic
queries (q06/q08) convert it into a ranking miss, which is why 8 queries pass
with the same deltas. (The checker now prints this conclusion itself rather than
leaving a reader to count rows — a refinement the live run earned.)

**Honest limits.** This establishes the *class*, not the mechanism. Not
established: which inference-path difference produces it. One concrete lead the
run surfaced — dev loads a **pre-optimized** graph
(`model_fp16.onnx.cuda.optimized`, `OnnxSessionCache`), and whether a fresh
install's first load produces an identically-optimized graph is unverified;
ONNX Runtime's CUDA EP also defaults to runtime algorithm search and TF32 (see
§Research R1), and the Sandbox's vGPU driver path differs from the host's. Also
not established: that the measured deltas *cause* the q06/q08 misses — it is a
coherent and well-supported mechanism (uniform deltas + near-tie density), not a
proven causal chain. Routing: 734/product, with these leads named.

**Side result:** with a corpus-matched v2 baseline, **q04 now passes (7/10, was
6/10)** — consistent with 734's finding that the help-doc corpus mismatch
contributed roughly one document of shift. q06/q08 are unmoved by that fix, as
734 predicted.

### Known deviation from §Design A4 — deliberate, and it needs an owner decision

§Design A4 says two things the implementation deliberately stops short of, and this
is recorded rather than left as a silent gap between design text and code:

1. *"Overlap@k becomes a descriptive secondary, **never the gate**"* — it is still
   the gate. Overlap is demoted in the *report*; the *blocking rule* is unchanged.
2. *"...and fails toward 'uncalibrated for this population' rather than toward a
   phantom regression"* — the checker still fails with `PARITY_OVERLAP_MISS`. It now
   *reports* toward uncalibrated (the SYSTEMATIC line + the calibration block) but
   does not *fail* that way.

**Why.** A4's replacement gate is A3 (exact-truth recall), which is deferred. Demoting
overlap from blocking with no replacement would not make the gate better — it would
make the parity check non-blocking, and would **unblock 0.2.0 as a side effect of an
instrument change**. Releasing is an owner decision, and it must not be smuggled in as
a refactor's side effect.

**The design is not wrong — the evidence now supports it.** The end-to-end run shows
the q06/q08 block *is* the phantom-regression case A4 describes: a systematic
environment-level difference judged against an envelope that never sampled this
population. So the honest end-state is A4 as written. The gap is that acting on it is
a release decision with a product question attached ("do we accept that a real
install's embeddings differ from dev's?"), which this tempdoc cannot make for the
owner. **Decision needed:** once finding 5's mechanism is understood, either (a)
recalibrate the envelope on a properly sampled dev↔sandbox population and keep overlap
blocking against *that*, or (b) land A3 and let overlap fall to descriptive as designed.
Until then the gate stays where it is, blocking, for the reason above.

### Follow-ups this tempdoc owns (not silently dropped)

- **Post-0.2.0 retirement review** (Part E, gated on the release finalizing):
  execute 728 P1-P3's retirement conditions and 734 Part F/G's falsifiers against
  the full release's evidence — including the size floor, already convicted by
  734 G.6 as a defeated byte proxy, which gets replaced or deleted rather than
  footnoted.
- **A3 (exact-KNN recall gate):** still the literature-standard end-state, still
  spike-gated. The evidence above lowers its priority — the cheap signals located
  the class without it.
- **Envelope recalibration** is deliberately NOT done here: doing it would move
  the gate while 734 finding 5 is open. Once the mechanism is understood, the
  dev<->sandbox population can be sampled honestly and the envelope set from it.
