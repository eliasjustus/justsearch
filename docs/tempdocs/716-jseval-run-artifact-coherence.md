# 716 — jseval run-artifact coherence + worktree ergonomics

- **status:** IMPLEMENTED (2026-07-11, worktree `worktree-716-jseval`) — plan approved by
  orchestrator with two riders (docs: search-quality-register + skills-sync; migration:
  legacy-root read fallback with WARN); all phases + riders landed, full suite 1634 passed /
  2 pre-registered reds. Live defaults-compose repro deferred to publish-time (§Implementation
  log). Merge pending founder direction.
- **created:** 2026-07-11

## Charter question

Three recurring per-session taxes in the jseval harness share a root — run artifacts,
data dirs, and the import path all resolve inconsistently across checkouts/worktrees. What is
the coherent layout, and does the per-run-ephemeral-data-dir shape (711 Item 4's named
retirement path) subsume the wipe-based clean?

## Evidence that motivates the charter (all hit live on 2026-07-11, during 711's publish)

1. **Gates can't find runs by default:** the gate commands' run discovery looks in
   `<data-dir>/eval-results` (`commands/gates.py` `--data-dir` help), but `jseval run`'s
   default `--output-dir` is `scripts/jseval/tmp/eval-results` (`_paths.py:100-101`,
   `DEFAULT_EVAL_RESULTS`). A defaults `run` followed by a defaults gate fails with
   `"no eval-results run with summary.json"` — worked around with manual `--run-dir` during
   711's gate run. Two defaults in one tool that don't compose.
2. **The editable-install/PYTHONPATH trap:** jseval is pip-installed editable against one
   checkout; invoking from any other worktree silently runs the wrong code unless
   `PYTHONPATH=<worktree>/scripts/jseval` is set (documented pitfall, CLAUDE.md table; paid
   again in every 711 detached run script). Candidate fixes: an entry wrapper that resolves
   the package relative to the invoking repo root, or a loud startup assertion that
   `jseval.__file__` is under the CWD's repo root.
3. **Wipe-based clean vs ephemeral run dirs:** 711 Item 4 made `--clean` fail-closed (shipped,
   live-proven incl. a real orphan-Worker kill), and named the structural successor: per-run
   ephemeral data dirs with the cross-run calibration state (`cohort_baselines/`,
   `non_determinism_envelopes/`) relocated OUTSIDE them — wiping becomes obsolete, and the
   orphan-sweep becomes best-effort hygiene instead of measurement-validity-critical. That
   shape was deliberately not built in 711 (scope); this tempdoc owns the judgment call.

## Constraints / relations

- Python-only; no retrieval semantics. The jseval test suite (~1600 tests) is the safety net;
  `test_backend.py` has the clean/orphan coverage from 711.
- The 645 lineage (jseval ownership) and tempdoc 704 Pillar 6 (isolated eval lane) are
  adjacent — check both before designing; Pillar 6 may already claim part of this ground.
- Cheapest evidence for the ephemeral-dir question: count what actually persists across runs
  by design vs by accident (the 711 E4 audit's per-file inventory of the data dir is the
  starting point).

---

## Takeover investigation (2026-07-11, agent takeover in worktree `worktree-716-jseval`)

### Verdict: DO IT NOW — proceed to design.

All three taxes verify as real, file:line-confirmed defects, not hypotheses; none is owned or
in-flight elsewhere; the cheapest evidence (static code read + independent live corroboration
already sitting in `docs/observations.md`) already exists and needn't be re-bought with a live
run. Proceeding to design/plan below.

### Tax 1 — gates-vs-run output-dir mismatch: CONFIRMED, independently corroborated

- `jseval run`'s `--output-dir` **defaults to** `DEFAULT_EVAL_RESULTS` =
  `scripts/jseval/tmp/eval-results` itself (`_paths.py:99-101`), and `artifacts.write_run`
  creates the actual per-run directory **one level under that**:
  `run_dir = output_dir / f"{ts}_{dataset_slug}"` (`artifacts.py:42-45`) — so a defaults `run`
  writes `scripts/jseval/tmp/eval-results/<ts>_<dataset>/summary.json`.
- Every gate command's `--data-dir` is documented and coded as the **parent** of an
  `eval-results/` directory: `gate.py:_latest_run_dir` does `eval_results = data_dir /
  "eval-results"` (`gate.py:64-66`); `ratchet_kernel.resolve_run_dir` shares the same shape
  (`ratchet_kernel.py:132-137`, echoing `"no eval-results run with summary.json"` on miss);
  `commands/gates.py` help text repeats "Data dir containing cohort_baselines/ + eval-results/"
  eight times across `gate`/`relevance-gate`/`perf-gate`/two recall-accounting gates.
- The two flags share the literal string `"eval-results"` but at **different nesting depths** —
  a `run --output-dir X` naively paired with `gate --data-dir X` (the natural first guess, since
  both name "where my run went") fails with `"no eval-results run with summary.json"`. The fix
  requires knowing to pass `X`'s **parent** to `--data-dir`, which is documented nowhere in
  `--help` text (the gates' help says "containing eval-results/", but doesn't warn that `run`'s
  own default doesn't nest that way).
- **Independently corroborated**, not just this tempdoc's hypothesis: `docs/observations.md:1933`
  records the identical symptom, found live during the 702 A/B session (2026-07-10, one day
  before this charter, unrelated agent): *"jseval relevance-gate exits 2 ('no eval-results run
  with summary.json') when pointed at scripts/jseval/tmp/eval-results runs that do contain
  summary.json — data-dir layout expectation mismatch vs jseval run's default output."*
- `commands/release.py:23-24` independently gets this right — its own `--data-dir` default is
  `DEFAULT_EVAL_RESULTS.parent`, proving the codebase already has the correct relationship
  encoded in one place but not shared as a constant the other commands could import.

### Tax 2 — editable-install/PYTHONPATH trap: CONFIRMED, worse than a path-resolution bug

- `pyproject.toml:33-34` registers `[project.scripts] jseval = "jseval.cli:main"` — a
  `pip install -e` console-script entry point. Python resolves `import jseval` via the interpreter's
  `sys.path`, which an editable install points at **one specific checkout's**
  `scripts/jseval/` — fixed at `pip install -e` time, independent of the caller's CWD.
- `_paths.py:_resolve_repo_root()` (tempdoc 351, extended by 644 Axis 1) only fixes *what
  `REPO_ROOT` resolves to once the module has already been imported*. It cannot fix *which
  copy of the `jseval` package's `.py` source* actually executes — that is decided by import
  resolution, before `_resolve_repo_root()` ever runs. Running the `jseval` console-script (or a
  bare `python -m jseval`, without `PYTHONPATH`, from a second worktree) silently executes the
  **first** checkout's code against paths that (thanks to 351/644) do point at the second
  worktree — a hybrid of stale logic and fresh paths, which is more confusing than either failure
  alone would be.
- This is a **documented, currently-manual workaround**, not undiscovered: CLAUDE.md's Common
  Pitfalls table already carries "set `PYTHONPATH=<worktree>/scripts/jseval`... paid again in
  every 711 detached run script" — i.e. every session re-pays the tax and re-discovers the
  workaround rather than the tool refusing or self-correcting.
- No prior tempdoc addresses this: 351 solved CWD-relative *path defaults*; 644 solved
  worktree-vs-main *model asset* resolution; neither touches *which package version executes*.
  Confirmed via `grep -ri "editable\|PYTHONPATH\|pip install -e"` across `docs/tempdocs/` and
  `docs/reference/contributing/common-workflows.md` — no hits besides this tempdoc and the
  CLAUDE.md pitfall-table line itself.

### Tax 3 — wipe-based clean vs ephemeral data dirs: CONFIRMED, this tempdoc is 711's own named successor

- 711 Item 4 (shipped, `backend.py:92-260`, live-proven incl. a real orphan-Worker kill) made
  `--clean` fail-closed: it deletes every top-level entry of the data dir **except**
  `cohort_baselines/` and `non_determinism_envelopes/` (`backend.py:92-101`), verifies the wipe,
  sweeps orphan Worker processes on failure, and hard-errors rather than proceeding dirty.
- 711 **explicitly named and deliberately deferred** the structural successor this tempdoc's Item
  3 is chartered to design: *"Named alternative (not built now): per-run ephemeral data dirs with
  calibration state (`cohort_baselines/` etc.) relocated outside them would make wiping obsolete
  entirely; that is a larger jseval restructure and the retirement path for this design, not its
  first step."* (711 §Item 4 Design, final paragraph) — and lists, in its Reach Judgment section,
  the explicit retirement trigger for its own fail-closed-wipe mechanism: **"Retire when: per-run
  ephemeral data dirs land."** This tempdoc is that retirement path, not new-invented scope.
- Data-dir inventory (what a run's `resolved_data` actually holds, traced via grep of every
  `data_dir / "..."` / `resolved_data / "..."` write site): `cohort_baselines/` (cross-run
  calibration, protected), `non_determinism_envelopes/` (cross-run calibration, protected),
  `eval-results/` (per-run, NOT protected — deleted by `--clean`, contradicting the "eval-results
  is basically per-run" framing: today `--clean` wipes accumulated run **history** too, not just
  index state), `logs/` (worker.log, app.log — per-run), `telemetry/` (per-run NDJSON, copied
  into each run_dir by `artifacts.write_run:37-40` then left behind in the shared dir),
  `index/` + `index/default.index.lock` (Lucene state — the thing `--clean` exists to reset),
  `watched_roots.json` (ingest state — the file whose staleness caused the 691 N-4 incident).
  Everything except the two protected dirs is **index/ingest working state that must reset
  between measurement arms**, confirming the seed's framing: the ephemeral-dir shape (move
  `cohort_baselines/`+`non_determinism_envelopes/` OUT to a stable location, make everything else
  live inside a fresh per-run directory) subsumes wiping rather than merely automating it —
  there is nothing left inside a fresh ephemeral dir that a NEXT run could accidentally inherit.

### Displacement / duplication check (704 Pillar 6, 645 lineage)

- **704 Pillar 6** ("an isolated eval lane") is adjacent but **not overlapping**: its problem is
  *shared single-GPU dev-stack contention* (runs losing to takeovers, GPU gates unable to run in
  hosted CI) and its proposed shape is *lease/ownership* (676 headless-eval contract as an
  isolated lane, eventually a self-hosted GPU runner). It says nothing about run-artifact layout,
  the gates/run directory mismatch, or the PYTHONPATH trap. 704 itself scopes Pillar 6 as
  "explicitly a later pillar" and routes ownership elsewhere for everything it doesn't itself
  cover. No overlap; 716's coherent layout is a *prerequisite hygiene* an isolated lane would
  also want, not a duplicate of it.
- **645 lineage** ("split the jseval CLI monolith") is **status: IMPLEMENTED** (landed
  2026-06-30) and unrelated in kind: it decomposed `cli.py` into per-command-group modules
  (`commands/run.py`, `commands/gates.py`, etc. — the very modules this investigation reads) to
  remove a multi-agent collision hotspot. It did not touch path defaults or data-dir semantics.
  Worth noting as a contributing *shape*: splitting `run` and `gate` into separate files removed
  the one place a reviewer could see both defaults side-by-side and notice the mismatch — a
  legitimate but secondary factor, not a reason to revert 645.
- **351** (jseval CWD-independent path resolution, done 2026-03-24) and **644** (worktree-aware
  model-asset resolution, implemented 2026-06-30) are the direct ancestors of Tax 2's `REPO_ROOT`
  machinery but — per the Tax 2 analysis above — neither actually closes the editable-install
  import-resolution gap; they solve a different half of "wrong worktree" (path values) than this
  tempdoc's remaining half (which package code runs).
- **No existing tempdoc, doc, or shipped code owns the run/gate directory-nesting mismatch or the
  PYTHONPATH-trap fix.** Confirmed via `grep` across `docs/tempdocs/**` for `eval-results`,
  `PYTHONPATH`, `editable`, and `--data-dir` (see file lists above) — the only other hits are
  this tempdoc, the CLAUDE.md pitfall-table line (documents the workaround, doesn't fix it), and
  `docs/observations.md`'s inbox entries (unowned findings, not designs).

### Cheapest evidence — already exists

The charter's own framing ("cheapest evidence... count what actually persists... by design vs by
accident") is satisfiable by static analysis (done above, Tax 3) plus the existing jseval test
suite as the regression safety net — no live pipeline run is needed to validate the *need* for
this work; live verification belongs at implementation-review time for the *fix*, not at the
takeover gate for the *diagnosis*. Test suite baseline confirmed green modulo the two
pre-registered reds: `cd scripts/jseval && PYTHONPATH=<this-worktree>/scripts/jseval
PYTHONUTF8=1 python -m pytest tests/ -q` → **1615 passed, 2 failed**
(`test_correction_probe.py::TestLoadManifest` — pre-existing, unrelated missing data file,
registered in `expected-state.v1.json`, not this tempdoc's).

---

## Theorization (2026-07-11)

### Reframing: three symptoms, one shared root

The charter names three "recurring per-session taxes" as siblings; theorizing about them
together (rather than as three independent bugs) surfaces a shared root: **jseval conflates two
directories that have different lifetimes and different owners under one CLI flag and one
filesystem path**, and does so twice:

1. `--output-dir` (a jseval/Python concept: where result artifacts accumulate) is conflated with
   `--data-dir` (a jseval/Python concept: where result artifacts + calibration state live) —
   same underlying idea, different nesting depth expected by different commands (Tax 1).
2. `JUSTSEARCH_DATA_DIR` (a Java/Gradle concept: the Worker's Lucene index + ingest-state
   directory, owned and mutated by a process jseval does not control once spawned) is conflated
   with the **same** `--data-dir` value that also holds `cohort_baselines/` and
   `non_determinism_envelopes/` (Python-owned, cross-run, and precious) — one path, two owners
   with incompatible lifetimes, is *why* `--clean` needed a protected-set carve-out at all (Tax
   3). Confirmed via `cohort_baselines.py:44-45` (`cohort_baselines_dir(data_dir)` is a bare
   `data_dir / "cohort_baselines"` — no separate root) and `backend.py:104`
   (`env["JUSTSEARCH_DATA_DIR"] = str(resolved_data)` — the exact same `resolved_data` passed to
   Gradle).

Tax 2 (PYTHONPATH) is a different root — an *import*-resolution problem, not a *data*-layout
problem — but shares the family trait: a single ambient value (which package is on `sys.path`,
analogous to which directory a flag points at) silently governs behavior with no cross-check
that it matches the invoker's intent.

### Alternative framings for Tax 1 + Tax 3 (the data-layout root)

**Framing A — fix the mismatch locally (asymmetric patch).** Make `gate`'s `--data-dir` accept
either shape (a dir directly containing `summary.json`-bearing subdirs, or a dir containing
`eval-results/`), or change `run`'s default so `--output-dir` and `--data-dir` are literally the
same string by convention. Cheapest, but treats the symptom: it doesn't touch the deeper
Java/Python data-dir conflation (Tax 3), and "accept either shape" is exactly the kind of
silent-dual-interpretation logic that produces confusing failures later (a directory that looks
right under one reading and wrong under the other).

**Framing B — one canonical data-dir root, three lifetime-scoped children (chosen direction).**
Stop treating "output dir" and "data dir" as separate concepts with separately-defaulted flags.
One root (`--data-dir`, replacing `--output-dir` as the operator-facing flag name everywhere)
with a fixed internal shape:
- `<data-dir>/eval-results/<ts>_<dataset>/` — durable results archive (today's `run_dir`,
  unchanged in spirit, now nested one level so `gate`'s existing expectation becomes the only
  expectation).
- `<data-dir>/cohort_baselines/`, `<data-dir>/non_determinism_envelopes/` — durable calibration
  state, physically separated from the Worker's Lucene runtime (see Framing D below) rather than
  merely protected-by-name during a wipe.
- `<data-dir>/runtime/<backend-invocation-id>/` (or a bare tempdir) — the Worker's
  `JUSTSEARCH_DATA_DIR` for one `start_backend()` lifetime: `index/`, `watched_roots.json`,
  `logs/`, `telemetry/`. Never wiped because it is never reused — `--clean`'s entire job
  (delete-and-verify-and-sweep-orphans) becomes unnecessary because there is nothing to delete:
  the next run gets a new `runtime/` child and the old one is simply abandoned (or reaped by
  best-effort background hygiene, per the seed's "orphan-sweep becomes best-effort hygiene
  instead of measurement-validity-critical").

**Framing C — go further: make `eval-results/` itself an explicit two-tier store** (recent runs
inline, older runs rolled into a compacted history db) to bound unbounded growth now that
nothing ever prunes it. Theorized but **out of scope for this tempdoc** — it's a retention policy
question orthogonal to the coherence question the charter asks, and `history_db` (already
threaded through `run.py`/`history.py`) is the existing seam for trend data; raise it as a
follow-up rather than absorbing it here.

**Framing D — for the Java/Python data-dir split specifically:** should `JUSTSEARCH_DATA_DIR`
(Worker-owned) and jseval's calibration/results root (Python-owned) be *the same physical
directory tree with sub-paths* (today's shape, minus the protected-set hack) or *two entirely
separate directory trees* (e.g., calibration lives under `scripts/jseval/tmp/`, never inside
whatever path is handed to Gradle)? The two-tree shape is theorized as cleaner: it removes the
possibility of the Worker process ever touching, listing, or racing against Python's calibration
files (today's proximity is accidental — the Worker does not read `cohort_baselines/`, per
`grep -r cohort_baselines modules/` returning zero hits — it just happens to share a parent
directory). Design should verify this null-result before committing.

### Alternative framings for Tax 2 (PYTHONPATH / editable install)

**Framing E — document better.** Rejected outright by the seed's own design bar ("delete the
paper-cut CLASS... not add more pitfall documentation") and by the evidence that the pitfall is
*already* documented (CLAUDE.md) and still re-paid every session — documentation has been tried
and doesn't hold.

**Framing F — loud fail-closed self-check (lower risk).** At `cli.py:main()` entry, compare
`Path(jseval.__file__).resolve()`'s repo root against the CWD-detected repo root (the same walk
`_paths._resolve_repo_root()` already performs). On mismatch, refuse to proceed and print the
exact `PYTHONPATH=...` remedy. Converts "silently runs stale code from a different checkout"
(the actual paper-cut class named in the charter) into "loudly refuses with the fix inline" — the
same shape 711 chose for `--clean` (best-effort-and-silent → fail-closed-and-loud). Low
implementation risk; still requires one manual step per session.

**Framing G — self-healing re-exec (higher ambition, higher risk).** On the same mismatch
detected in Framing F, instead of refusing, re-exec the current process
(`os.execve`/`subprocess` replacement) with a corrected `PYTHONPATH` injected — the class is
*deleted*, not merely surfaced, because the wrong-copy code path never actually executes user
logic. Risks: re-exec semantics differ Windows-vs-POSIX (this repo is Windows-primary per the env
banner), a detection bug could infinite-loop or mask a legitimately-intended cross-checkout
invocation (e.g. deliberately testing worktree A's harness against worktree B's data — does that
usage exist? Not observed in any command's `--help` or docs, but design should confirm before
foreclosing it), and it adds process-relaunch latency to every invocation, not just the buggy
minority. Framing F is the safer default; Framing G is worth naming as a stretch option for
design to accept or reject with reasons, not to silently drop.

### Broader principle this tempdoc points toward

The shared root above ("one ambient path/flag governs two things with different lifetimes and
owners, with no structural check that they agree") is an instance of a pattern already named in
this codebase's own history: 711's Item 1 (RMW field preservation) generalized "per-call-site
discipline" into "declared disposition enforced at the one choke point." The same move applies
here at a coarser grain — instead of every command independently defaulting and re-deriving
"where do my results live," there should be **one declared data-dir shape, constants for every
child path, imported everywhere** (mirroring `_paths.py`'s existing role for `REPO_ROOT`, just
extended to cover the *internal* shape of a data dir, not only its root). This is a design
choice, not a foregone conclusion — noted here as a candidate register-grade principle for the
design phase to adopt or reject explicitly, consistent with the `explore-before-implementing`
discipline of naming what already exists before adding a new mechanism.

### Risks / hidden assumptions surfaced

- **Migration surface is nontrivial.** `docs/reference/jseval-pipeline-reference.md` hard-codes
  `tmp/eval-results/<timestamp>_<dataset>/` (output structure section) and
  `output_dir: tmp/eval-results` (YAML config example); `docs/explanation/08-observability.md`
  documents `<dataDir>/cohort_baselines/<cohort_hash>/envelope.json`; `docs/how-to/
  calibrate-drift-baseline.md` and `recalibrate-phase3-baseline.md` both reference `--data-dir`
  usage; any layout change must update all of these in the same change, not leave them stale
  (the exact failure mode 711 found and fixed for the `--clean` doc-drift).
- **Backward compatibility for existing on-disk data dirs / CI workflow YAML** — a rename or
  re-nesting breaks any already-materialized `tmp/eval-results/` on a developer's machine and any
  hardcoded path in a workflow file. Design should check `.github/workflows/*.yml` for jseval
  path references before committing to a rename.
- **`bisection.register_run` and `history.append_run`** both take `output_dir`/`history_dir`
  directly (`run.py:361,421`) — a data-dir reshape must thread the corrected root through these
  too, or the two get quietly desynced from `write_run`'s new nesting.
- **Framing G's cross-checkout-by-design use case** (deliberately running one worktree's harness
  against another's data) is asserted absent above from a docs/`--help` sweep, not proven absent
  by exhaustive code search — design should do the exhaustive check before foreclosing it, since
  foreclosing a real use case would be a regression, not a fix.

**Summary for the user:** the three taxes share a root — jseval uses one flag/one directory to
mean two things with different lifetimes (an operator-facing bug for Tax 1+3, an import-resolution
version of the same idea for Tax 2). The chosen direction (Framing B + Framing F) is a canonical
`--data-dir` with a declared internal shape (durable results / durable calibration / ephemeral
per-run runtime, physically separated) plus a loud self-check that refuses to run stale
cross-checkout code instead of silently doing so. I flagged a self-healing alternative (re-exec)
as a stretch option and a two-tier eval-results retention question as an explicit non-goal, both
for the design phase to accept or reject on the record rather than silently drop.

---

## Design (2026-07-11)

Investigation for this section traced the *exact* sites Framing B's constant would touch (not
just the ones the charter named), because the initial theorize-stage framing ("rename
`--output-dir` to `--data-dir` everywhere") turned out to be **overscoped relative to the actual
problem** — `grep` shows `--output-dir` is used by 20+ unrelated options across
`analysis.py`/`bench.py`/`ui.py`/`utility.py` for concepts (bench claim dirs, screenshot output,
judged-record output) that have nothing to do with the gates/run mismatch. Renaming those would
be a large, disproportionate blast radius for zero benefit — corrected below to the actual
minimal shape the problem requires.

### The precise site Tax 1 and Tax 3 share (traced, not assumed)

`commands/calibrate.py:cmd_calibrate` — the command that *writes* `cohort_baselines/` — is the
literal colocation site: its `--data-dir` option (`calibrate.py:141-143`, "Override
`JUSTSEARCH_DATA_DIR`. Defaults to the eval-mode data dir used by `jseval run
--start-backend`") is passed straight into `jseval.calibrate.calibrate(data_dir=...)`, which both
launches the backend **and** writes `<data_dir>/cohort_baselines/<hash>/envelope.json` at that
same path. This is the one place the coupling 711 had to carve a protected-set exception for is
actually *created* — not an accident of operator convention (theorize's initial guess), a
structural choice in `cmd_calibrate` itself. Every downstream reader
(`cmd_recalibrate_nightly_baseline`, `cmd_calibrate_drift_baseline`, every `gates.py` command,
`release.py`) inherits the same "`--data-dir` containing `cohort_baselines/`" contract and is
therefore already speaking the *post-fix* vocabulary — only `cmd_calibrate`'s own write site (and
the backend-side protected-set carve-out it forces) needs to change.

### Chosen shape (Framing B, corrected to minimal blast radius)

**1. One canonical, stable jseval data-dir root — reuse, don't invent.** `_paths.py` gains
`DEFAULT_JSEVAL_DATA_DIR = _JSEVAL_TMP` (i.e. `scripts/jseval/tmp/`) as a named constant.
`commands/release.py:23` already computes this exact value ad hoc as
`DEFAULT_EVAL_RESULTS.parent` — the correct relationship has existed in the codebase since that
command was written, just not shared. `DEFAULT_EVAL_RESULTS` becomes a derived value,
`DEFAULT_JSEVAL_DATA_DIR / "eval-results"` — the same physical path as today, so `run`,
`requery`, `bench`, `analysis`, and `ui`'s `--output-dir` flags are **untouched** (no rename, no
behavior change for that flag family).

**2. Every jseval-owned-artifact `--data-dir` gets the shared default.** `commands/gates.py`'s
seven `--data-dir` options (currently `required=True`, forcing every gate invocation to
hand-compute and pass the parent path) and `commands/calibrate.py`'s two *reader* commands
(`recalibrate-nightly-baseline`, `calibrate-drift-baseline`, both already documented as "data dir
containing `cohort_baselines/`") default to `DEFAULT_JSEVAL_DATA_DIR`, mirroring `release.py`'s
existing pattern instead of inventing a new one. A defaults-only `jseval run` followed by a
defaults-only `jseval gates relevance-gate --dataset X` now composes without the operator ever
reasoning about nesting depth — Tax 1 closed by construction, not by a smarter error message.

**3. `cmd_calibrate` splits its one overloaded `--data-dir` into its two real concerns.** Today's
single flag conflates "which backend to calibrate against" (Worker-owned, ephemeral,
`JUSTSEARCH_DATA_DIR`) with "where the resulting envelope is filed" (jseval-owned, durable,
`cohort_baselines/`). The design splits it: the backend-target dir keeps its current
env-var/override resolution (unchanged — `cmd_calibrate` still needs to say which running
instance it's sampling), and the envelope-write location becomes `DEFAULT_JSEVAL_DATA_DIR` by
default, independently overridable. This is the one real flag-surface/behavior change in this
design (everything else is additive defaults); the migration note in Plan covers it.

**4. `cohort_baselines/`/`non_determinism_envelopes/` stop living inside `JUSTSEARCH_DATA_DIR`.**
Once (3) lands, nothing durable is ever written inside the Worker's runtime data dir by
construction — `backend.py`'s `_protected = {"cohort_baselines", "non_determinism_envelopes"}`
carve-out (`backend.py:100`) and the protected-aware branches of `_attempt_wipe`/
`_clean_data_dir` (`backend.py:171-205+`) have nothing left to protect and collapse to an
unconditional wipe. This is **711 Item 4's own named retirement condition** ("Retire when:
per-run ephemeral data dirs land") satisfied at jseval's own boundary — not by making the
Worker's `JUSTSEARCH_DATA_DIR` itself per-invocation-timestamped (a larger, riskier change
reaching into `WorkerSpawner.java`/Gradle and the orphan-detection semantics 711 just shipped —
explicitly **out of scope**, named as a follow-up candidate, not solved here), but by removing
the *only reason* a wipe ever needed a protected set. `--clean` keeps its fail-closed
verify-and-sweep contract (711's actual correctness property); it just stops needing to
distinguish precious from disposable content, because after this change everything inside
`JUSTSEARCH_DATA_DIR` is disposable by definition.

**5. Tax 2 — loud fail-closed self-check at the one CLI entry point (Framing F, chosen over
Framing G).** `jseval/cli.py`'s top-level `main()` is the single choke point every invocation
(console-script or `python -m jseval`) already passes through. It gains one check, reusing
`_paths._resolve_repo_root()`'s existing CWD-walk logic rather than inventing new detection: if
the *imported package's own file location* resolves to a different checkout than the
*CWD-detected* repo root, refuse before dispatching to any subcommand, printing the exact
`PYTHONPATH=<detected-root>/scripts/jseval` remedy inline (the same value CLAUDE.md's pitfall
table already tells operators to set by hand). An escape hatch env var
(`JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1`) permits the refusal to be overridden — this
deliberately **reuses the existing escape-hatch idiom** already shipped in this exact codebase
(`gates.py --allow-engine-mismatch`, tempdoc 644's homogeneity refusal) rather than inventing a
new opt-out shape.

**Framing G (self-healing re-exec) — considered and rejected for now:** re-execing the process
with a corrected `PYTHONPATH` would delete the manual-fix step entirely, but Windows/POSIX
re-exec semantics differ (this repo is Windows-primary), a detection bug risks an infinite
relaunch loop, and it adds relaunch latency to every invocation rather than only the minority
that are actually mismatched. A `grep` across every command's `--help` text and
`docs/reference/contributing/common-workflows.md` found no documented use case for *deliberately*
running one worktree's harness against a different worktree's checkout, so Framing G's
foreclosure risk (theorize's open concern) is not blocking — but the lower-risk, reversible
Framing F is the better first step; Framing G can be revisited if the manual-fix step itself
turns out to still be a measurable tax after F ships.

**Explicit non-goals (named, not silently dropped):**
- **Framing C (two-tier `eval-results/` retention)** — out of scope; a retention-policy question
  orthogonal to layout coherence, and `history_db` is the existing seam for trend data if pruning
  is ever needed.
- **Per-invocation-timestamped `JUSTSEARCH_DATA_DIR`** (making the Worker's own runtime dir
  disposable rather than reused-then-wiped) — out of scope; Java-side surface beyond this
  jseval-only tempdoc's boundary, named as a follow-up.

### Orphans (named now, deleted/updated in this same PR, not a later sweep)

- `backend.py`'s `_protected` parameter and its threading through `_attempt_wipe`/
  `_clean_data_dir`/`start_backend`'s `--clean` branch — collapses to unconditional wipe once (4)
  lands. Not a revert of 711 (which remains correct and shipped); this is 711's own declared
  retirement condition being exercised.
- `commands/release.py:23`'s ad hoc `default=lambda: str(DEFAULT_EVAL_RESULTS.parent)` —
  replaced by the shared `DEFAULT_JSEVAL_DATA_DIR` constant (same value, now named and shared
  instead of independently re-derived).
- `cmd_calibrate`'s current `--data-dir` help text ("Override `JUSTSEARCH_DATA_DIR`") — rewritten
  to describe the split concern from (3).
- **Docs, same PR (711's own "docs alignment" precedent applies again):**
  `docs/reference/jseval-pipeline-reference.md` (Output Structure section, YAML config example,
  the "configured eval-results / cohort_baselines layout" observability paragraph),
  `docs/explanation/08-observability.md` (`<dataDir>/cohort_baselines/...` schema description),
  `docs/how-to/calibrate-drift-baseline.md`, `docs/how-to/recalibrate-phase3-baseline.md`, and
  CLAUDE.md's Common Pitfalls PYTHONPATH row (rewritten to point at the new loud error's remedy
  instead of prescribing the manual fix as the only path). Verified via `grep` (no `.github/
  workflows/*.yml` reference any jseval path — zero hits — so no CI migration surface exists
  beyond docs).

### Reach judgment

**Principle 1 (Tax 1+3): "A jseval-owned artifact lives in a jseval-owned, stable directory root
— never nested inside a directory owned and mutated by a process jseval does not control the
lifecycle of."** This generalizes 711 Item 1's "declared disposition at the one choke point" idea
(there: field-level, in the write path; here: directory-level, in the data-dir contract). Verified
against the rest of the data-dir inventory before generalizing further: `logs/` and `telemetry/`
also live inside `JUSTSEARCH_DATA_DIR` today, but correctly so — they are *written by the Worker
process itself* (worker.log/app.log, telemetry NDJSON), not by jseval, and jseval already copies
what it needs out (`artifacts.write_run` copies telemetry into `run_dir`, `commands/ops.py` reads
logs in place) rather than claiming ownership of the originals. The boundary is "who writes the
primary copy," not "what sounds calibration-adjacent" — this correctly limits the principle's
reach rather than over-applying it to files that are legitimately Worker-owned.
**Earning its keep:** zero future `observations.md` entries of the Tax-1/Tax-3 shape (already
two, both pre-dating this tempdoc: `:1933`, `:548`); `_protected` literally absent from
`backend.py`. **Retire when:** the Worker's own `JUSTSEARCH_DATA_DIR` becomes naturally
per-invocation-ephemeral (the named-but-deferred Java-side follow-up) — at that point even
`logs/`/`telemetry/` stop needing an explicit copy-out, and this principle's jseval-side half
becomes moot.

**Principle 2 (Tax 2): "An ambient fact that silently governs behavior (which package version is
imported, which engine set is realized) must be checked at the single entry point and refused
with an inline remedy on mismatch — not merely documented as a pitfall."** Not new to this
tempdoc: it is the same shape as tempdoc 644's `--allow-engine-mismatch` refusal and 711 Item 4's
fail-closed wipe; this design is the third instance, applied to import resolution instead of
engine realization or destructive cleanup. No other unapplied instance was found in jseval or
elsewhere in this Python-only investigation's scope. **Earning its keep:** the CLAUDE.md pitfall
row's "paid again in every 711 detached run script" framing stops being true — a wrong-checkout
invocation errors immediately instead of running silently. **Retire when:** jseval moves to a
per-worktree-isolated install model (each worktree `pip install -e`s its own copy) — at that
point cross-checkout execution becomes structurally impossible and the check is dead code that
should be deleted, not left as unreachable apparatus.

**Summary for the user (design):** the fix is one shared constant (`DEFAULT_JSEVAL_DATA_DIR`,
already correctly computed ad hoc by one existing command) that becomes every jseval-owned
artifact's home and default, plus splitting `calibrate`'s one overloaded flag into its two real
concerns — that closes Tax 1 and lets Tax 3's protected-set carve-out (711's own named retirement
trigger) delete itself. Tax 2 gets a loud refuse-with-remedy check at the CLI's one entry point,
reusing the exact escape-hatch idiom the codebase already uses for engine-mismatch refusals. I
corrected my own earlier theorize-stage framing (which would have renamed 20+ unrelated
`--output-dir` flags) down to the actual minimal shape once I traced the exact site the coupling
lives at. Two things are explicitly named as out of scope rather than silently dropped: a
retention-policy question for `eval-results/`, and making the Worker's own runtime directory
itself per-invocation-ephemeral (Java-side, a follow-up candidate).

---

## Implementation plan (2026-07-11, plan-mode adaptation — no plan-mode tool available in this
harness; this section stands in for it, for FOUNDER approval before any code change)

**No implementation has been performed.** This is a plan for a future session to execute after
go-ahead. Ordering matters — later phases depend on earlier ones landing first, since `backend.py`'s
protected-set removal is only safe once `cmd_calibrate` stops writing into `JUSTSEARCH_DATA_DIR`.

### Phase 0 — shared constant (prerequisite for everything else)

- `scripts/jseval/jseval/_paths.py`: add `DEFAULT_JSEVAL_DATA_DIR: Path = _JSEVAL_TMP` above the
  existing `DEFAULT_EVAL_RESULTS` line; change `DEFAULT_EVAL_RESULTS` to
  `DEFAULT_JSEVAL_DATA_DIR / "eval-results"` (same resulting value — verify with a one-line unit
  test asserting the two paths' string equality against today's literal, so this refactor is
  provably a no-op for existing `run`/`bench`/`analysis`/`ui` callers).
- No other file changes in this phase; existing tests must stay green untouched (regression
  guard for the "no-op refactor" claim).

### Phase 1 — Tax 1: gates + release default composition

- `scripts/jseval/jseval/commands/gates.py`: for all 7 `--data-dir` options (lines ~15, 64, 124,
  237, 297, 400, 488 per the investigation grep), add `default=lambda: str(DEFAULT_JSEVAL_DATA_DIR)`
  and drop `required=True`. **Watch item:** `click.Path(exists=True, ...)` validates the *default*
  value too when the option resolves — if `scripts/jseval/tmp/` doesn't exist yet (fresh checkout,
  no run performed), click's generic "path does not exist" error would preempt the existing,
  more informative `"no eval-results run with summary.json"` domain error. Fix: drop `exists=True`
  from these options (the downstream `_latest_run_dir`/`resolve_run_dir` already handle a
  missing/empty directory and produce the correct domain-specific exit-2 message — confirmed via
  `gate.py:64-68`'s `if not eval_results.is_dir(): return None` and `ratchet_kernel.py:132-137`'s
  echo-and-exit-2 on `None`).
- `scripts/jseval/jseval/commands/release.py:23`: replace
  `default=lambda: str(DEFAULT_EVAL_RESULTS.parent)` with
  `default=lambda: str(DEFAULT_JSEVAL_DATA_DIR)` (same value; now sourced from the shared name).
- `scripts/jseval/jseval/commands/calibrate.py`: `cmd_recalibrate_nightly_baseline` (`--data-dir`,
  line 16) and `cmd_calibrate_drift_baseline` (`--data-dir`, line 77) get the same
  `default=lambda: str(DEFAULT_JSEVAL_DATA_DIR)` treatment, `exists=True` dropped for the same
  reason as gates.py (both currently `required=True` with `exists=True`).
- **Tests:** extend `tests/test_gates.py` (or wherever `cmd_gate`/`cmd_relevance_gate` are
  covered) with a case that invokes a gate command with **no** `--data-dir` flag at all, backed
  by a `run` fixture that also used its own default `--output-dir`, asserting the gate finds the
  run without any explicit path (the actual regression this phase fixes — a defaults-only
  compose test, not just a unit test of the constant). Extend `tests/test_release.py` and
  `tests/test_calibrate.py` (or equivalents — confirm exact file names during implementation)
  with the analogous no-flag-needed case.

### Phase 2 — Tax 3: split `cmd_calibrate`'s overloaded `--data-dir`

- `scripts/jseval/jseval/commands/calibrate.py:cmd_calibrate` (currently one `--data-dir`, lines
  141-143): keep the *backend target* resolution as-is (env var / explicit override — this part
  legitimately means "which running instance to sample" and stays Worker-owned), but change the
  envelope-write path threaded into `jseval.calibrate.calibrate(...)` to
  `DEFAULT_JSEVAL_DATA_DIR` by default, independently overridable. Concretely: add a second option
  (name TBD at implementation time — e.g. `--envelope-dir`, defaulting to
  `DEFAULT_JSEVAL_DATA_DIR`) and rewire `jseval/calibrate.py`'s `calibrate()` function signature to
  accept the envelope-write root as a parameter distinct from the backend data dir it starts.
  Read `scripts/jseval/jseval/calibrate.py` (the module, not `commands/calibrate.py`) in full
  before touching it — this investigation read only `commands/calibrate.py`'s CLI surface, not
  the underlying `calibrate()` implementation, so the exact signature change needs verification
  against the real function body first.
- Update the `cmd_calibrate` docstring/help text to describe the split (orphans the "Override
  `JUSTSEARCH_DATA_DIR`" framing named in Design's Orphans list).
- **Tests:** a `test_calibrate.py` case asserting `envelope.json` lands under
  `DEFAULT_JSEVAL_DATA_DIR/cohort_baselines/...` even when the backend's data dir is a different,
  explicitly-overridden path — the actual regression this phase fixes (physical separation, not
  just a renamed flag).

### Phase 3 — Tax 3: retire `backend.py`'s protected-set carve-out

**Sequenced after Phase 2**, since it's only safe once nothing durable is written inside
`JUSTSEARCH_DATA_DIR` by construction.

- `scripts/jseval/jseval/backend.py`: remove the `protected` parameter from `_attempt_wipe` and
  `_clean_data_dir` (lines ~171-205+, read the full functions past the 200-line auto-limit before
  editing — this investigation only saw up to line 205); `start_backend`'s `--clean` branch
  (`backend.py:92-101`) drops the `_protected = {...}` set and its log message's "(preserving...)"
  clause, collapsing to an unconditional-wipe call. The fail-closed verify-and-retry-and-sweep
  contract (711's actual correctness property) is preserved untouched — only the
  protected-vs-wipeable distinction is deleted, per Design's Orphans list.
- **Tests:** `tests/test_backend.py:263-292` (the existing 711 happy-path/failure-path coverage,
  per the tempdoc 711 citation) needs re-reading in full before editing — confirm which cases are
  specifically about the protected-set behavior (delete/simplify those) versus which are about
  the fail-closed verify/sweep/retry contract generally (must keep passing unchanged). Add one
  test asserting a fresh `--clean` wipes the *entire* data dir with no exceptions, replacing any
  test that asserted `cohort_baselines/` specifically survives a wipe (that assertion becomes
  false — and should — under the new contract, since nothing in `JUSTSEARCH_DATA_DIR` is
  protected content anymore).

### Phase 4 — Tax 2: loud fail-closed self-check at CLI entry

- `scripts/jseval/jseval/cli.py`: at the top of `main()` (the Click group entry point every
  console-script/`python -m jseval` invocation passes through), add a check reusing
  `_paths._resolve_repo_root()`'s existing logic: compare `Path(jseval.__file__).resolve()`'s
  own checkout root against the CWD-walk-detected repo root. On mismatch, print the exact
  `PYTHONPATH=<detected-root>/scripts/jseval` remedy and exit non-zero, unless
  `JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1` is set (mirroring `gates.py --allow-engine-mismatch`'s
  existing escape-hatch idiom, tempdoc 644). Implementation detail to settle in-session: whether
  `_resolve_repo_root()` itself should be refactored to expose both candidate roots (module-based
  and CWD-based) as a return value, or whether `cli.py` re-derives `module_root` independently —
  prefer the former (single source of truth for the comparison, avoids two slightly-different
  implementations of the same walk).
- **Tests:** a new `tests/test_cli_entry.py` (or addition to an existing CLI-surface test) that
  monkeypatches `jseval.__file__` to a synthetic "other checkout" path and asserts (a) refusal +
  correct remedy text by default, (b) proceeds normally when
  `JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1` is set, (c) proceeds normally (no false positive)
  when module root and CWD-detected root genuinely agree (the common case — this is the
  regression this phase must not introduce).

### Phase 5 — docs (same PR, per 711's own "docs alignment" precedent)

Mechanical, subagent-suitable once Phases 0-4's actual shape is confirmed by tests (doc text must
match shipped behavior, not the plan's guess of it):
- `docs/reference/jseval-pipeline-reference.md`: Output Structure section, YAML config example
  (`output_dir: tmp/eval-results` stays correct — unchanged value — but the surrounding
  "configured eval-results / cohort_baselines layout" paragraph should name the new
  `DEFAULT_JSEVAL_DATA_DIR` relationship explicitly), the Key Flags `--clean` row (drop the
  "preserves `cohort_baselines/`/`non_determinism_envelopes/`" clause per Phase 3's removal).
- `docs/explanation/08-observability.md`: `<dataDir>/cohort_baselines/...` schema description
  updated to reflect the split from Phase 2.
- `docs/how-to/calibrate-drift-baseline.md`, `docs/how-to/recalibrate-phase3-baseline.md`: any
  `--data-dir` usage examples updated to show the new default (no flag needed in the common case).
- `CLAUDE.md` Common Pitfalls table, PYTHONPATH row: rewritten to point at Phase 4's loud error
  and its remedy instead of prescribing the manual `PYTHONPATH` export as the only path (the
  pitfall becomes self-diagnosing rather than tribal-knowledge-dependent).
- **Validation:** `docsApiDriftCheck`-style spot-check is not applicable here (no API surface),
  but a manual re-read of each changed doc against the actual shipped `--help` output is the
  validation step — do not let doc prose drift from the real defaults, the exact failure mode 711
  found and fixed for `--clean`'s docs.

### Phase 6 — full verification

- `cd scripts/jseval && PYTHONPATH=<worktree>/scripts/jseval PYTHONUTF8=1 python -m pytest
  tests/ -q` — expect the pre-fix baseline (1615 passed, 2 pre-existing `test_correction_probe`
  reds) plus every new test from Phases 0-4, all green. Any new red is this tempdoc's, not
  pre-existing — investigate per `audit-driven-fixes-need-test`, don't wave it through.
- Re-run the exact `docs/observations.md:1933` and `:548` reproduction steps (a `run` at
  defaults immediately followed by a `gate`/`relevance-gate` at defaults with no `--data-dir`;
  a `--start-backend --clean` cycle) against the fixed code — **live-stack verification**, not
  just unit tests, since both originating observations were live-session findings, not unit-test
  failures. This is the `static-green ≠ live-working` discipline: the unit tests prove the logic:
  the live repro proves the actual paper-cut is gone. No GPU/dense-model requirement for either
  repro (a lexical-mode `run --start-backend` suffices for both).
- No UI/frontend surface is touched by this tempdoc (jseval is a Python CLI harness) — the
  `verify` skill's "drive the affected flow" bar is satisfied by the CLI live-repro above, not a
  browser session.

### Subagent-fit assessment

Phases 0-4 are tightly coupled (shared constant → composing defaults → split flag → protected-set
removal → entry-point check) and touch the same handful of files with real ordering dependencies
— **not a good fan-out candidate**; a single implementing session should carry them sequentially
with its own tests, per `delegating-to-subagents`'s "chunk long refactors into bounded
delegations" guidance rather than fanning out entangled work. Phase 5 (docs) is the one
subagent-suitable chunk, and only after Phases 0-4 are code-complete and tested (so the doc-writer
subagent is briefed with the *actual* shipped flag names/defaults, not this plan's projections of
them) — brief it with the specific file:line diffs from Phases 0-4, not just "update the docs,"
per `subagents-no-inheritance`'s requirement for a self-contained task-specific brief.

### Open implementation-time questions (flagged, not resolved here — plan-mode adaptation, no
code was written to test these against)

1. Exact new flag name for Phase 2's split (`--envelope-dir` was a placeholder) — pick during
   implementation once `jseval/calibrate.py`'s real function signature is read in full.
2. Whether `_resolve_repo_root()` should be refactored to return both candidate roots, or Phase 4
   should re-derive the module-root walk independently — prefer the shared-source-of-truth
   version, confirm no other caller of `_resolve_repo_root()` breaks from a signature change.
3. Confirm `tests/test_backend.py`'s exact line range and per-test protected-set dependency
   before deleting/rewriting any of it (this investigation read only through line 205 of
   `backend.py`, not the paired test file in full).

---

## Implementation log (2026-07-11 — all phases landed, worktree `worktree-716-jseval`)

Orchestrator approved the plan with two riders; both implemented. Commits: `00d1573`
(Phases 0-2), `576635a` (Phases 3-4), `5d353e8` (Phase 5 docs), plus this log.

### As-built, per phase

- **Phase 0** — `_paths.py`: `DEFAULT_JSEVAL_DATA_DIR` (= `scripts/jseval/tmp/`) and
  `DEFAULT_BACKEND_DATA_DIR` (= `<repo>/tmp/headless-eval-data`, mirrors `start_backend`'s
  fallback) added; `DEFAULT_EVAL_RESULTS` now derived. No-op pinned by
  `test_paths.py::TestJsevalDataDirConstants` (literal-path equality vs the pre-716 value).
- **Phase 1** — all 8 gate `--data-dir` options (plan said 7; grep found 8 — `gate`,
  `relevance-gate`, `perf-gate`, 2× recall-accounting, 2× datasets-projection, 1× re-produce)
  plus `release` and the two calibrate readers default to `DEFAULT_JSEVAL_DATA_DIR`;
  `required=True`/`exists=True` dropped so the domain exit-2 message wins over click's generic
  path error (pinned by `test_data_dir_composition.py::test_gate_defaults_still_fail_closed...`).
  Defaults-only compose pinned by `...::test_gate_defaults_to_jseval_data_dir_and_finds_run`.
- **Phase 2** — flag-name decision (delegated to implementation): **deviation from the design
  section's letter, conforming to its principle.** The design text said "backend target keeps
  `--data-dir`"; as-built, `cmd_calibrate`'s `--data-dir` now means the jseval-owned envelope
  root — the same meaning as its two sibling calibrate commands and all 8 gates — and the
  backend target moved to the new explicit `--backend-data-dir` (default: env
  `JUSTSEARCH_DATA_DIR`, else `DEFAULT_BACKEND_DATA_DIR`). Rationale: keeping `--data-dir` =
  backend-target would have left `calibrate` the one command in its own file where the flag
  means something different — the exact same-flag-two-meanings disease this tempdoc deletes.
  `calibrate()` signature split into `backend_data_dir`/`envelope_dir`. Operator-visible change:
  a pre-716 `calibrate --data-dir X` invocation now files the envelope under `X` (as before)
  but runs the backend at env/default instead of `X` — pass `--backend-data-dir X` for the old
  isolation behavior (docs updated accordingly).
- **Migration rider (as directed)** — `cohort_baselines.candidate_roots()` (primary first, then
  env `JUSTSEARCH_DATA_DIR`, then `DEFAULT_BACKEND_DATA_DIR`, deduped) + `resolve_envelope_path`
  / `resolve_span_distributions_path` / `warn_legacy_hit`. Wired into `calibrate.read_envelope`
  (preserving the corrupt-file fall-through semantics), `gate._find_envelope` (scan per root),
  `recalibrate-nightly-baseline`, and `encoder_drift._load_baseline`. Old-location-only fixtures
  resolve + WARN — pinned by `test_cohort_baselines.py::TestPre716LegacyRootFallback` (5 tests).
  `conftest.py` gained an autouse fixture isolating the legacy roots so a developer machine's
  real `tmp/headless-eval-data` can't leak into the suite.
- **`run.py` split** — `envelope_data_dir` (manifest embedding) now always the jseval root
  (envelope embedding works at defaults; pre-716 it required an exported env var);
  `worker_data_dir` (telemetry copy into the run dir) stays env-derived, now defaulting to
  `DEFAULT_BACKEND_DATA_DIR` so defaults `--start-backend` runs get telemetry copied too.
- **Phase 3** — `backend.py` `_protected` retired: `_attempt_wipe`/`_clean_data_dir` lose the
  parameter, `--clean` wipes the whole dir; fail-closed verify/sweep/hard-error unchanged.
  `TestStartBackendCleanPreservesCohortBaselines` deliberately FLIPPED to
  `TestStartBackendCleanWipesEverything` (the preservation contract moved to its new home —
  `test_calibrate.py::test_envelope_filed_under_envelope_dir_not_backend_dir`). This exercises
  711 Item 4's own declared retirement condition, not a weakening.
- **Phase 4** — `cli.main()` calls `_assert_matching_checkout()`: refuses (ClickException,
  exit 1) when `module_checkout_root()` ≠ `cwd_checkout_root()`, printing the exact
  `PYTHONPATH=<cwd-root>/scripts/jseval` remedy; escape hatch
  `JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1`. Open question #2 resolved as preferred: the CWD
  walk was extracted from `_resolve_repo_root` into pure helpers (`cwd_checkout_root`,
  `module_checkout_root`) shared by both — no signature change to `_resolve_repo_root` itself.
  Pinned by `test_cli_entry.py` (mismatch/escape-hatch/match/outside-checkout/live-consistency).
  Note: click does not invoke the group callback for a bare `--help`, so `jseval --help`
  bypasses the check — harmless (help executes no stale logic).
- **Phase 5 + docs rider** — `search-quality-register.md` §Cross-run-noise envelope path
  updated + `node scripts/docs/skills-sync.mjs` re-run (regenerated `jseval` and
  `search-quality` SKILL.md); `jseval-pipeline-reference.md` (observability root + calibrate
  split + `--clean` rows + Output Structure), `08-observability.md` (envelope workflow),
  both how-tos (procedures at the new defaults), CLAUDE.md pitfall row (trap now
  self-diagnosing). `prose-tier-register` gate: pass. `common-workflows.md`'s `--clean` line
  verified already-accurate (no protected-set claim).
- **Phase 6** — full suite after every phase; final: **1634 passed, 2 failed** — both the
  pre-registered `test_correction_probe` reds (`expected-state.v1.json`), +19 net-new tests over
  the 1615 pre-implementation baseline.

### Deviations / deferrals (named, not silent)

1. **Calibrate flag-meaning swap** (above) — deviation from the design section's letter, with
   rationale; operator-visible behavior change documented in the how-to + `--help`.
2. **`encoder_drift._resolve_data_dir`'s `run_dir.parent` last-ditch fallback retired** — it
   pointed at `<eval-results>/cohort_baselines/`, a layout nothing ever wrote; replaced by the
   jseval root + legacy-root fallback chain.
3. **Live defaults-compose repro deferred to publish-time** — the plan's Phase-6 live check (a
   defaults `run --start-backend` + defaults gate against the real repro from
   `observations.md:1933`/`:548`) needs a Gradle-built backend in this worktree; the approved
   verification bar for this pass was the full suite. The CLI-level compose test
   (`test_data_dir_composition.py`) covers the wiring; the live repro should ride the
   pre-publish verification pass (`static-green ≠ live-working` applies — flagged, not skipped
   silently).
4. **Pre-existing dead code logged, not fixed**: `encoder_drift._write_baseline` has zero call
   sites — observation shard entry filed.

### Orphan check (design §Orphans — all delivered in this same change)

`_protected` set + threading: gone. `release.py` ad hoc `.parent` default: replaced by the
shared constant. `cmd_calibrate` "Override JUSTSEARCH_DATA_DIR" help: rewritten. All five named
doc surfaces updated; CLAUDE.md row rewritten.

### Live pre-publish check (2026-07-11, GPU slot assigned — closes deferral #3)

All commands from this worktree with `PYTHONPATH=<worktree>/scripts/jseval PYTHONUTF8=1`;
worktree made runnable first (`npm ci` + `gradlew :modules:ui:installDist
:modules:indexer-worker:installDist`, BUILD SUCCESSFUL).

1. **Corpus substitution (named deviation):** the directive's `battlefield-en-v1` has no
   committed source under `scripts/jseval/635-corpora/` (only `battlefield-en-scale-v1`,
   2736 docs × 2500 words — the 691 throughput corpus, too heavy for a serial GPU slot).
   Used the committed small corpus instead: `jseval corpus-build --source
   635-corpora/needle-burial-v1 --name needle-burial-v1` → 280 docs / 20 queries. This
   invocation is itself live proof of the Phase-4 check's no-false-positive path (matching
   checkout → no refusal).
2. **Defaults run:** `python -m jseval run --dataset golden/needle-burial-v1 --modes hybrid
   --start-backend --clean --pipeline` with **no `--output-dir`** — exit 0; backend healthy in
   8s; models auto-resolved from the main checkout (644); artifacts at
   `scripts/jseval/tmp/eval-results/20260711T163008_golden_needle-burial-v1/` (summary.json,
   manifest.json, projections/, telemetry NDJSON — the run.py worker-data-dir default landed
   the telemetry copy at defaults, as designed). Bonus live evidence: `stop_backend`'s
   orphan sweep fired for real — `WARNING jseval.backend: Killing orphan Worker PID=37784` —
   the 711 sweep catching an actual surviving Worker in this very run.
3. **Gate compose (the Tax-1 repro, observations.md:1933):** `python -m jseval relevance-gate
   --dataset golden/needle-burial-v1 --report-out tmp/716-live-gate-report.json` with **no
   `--data-dir`/`--run-dir`** — exit 0; report `run_dir` =
   `...\scripts\jseval\tmp\eval-results\20260711T163008_golden_needle-burial-v1` (run
   DISCOVERED via the new default), domain outcome `baseline-pinned: skip` (un-pinned golden
   dataset). The pre-716 failure mode `"no eval-results run with summary.json"` did not occur.
4. **Legacy-fallback WARN (migration rider, live):** planted an old-layout envelope at
   `tmp/headless-eval-data/cohort_baselines/716-live-fixture/envelope.json`, then
   `python -m jseval recalibrate-nightly-baseline --cohort-hash 716-live-fixture` with **no
   `--data-dir`** — the deprecation WARN fired naming the exact legacy path, the value
   resolved (`PHASE3_BASELINE_NDCG10_STDEV=0.00123`), exit 0. Fixture removed after.
5. **Shutdown verified:** port 33221 free, zero `java.exe` with `headless-eval-data` on the
   command line, `nvidia-smi` compute list back to desktop baseline (no java/llama/gradle).

Result: the run→gate defaults compose end-to-end live, the migration fallback works against a
real old-layout fixture, and 711's fail-closed sweep demonstrated live during teardown.
Deferral #3 is closed; no code changes were needed as a result of the live check.
