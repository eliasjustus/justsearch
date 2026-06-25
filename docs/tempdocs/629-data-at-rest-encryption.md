---
title: "Data-at-rest protection as a projection of store-recoverability: classify each store DERIVED (rebuildable projection of the user's files → OS full-disk encryption) vs AUTHORED (authority of record → optional passphrase-gated app-level AEAD); conform to 628's reconstruct-from-source invariant, sense FDE via the 587 host-capability seam, and surface an honest at-rest status through the ConditionStore→CAUSE_ROWS legibility seam — never app-encrypt the mmap'd index. Long-term shape (2026-06-22): realize the class as the one load-bearing authority that a factor-agnostic key envelope (passphrase / recovery / WebAuthn-PRF) and — when a second consumer lands — a governed store-register project across confidentiality, backup, and protect-on-delete. Reach (2026-06-23): the same class drives the whole data lifecycle under one invariant — each store's recoverability class determines its lifecycle *handle* (source-of-truth for DERIVED per 628, encryption key for AUTHORED), so every obligation (recover / delete / retain / back up) operates on the handle, not the bytes; erasure is crypto-shred (destroy the key), and a master-KEK→per-record-DEK key hierarchy is the substrate built only when the first granular lifecycle obligation lands"
type: tempdoc
status: closed
created: 2026-06-22
updated: 2026-06-23
closed: 2026-06-23
related:
  - 628-index-durability-corruption-recovery  # rebuild-from-source makes the index a recoverable asset; conversations are not
  - 626-incremental-indexing-correctness       # reconciler/watcher touch the same index dir an encrypting Directory would wrap
  - 297-diagnostics-export-redaction           # adjacent confidentiality concern (exports vs at-rest)
  - 630-os-sleep-resume-robustness             # sibling gap-analysis idea; orthogonal
---

> NOTE: Noncanonical working tempdoc. **Investigated + designed 2026-06-22.**
> Read in this order for current truth: **`# Design (2026-06-22)`** (the
> long-term backend design) → **`# Research refresh`** (current-threat validation
> + caveats) → **`# Frontend / user-facing design`** (the user-facing layer,
> grounded in a live UI inspection) → **`# Investigation (2026-06-22)`** (verdicts
> against `main`, mechanism/prior-art research, the critical reframe). The charter
> below (§The gap … §Next step) is the original idea-stage record, **superseded by
> the Design section** — the index is the wrong target for app-level crypto, and
> passphrase-free OS-keystore custody adds little over BitLocker. **No
> implementation yet**; the design is general, not implementation-level, and its
> expensive half (incl. the user-facing control/unlock layer) is gated on a
> threat-model decision (§Design Q1).

# 629 — Data-at-rest encryption

## The gap

JustSearch is a local-first neural search engine that indexes the user's
personal files. By construction it extracts and stores the **full text** of
everything it indexes. Today that data sits on disk as plaintext:

- **Lucene index** — the index directory holds extracted document text,
  postings, and stored fields in the clear.
- **Job queue** — `SqliteJobQueue` opens a plain `DriverManager` JDBC
  connection; no SQLCipher / `PRAGMA key`. Queue rows can carry paths and
  extracted content.
- **Conversation store** — `FileConversationStore` persists chat / RAG
  transcripts (which quote indexed content) as plaintext files.

Survey finding (unverified by author): no `Cipher` / `KeyStore` / AES /
SQLCipher usage anywhere under `modules/` — the only matches are in lockfiles,
generated protobuf JS, and corpus filenames.

**Why it matters.** On a lost or stolen laptop, or a shared / multi-user
machine, the index *is* a readable, searchable corpus of everything the user
pointed JustSearch at — often the most sensitive data on the device. For a
product whose entire pitch is "private, local-first," plaintext-at-rest is a
positioning liability as much as a security one.

## Why this isn't already covered

- **628 (index durability / corruption recovery)** owns detect → recover →
  rebuild-from-source; it does not touch confidentiality of the data at rest.
- **297 (diagnostics export path redaction)** redacts *paths in exported
  diagnostics*, not the index contents on disk.
- **Loopback API auth** (`ApiSecurityFilters`) defends the network surface, not
  the disk surface.

No existing tempdoc addresses encryption-at-rest.

## The idea (design questions, not yet decisions)

This is a clean-slate design problem. The tempdoc should resolve:

1. **Scope** — which stores get encrypted? (index dir, SQLite job queue,
   conversation store, logs, telemetry, model cache). The index is the high
   value target; models are public artifacts and probably out of scope.
2. **Key custody** — the core fork:
   - **Transparent OS-bound** (Windows DPAPI / a machine-scoped key): protects
     against disk theft + other-user-on-same-box, *zero* user friction, but the
     key lives on the same machine — no protection against an attacker with the
     running OS account.
   - **User passphrase** (derived KEK, e.g. Argon2id → AES-GCM): real
     confidentiality even against the OS account, at the cost of an unlock step
     and an unrecoverable-on-forgotten-passphrase failure mode.
   - **Optional / tiered**: default to DPAPI-transparent, offer passphrase as an
     opt-in "high security" mode.
3. **Mechanism per store**:
   - SQLite → SQLCipher (or app-level field encryption).
   - Lucene → filesystem-level (encrypt the index directory via an encrypting
     `Directory` wrapper / OS filesystem encryption) vs. app-level. Lucene has
     no built-in at-rest encryption; an encrypting `Directory` is the usual
     route but interacts with mmap (`MMapDirectory`) and the Windows unmap
     workaround (see 378 / 626).
   - Conversation store → straightforward app-level AES-GCM per file.
4. **Interaction with rebuild-from-source (628/626)** — encryption must not
   break the corruption → rebuild path; the rebuild re-extracts from the
   original files (still plaintext on the user's disk, outside our control), so
   encryption protects *our* derived stores, not the source.
5. **Performance** — encryption overhead on the indexing write path and on
   search read latency; mmap compatibility is the sharp edge.
6. **Threat model boundary** — be explicit about what this does and does NOT
   defend (it does not defend against malware running as the user while the app
   is unlocked; it does defend against offline disk access).

## Severity / horizon

Privacy differentiator and a credible-ask for the "local-first / private"
positioning. Medium implementation effort. Not a hard pre-alpha blocker, but
shipping a public alpha that stores a plaintext index of personal files is a
reputational risk worth a deliberate decision (even if the decision is
"documented plaintext for v1, encryption in v1.x").

## Related

- [[628-index-durability-corruption-recovery]] — rebuild-from-source must
  survive whatever encryption scheme lands.
- [[626-incremental-indexing-correctness]] — reconciler / watcher touch the same
  index directory an encrypting `Directory` would wrap.
- [[297-diagnostics-export-redaction]] — adjacent confidentiality concern
  (exports vs. at-rest).
- [[630-os-sleep-resume-robustness]] — sibling idea from the same gap analysis.

## Next step

Investigation pass: confirm the absence claims against `main`, inventory every
on-disk store and what it actually persists, and produce the key-custody fork as
a user decision before any design.

---

# Investigation (2026-06-22)

> Done per assignment: understanding + critique, not a plan. **No design or
> implementation yet.** Provenance tags: **[VERIFIED `file:line`]** = source was
> read and quoted (by the codebase-audit subagent, which cited primary-source
> `file:line`); the **three tiering-critical claims** — MMapDirectory default
> (`ComponentsFactory.java:105`), persistent-conversation RAG-content embedding
> (`ConversationEngine.java:292-301`), and the unmap hack
> (`WorkerSpawner.java:524`) — were **re-read by the tempdoc author this pass**.
> **[SURVEY]** = grep-survey only (line numbers are a starting point).
> **[RESEARCH `url`]** = external source. The charter above is the original
> idea-stage record; this section is what holds against `main` on 2026-06-22,
> and it materially reframes the idea.

## A. The absence claims — verdicts

All three hold; the codebase has **no data-at-rest encryption anywhere**.

1. **No crypto for persistence — CONFIRMED.** [VERIFIED] The only `javax.crypto`
   use in production Java is an **in-memory HMAC** for consent tokens
   (`ConsentCapsuleService.java:13-14,56`), whose own javadoc says the key is
   "regenerated per process, so capsules do not survive a restart" — nothing
   written to disk. No `Cipher`/`KeyStore`/`SQLCipher`/`PRAGMA key`/`Argon2`/
   `DPAPI`/`ProtectedData` in `modules/**` or the Rust shell. (`jackcess-encrypt`
   in lockfiles is a transitive *Tika* dep for reading password-protected Office
   files — not app crypto. SHA-256 is used for IDs only.)
2. **SQLite job queue is plaintext — CONFIRMED.** [VERIFIED]
   `SqliteJobQueue.java:159-160` opens `jdbc:sqlite:<dataDir>/jobs.db` via plain
   `DriverManager`; PRAGMAs (`:164-169`) set WAL/synchronous/auto_vacuum — **no
   `PRAGMA key`**. WAL means `jobs.db-wal`/`-shm` sidecars + a `jobs.db.bak`
   (`KnowledgeServer.java:379,2029`) are also plaintext.
3. **Conversation store is plaintext — CONFIRMED.** [VERIFIED]
   `FileConversationStore` writes append-only JSONL via `Files.writeString(…
   APPEND)` (`:140-145`) under `<dataDir>/conversations/<sessionId>/`.

## B. What's actually on disk — corrected store inventory + sensitivity

The original charter named three stores. The real surface is larger, and the
**sensitivity ordering is the load-bearing correction**:

| Store | Path (under `<dataDir>` = `%LOCALAPPDATA%\JustSearch`) | What it leaks | Rebuildable? |
|---|---|---|---|
| **Lucene index** | `index/<collection>` | **The bulk: extracted document TEXT + vectors** (stored fields + term dictionary) | **YES** — 628 rebuilds from source files |
| **Job queue DB** | `jobs.db` (+`-wal`,`-shm`,`.bak`) | **Raw absolute file paths** (`jobs.path`, `path_resolution.normalized_path` — javadoc: "the only persistent place where raw paths are stored"). NOT document bodies. | YES — re-enumerable from roots |
| **Conversations** | `conversations/<id>/messages.jsonl` | Chat/RAG transcripts. **For *persistent* sessions, the RAG-augmented user turn embeds retrieved document text** (`ConversationEngine.java:292-295` + `RAGContext.java:225`); assistant turns carry `citations` with quoted snippets. *Ephemeral* RAG-ask records only the clean question (`:296-301`). | **NO** — user-authored history, not derivable |
| Logs / crash dumps | `logs/`, `crashes/` | Crash dumps especially can hold in-memory document content | n/a |
| Telemetry | rooted at `dataDir` | metrics/traces (may carry paths) | n/a |
| Agent runs / history / memories | `agent-runs`, `agent-history`, `memories` | agent transcripts (same content-quoting risk as conversations) | mostly NO |

[VERIFIED] paths from `PlatformPaths.resolveDataDir():66,134-144`,
`ResolvedConfigBuilder:878`, `ConversationApiAssembly:199`, `HeadAssembly:402,484,545`.
Note a casing split: bulk data in `%LOCALAPPDATA%\JustSearch`; **UI settings live
separately** in `%APPDATA%\Roaming\justsearch\ui\settings.json`
(`UiSettingsStore:82-89`) — the natural home for key-derivation params.

**Two facts that drive the whole design:**
- **The index holds the most content, by far** — so any "we protect your
  documents" claim is *false* unless the index is covered. Encrypting
  conversations + paths while leaving the index plaintext protects *history and
  the file list*, **not document content**.
- **Rebuildability splits cleanly the right way.** The index (bulk,
  perf-sensitive) is *recoverable* via 628's rebuild-from-source; conversations
  (small, sensitive) are *not*. This is the key the tiering hangs on (§F).

## C. Mechanism findings (feasibility)

- **Runtime:** [VERIFIED] JDK **25** (`libs.versions.toml:2`), Lucene **10.4.0**
  (`:9`), `org.xerial:sqlite-jdbc` **3.51.2.0** (`:33` — plain, unencrypted).
- **Lucene + mmap is the crux.** [VERIFIED] index opens with **`MMapDirectory`
  by default** (`ComponentsFactory.java:100-106`); configurable to NIOFS but
  defaults to mmap. (Charter cross-check: a Windows mmap workaround **does**
  exist, but [VERIFIED] it lives at `WorkerSpawner.java:524` — the worker JVM is
  launched with `--add-opens=java.base/java.nio=ALL-UNNAMED` to "Enable
  MMapDirectory unmap hack — prevents 'pending deleted files' errors on Windows".
  That's about *clean file deletion* of mmap'd segments, **not** an encryption
  interception point; it doesn't change the conclusion below. It is **not** in
  `adapters-lucene` as the charter's "(see 378/626)" implied.)
  - [RESEARCH] There is **no maintained, mmap-compatible encrypting
    `Directory`.** `MMapDirectory` maps raw bytes into the address space and
    reads are plain memory loads — **there is no call site to intercept for
    decryption**, and the OS page cache would hold ciphertext. App-level index
    crypto therefore **forces a fall-off mmap** to buffered/positional reads
    (`NIOFSDirectory`-style). Apache's own `solr-sandbox` encryption module
    measures **"−20% on most queries, −60% on multi-term queries"** and warns
    "if you can use OS-level encryption, prefer it." (LUCENE-9379 / -6966 never
    merged.) OS/volume encryption (BitLocker/dm-crypt) sits *below* the page
    cache, so the cache holds plaintext and **mmap stays full-speed** — this is
    why Elasticsearch's "encryption at rest" is disk-level, not Lucene-level.
    https://github.com/apache/solr-sandbox/blob/main/ENCRYPTION.md ·
    https://issues.apache.org/jira/browse/LUCENE-9379 ·
    https://www.elastic.co/docs/deploy-manage/security/data-security
- **SQLite from xerial:** [RESEARCH] xerial bundles plain SQLite ("does not
  support encryption out of the box"). Free whole-DB option = **`Willena/
  sqlite-jdbc-crypt`** (tracks xerial, latest 3.51.2.0, Apache-2.0/BSD,
  SQLCipher-compatible ciphers). SQLCipher's *Community* edition is closed-source
  friendly **but has no free JDBC path** (official JDBC is paid); SEE is paid. For
  a queue that treats paths as *payload* (insert / pop-by-priority / delete, no
  `WHERE path LIKE`), **app-level AES-GCM column encryption on the stock driver**
  is simplest (no native dep, no licensing, survives driver upgrades).
  https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md ·
  https://github.com/Willena/sqlite-jdbc-crypt
- **Crypto primitives on Java 25:** [RESEARCH] AES-256-GCM and IETF
  ChaCha20-Poly1305 (96-bit nonce) are **built in**. **Argon2id is NOT in the
  JDK** → Bouncy Castle `Argon2BytesGenerator` (pure Java). XChaCha20 (192-bit
  nonce) is in neither → would need a libsodium native binding. So the pure-Java
  path is **BC Argon2id (KEK) + JDK AES-256-GCM (DEK)**, envelope-wrapped, params
  in the file header. OWASP/RFC 9106 Argon2id tuned to ~250–500 ms on min
  hardware. https://datatracker.ietf.org/doc/rfc9106/
- **Key custody:** [RESEARCH] cross-platform without three Java native bindings
  → let the **Tauri/Rust shell own custody** (the `keyring` crate: DPAPI /
  Keychain / Secret Service) and hand the unwrapped DEK to Java over the existing
  loopback IPC at startup. `tauri-plugin-stronghold` is **deprecated**. Linux
  headless often has *no* Secret Service and silently falls back to plaintext →
  needs an explicit "no secure store → require passphrase, never silent
  plaintext" branch.

## D. The critical pivot — does app-level encryption even beat BitLocker?

This is the question the original charter under-weighted, and it reframes
everything. [RESEARCH, security-delta analysis]

- **FDE (BitLocker/FileVault/LUKS) protects** the *powered-off / stolen disk* —
  the canonical "thief steals the laptop" threat — fully and for free.
- **FDE does NOT protect** a *running, unlocked* machine, *malware running as the
  user*, or (with Windows TPM auto-unlock and no pre-boot PIN) a boot-environment
  attacker (2025–26 WinRE "BitUnlocker" bypasses).
  https://techcommunity.microsoft.com/blog/microsoft-security-blog/bitunlocker-leveraging-windows-recovery-to-extract-bitlocker-secrets/4442806
- **The honest delta:**
  - **Passphrase-FREE app crypto keyed from the OS keystore (DPAPI/Keychain):**
    unlocks automatically because the user is logged in → **any code running as
    that user just asks the OS to decrypt.** It defends the *same* cases FDE
    already defends (powered-off disk, other user) and falls to the *same*
    adversary (malware-as-user). On a single-user BitLocker'd Windows box this is
    **two locks keyed to the same login secret — largely security theater.** Its
    only honest wins: protection when FDE is *off/misconfigured* (real on
    consumer Windows Home), and per-user separation on a shared *unencrypted*
    volume.
  - **Passphrase-GATED app crypto with lock-on-idle:** the **only** configuration
    that defends a threat FDE structurally cannot — the *running-but-locked /
    unattended* machine and *malware-as-user-while-the-app-is-locked*. This is
    where app-level encryption earns its complexity, at the cost of an unlock
    step and forgotten-passphrase = data loss.

**Consequence for the charter's "key-custody fork."** The fork was framed as
"DPAPI-transparent (default) vs passphrase (opt-in)." The research says the
DPAPI-transparent tier is **near-worthless as a security control over FDE** — its
real role is *convenience defense-in-depth for users without FDE*, and it must
**not** be marketed as strong encryption. The genuine security choice is binary:
**recommend/verify FDE for the bulk index, and offer passphrase-gated encryption
for the non-rebuildable sensitive stores** — or do neither.

There's a **positioning trap** here too: a "private, local-first" product that
ships passphrase-free DPAPI encryption and *calls it* protection invites a "this
is theater" critique under scrutiny. Honesty (recommend BitLocker; offer a real
passphrase mode) is the more defensible posture than a weak default dressed up.

## E. Prior-art norm

[RESEARCH] The de-facto norm for a local search *index* is **do not app-encrypt
it; restrict with filesystem permissions and push at-rest to FDE**:
**Recoll** (index ≈ full doc copies, no encryption, relies on `0700` + user
choice), **Everything/voidtools** (recommends storing the DB on a BitLocker
volume), **Windows Search** `Windows.edb` (FDE), **Spotlight** (FileVault),
**DocFetcher** (Lucene, punts to volume), **Obsidian/Logseq** (OS encryption).
Where these apps *do* ship crypto it's **E2E for the sync channel** (Joplin) — a
different threat (untrusted server), and *even Joplin's local SQLite is
plaintext*. So the bulk-index-via-FDE choice is the industry default, not a
cop-out. https://www.recoll.org/usermanual/webhelp/docs/RCL.INDEXING.STORAGE.SECURITY.html ·
https://www.voidtools.com/forum/viewtopic.php?t=15313 ·
https://github.com/laurent22/joplin/issues/13573

## F. Reframed design space (NOT a decision — input to the next pass)

The evidence points to a **tiered** shape rather than the charter's "encrypt the
stores, pick a key-custody mode":

1. **Bulk index → OS/volume encryption (FDE).** Detect BitLocker status, surface
   it in the UI, and *recommend/guide* enabling it (FileVault/LUKS elsewhere). Do
   **not** app-encrypt the Lucene index (no mmap-compatible option; 20–60% query
   tax; against the universal norm). The index being *rebuildable* (628) means
   even total key loss is non-catastrophic for it.
2. **Small NON-rebuildable sensitive stores → optional app-level AES-256-GCM,
   passphrase-gated, lock-on-idle.** Primary target = **conversations / agent
   history / memories** (not rebuildable, content-quoting, small enough that the
   buffered-I/O penalty is irrelevant). The **job-queue paths** can ride the same
   key (app-level column encryption, stock driver). Envelope: random DEK wrapped
   by an Argon2id KEK; optional recovery key; loud forgotten-passphrase warning.
3. **Passphrase-free OS-keystore custody → offered only as a clearly-labeled
   *weaker convenience tier*** for users who decline the passphrase or have no
   FDE — never described as strong protection.
4. **Honesty constraint (coherence):** because the index carries the document
   *content*, the product must not claim "your documents are encrypted" on the
   strength of (2) alone. The accurate claim is "your **chat history and the list
   of files you indexed** are encryptable with a passphrase; your **document
   index** is protected by your OS disk encryption." If true content
   confidentiality against the running-machine threat is wanted, that requires
   passphrase-gating the **index** too (eating the mmap/perf cost) — a distinct,
   heavier option to put to the user, not assume.

This reframe also tidies the durability interaction: forgotten-passphrase loses
*conversations* (acceptable, user-authored, warned) but never the *index*
(rebuildable) — far better than a scheme where a lost key bricks the whole index.

## G. Open questions / forks for the next pass (threat-model first)

Before any design, the user should settle:

- **Q1 — Threat model.** Which threat are we actually buying down? (a) stolen
  powered-off laptop [→ FDE suffices]; (b) running-machine / malware-as-user [→
  needs passphrase-gating]; (c) other local user [→ already partly covered by
  per-user `%LOCALAPPDATA%` NTFS ACLs — **verify** the default ACL]; (d) the
  *marketing* expectation set by "private local-first". The answer selects
  everything downstream.
- **Q2 — Is the index in or out of app-level scope?** Out (FDE-only, the norm,
  no perf hit) vs in (passphrase-gated, ~20–60% query cost, true content
  confidentiality vs the running machine). Likely the single biggest fork.
- **Q3 — Passphrase or passphrase-free as the *real* tier?** Given §D, is the
  passphrase-free tier worth building at all, or does it only invite a "theater"
  critique?
- **Q4 — Custody home:** Tauri `keyring` shell-owned DEK over loopback IPC vs a
  Java-side FFM/JNA DPAPI binding. (Shell-owned = one cross-platform path.)
- **Q5 — Scope of the "small stores":** just conversations, or also agent
  history / memories / crash dumps / telemetry? (Crash dumps can hold document
  content — easy to overlook.)
- **Q6 — Recovery vs durability:** confirm forgotten-passphrase only ever costs
  non-rebuildable data; spec the optional recovery-key envelope.

**Not opened as separate work:** the broader plaintext surfaces noted in §B
(logs/crashes/telemetry) are in-scope for *this* tempdoc's Q5, not a new doc.

---

# Implementation — FLOOR (as-built, 2026-06-22)

> User decisions: **FLOOR now, LAYER deferred**; **coarse FDE on/off** fidelity.
> Shipped the honest at-rest status end-to-end, no app-level crypto. Plan:
> `.claude/plans/scalable-noodling-neumann.md`. **Live-UI-validated** (below).

**What shipped (all mirror existing patterns):**
- **FDE probe** — `modules/app-services/.../atrest/DiskEncryptionProbe.java` +
  `AtRestProtection.java`: reads the non-elevated Explorer shell property
  `System.Volume.BitLockerProtection` for the data-dir drive via a cached (5s),
  never-throwing PowerShell subprocess → coarse `{ENCRYPTED, NOT_ENCRYPTED,
  ENCRYPTING, UNKNOWN}` + source + confidence. No OS gate (app-services may not
  read `System.getProperty` — caught by `AppServicesWorkerGuardrailsTest`); on
  non-Windows the subprocess simply fails → UNKNOWN.
- **Wire** — `AtRestProtectionView` (app-api) + `StatusResponse.atRestProtection`
  + `status.proto` field 31 + message + `StatusLifecycleHandler.buildAtRestProtection()`
  (mirrors `buildGpuStatus`). Regenerated JSON schema + FE TS/Zod + fixture.
- **Condition** — `AtRestHealthTap` (mirrors `IndexDriftHealthTap`): asserts the
  `at-rest.unprotected` ConditionStore condition iff `NOT_ENCRYPTED`, clears on
  ENCRYPTED/ENCRYPTING/UNKNOWN; wired in `CoreApiAssembly` sharing one probe with
  the View. Verdict-independent (confidentiality ≠ retrieval degradation, P3).
- **FE card** — `HealthSurface.renderAtRest()` "Data protection" card (mirrors
  `renderGpu`): disk-encryption state + `source · confidence`, `Configuration:
  Unknown — needs admin` (coarse fidelity, P1), per-store rows (Index /
  Conversations), and the honest BitLocker-with-PIN guidance + the 24H2
  cloud-escrow disclosure when not encrypted.

**Tests:** `AtRestHealthTapTest`, `DiskEncryptionProbeTest` (cache + never-throws),
`StatusRecordSchemaTest` updated. Backend compiles + tests green; FE typecheck +
242 unit tests green; `class-size` + `wire` gates green; 5/6 ui-web gates green.

**LIVE validation (user requirement met):** dev stack up, `/api/status` emits
`atRestProtection:{diskEncryption:"NOT_ENCRYPTED",source:"shell-property",
confidence:"MEDIUM",qualityKnown:false}` (this box's data-dir is on the
unencrypted F: drive). The System-Health "Data protection" card renders the
amber **NOT ENCRYPTED** pill, the coarse status, `Configuration: Unknown — needs
admin`, both per-store rows as "Not encrypted", and the guidance copy — confirmed
by browser screenshot + shadow-DOM text read.

**Deliberately deferred (not bugs):**
- **The ambient fact-chip** (`facts.ts` `core.at-rest.protection`) — optional per
  §UF.5 (the diagnostic card is the primary surface); skipped this pass.
- **The entire LAYER** — passphrase/Hello encryption of AUTHORED stores, key
  custody, unlock gate (gated on the Q1 threat decision).

## Follow-up (2026-06-22): condition-surfacing verification (post-impl gap close)

The post-impl critical analysis flagged that I'd verified the *card* but not that
the `at-rest.unprotected` **condition** surfaces as a proper health condition.
Verdict after live investigation:

- **RESOLVED at the substantive layer.** The condition IS a first-class
  `ConditionStore` condition, broadcast via the standard `/api/health/events/stream`
  — **directly verified live**: the snapshot frame carries 5 conditions incl.
  `{"id":"at-rest.unprotected", severity:"WARNING", message:"Your data folder is on
  a drive that isn't encrypted…"}`, alongside `ai.not-ready` / `embedding.blocked` /
  etc. This is exactly the §P3 intent (a condition on the legibility seam, not
  card-local), and it would render via the generic `healthEventActivityRowStrategy`
  (no bespoke renderer needed).
- **Pre-existing, out-of-scope blocker on the visible row.** `HealthSurface.events`
  is empty for **all 5 conditions** (the 4 pre-existing ones equally) in the dev
  env — its persistent SSE subscription isn't populating `this.events`, while a
  fresh same-origin fetch of the same stream returns the snapshot fine. So this is
  a **pre-existing, all-conditions HealthSurface SSE-population issue (likely the
  session-long dev-stack reconnect/stale-port flakiness), NOT a 629 regression and
  NOT at-rest-specific** — logged to the inbox, not fixed here (scope creep into
  unrelated FE infra). `recovery` is optional in the wire schema, so the at-rest
  event (no recovery) validates cleanly — ruled out as the cause.
- **Net:** the warning is a proper health condition (verified) AND prominently
  visible via the card. Full row-rendering in "Recent events" is gated on the
  pre-existing HealthSurface events population, which is environmental.
- **Ambient chip:** skipped (optional per §UF.5; the card is the primary surface).

**Pre-existing, NOT introduced by 629 (logged):**
- `check-theme-token-closure` fails on `RecentsMenu.ts` (`--space-1`,
  `--surface-raised`, `--text`, `--z-overlay-menu`) — a different file; the
  at-rest card's tokens are all defined.
- `class-size` HeadlessApp +10 inherited drift (re-pinned via the realign
  changeset, mirroring `626-followup-post-merge-class-size-realign.md`).

---

# Design (2026-06-22)

> The long-term design, kept general (seams/authorities/projections, not
> code). It is the result of mapping four existing seams (see the four agent
> findings folded in below) and deciding, per the assignment, to **conform**
> rather than build parallel structure. `file:line` anchors are in the
> Investigation section; this section names the *shape*.
>
> **Post-research caveats (`# Research refresh` §R3) refine four points below** —
> the FDE recommendation now requires a *pre-boot secret* + *cloud-escrow honesty*
> (R3.1), the honest status must carry *configuration quality* not just on/off
> (R3.2), the non-replayable factor may be *Windows Hello / Touch ID* not only a
> passphrase (R3.3), and the envelope has a NIST-800-38D watch-item (R3.4). The
> design's spine and tier decisions are unchanged and strengthened.

## 0. The one idea the design turns on

**At-rest protection is not a per-store setting; it is a _projection_ of a
store's recoverability class.** Every store under `dataDir` is one of two kinds:

- **DERIVED** — a projection of an external source of truth (the user's files on
  disk). The Lucene index, the SQLite job queue, `path_resolution`, sidecar
  caches. *Rebuildable*; its confidentiality ceiling is already the source files
  themselves (plaintext, on the same disk, outside our control).
- **AUTHORED** — an authority of record with **no** upstream source. Conversation
  transcripts, agent memories/history. *Not rebuildable*; deletion is permanent
  loss.

From that one class, three downstream policies fall out **as projections, not
independent decisions**: (a) which at-rest control applies (FDE vs app-level
crypto), (b) the key-loss policy (rebuild vs terminal loss), (c) the honest
status we can show the user. This is the same projection-vs-authority discipline
the codebase already runs everywhere (one canonical authority, many derived
views) — applied to the storage layer.

**This is not a new principle.** Tempdoc 628 already named exactly this invariant
— *"reconstruct from source-of-truth"* (628 §"Reveals a candidate invariant",
lines 379-389) — listed the index / `SqlitePathResolutionStore` /
`Sha256SidecarCache` as candidate members, and **deliberately recorded it without
building it** ("the capture, not a license to build the generalized recovery
framework now"). 629 is the **second consumer** of that invariant. The disciplined
move (and the whole shape of this design) is: make the classification first-class
*only as much as 629 needs*, conform every other piece to a seam that already
exists, and record the now-twice-seen principle (§Reach) without building the
N-consumer framework.

## 1. Architecture: a correct FLOOR + a decision-gated LAYER

The design splits along what is *unconditionally correct* vs *gated on a
product/threat decision*. This is a structural property, not a delivery plan.

### The FLOOR — always correct, conforms entirely to existing seams, **no new crypto**

The floor makes the product *honest* about at-rest protection without encrypting
anything itself. It is correct regardless of how Q1 is answered.

1. **Store-recoverability classification (the spine — the only genuinely new
   structure).** Declare DERIVED vs AUTHORED for the stores 629 touches. Minimal:
   a class tag on each relevant store, not a full `dataDir` registry with closure
   gates (that is the deferred generalization — §Reach). 628's recovery code today
   *hardcodes this same distinction at three call-sites* (index → rebuild-from-source
   marker; jobs.db → restore-from-backup; else throw) without ever naming it — so
   the spine also retires a latent triple-fork, but only if/when 628's sites are
   migrated to read it (not required by 629; recorded as a violation in §Reach).

2. **FDE sensing — conform to the 587 host-capability seam.** "Is the volume
   OS-encrypted (BitLocker / FileVault / LUKS)?" is a *host capability sensed with
   confidence* — structurally identical to 587's shipped `Cuda(Boolean functional,
   source, Confidence)` single-probe axis. Mirror that pattern: a best-effort,
   never-throws probe → `(on: on|off|unknown, source, Confidence)` effective view,
   one resolver in its own seam. **Mirror, do not generalize** — 587 shipped
   one-resolver-per-capability and explicitly judged a generic host-capability
   framework YAGNI when GPU was the sole multi-probe capability. FDE is the second
   genuine capability and is therefore the *recorded trigger* for that
   generalization (§Reach), but 629 builds one resolver, not the framework.

3. **Honest at-rest status — conform to the ConditionStore→CAUSE_ROWS legibility
   seam (600/596/628).** The protection state of each tier is a **typed condition
   with a cause**, per-subject (`at-rest.index`, `at-rest.conversations`),
   reconciled from `(store-class × FDE-sensing × passphrase-state)` into the
   existing `ConditionStore` by a tap modeled on 628's `IndexDriftHealthTap`, with
   stable `LifecycleReasonCode`s worded **once** in `CAUSE_ROWS` and projected to
   the UI by the existing `<jf-system-notice>` / fact-chip path. Tri-state mirrors
   the existing `AssertedCondition.status {TRUE|FALSE|UNKNOWN}` and the
   `present|absent|unknown` fact ternary — never conflate "unknown" with
   "protected".

   **This is where the honesty constraint (§F.4) becomes a _typed guarantee_
   instead of marketing discipline.** The product can only state what the typed
   condition says — exactly the over-claim guard `CAUSE_ROWS` already enforces
   (596 deleted a forked wording map for precisely this reason). The accurate,
   structurally-enforced claim becomes: *"your document index is protected by your
   OS disk encryption \[on|off|unknown]; your chat history is \[passphrase-
   encrypted | protected only by OS disk encryption]."*

4. **Recommend/guide FDE** for the DERIVED tier (detect-and-surface via (2)+(3);
   guide enabling BitLocker). This is the at-rest control for the bulk index — the
   universal industry norm, and the only option that keeps the index on mmap.

### The LAYER — gated on the threat-model decision (Q1), app-level crypto for AUTHORED stores only

Built **only if** Q1 includes the running-machine / no-FDE threat (the one FDE
structurally cannot cover, §D). It never touches the index.

5. **Passphrase-gated app-level AEAD on AUTHORED stores.** Envelope: a random
   per-install **DEK** (AES-256-GCM) wrapping the conversation/memory bytes; the
   DEK wrapped by a **KEK** derived from a user passphrase (Argon2id; pure-Java via
   Bouncy Castle, GCM via the JDK). KDF params + salt + wrapped-DEK live in the
   non-secret settings store; the raw key never persists. Passphrase change =
   re-wrap the DEK only (no bulk re-encryption) — the standard envelope property.
   **The index stays out** (no mmap-compatible crypto; ~20-60% query tax; §C).

6. **Key custody — conform to the shell↔Head boundary, but it is greenfield and
   the channel is net-new (correcting Investigation §C).** OS-native key custody
   belongs to the Tauri/Rust shell (it already owns the OS-native concerns:
   spawn, factory-reset, path-safety), via the `keyring` crate (DPAPI / Keychain /
   Secret Service). **Correction to the charter and Investigation §C:** there is
   *no* existing inbound loopback channel for the shell to hand a DEK to Head — the
   one existing shell→Head channel is spawn-time `-D`/env, which leaks via the
   process table and is unfit for a key. The DEK handoff is therefore a **new
   private channel** (e.g. a stdin pipe at spawn, or a shell-only loopback endpoint
   Head exposes) — named here as net-new, not pretended-existing. Linux without a
   Secret Service must take an explicit "require passphrase, never silent
   plaintext" branch. (Also: `UiSettingsStore` defaults to `IN_MEMORY` in prod —
   that must change for key params to persist.)

7. **Key-loss policy = a projection of the store class, folded into 628's recovery
   taxonomy.** Losing the key is just another fault whose handling reads the class:
   DERIVED → rebuild-from-source (628 *already does this*); AUTHORED → terminal
   data loss, warned at setup, with an **optional recovery-key** second DEK
   envelope. This is why the tiering is safe: a forgotten passphrase can only ever
   cost *authored* data (user-authored, warned), never the rebuildable index.

8. **Lock-on-suspend conforms to 630's resume signal (when it ships)** — re-lock
   the DEK on the cause-agnostic `Resumed` event. *Lock-on-idle* (an inactivity
   timer) is greenfield and a separate small concern; not required for the core.

## 2. What this design deliberately does NOT build (scope discipline)

Per the assignment — structure only where the present problem requires it:

- **No encryption of the mmap'd Lucene index.** Architectural (no interception
  point) + perf (~20-60%) + against the universal norm. FDE covers it.
- **No generic host-capability framework.** One FDE resolver mirroring 587's
  pattern; the generalization is *recorded* (§Reach), not built.
- **No full `dataDir` store-registry with closure gates.** Classify the stores 629
  touches; the registry is the deferred generalization shared with 628/retention/
  backup (§Reach), to be promoted when a *second non-encryption* consumer needs it.
- **No key rotation / HSM / per-store key hierarchy.** One DEK + envelope; the tier
  split is the only key boundary the problem has.
- **The entire LAYER (5-8) is gated on Q1.** The FLOOR is the unconditional
  deliverable; the crypto is conditional on the threat model actually including the
  threat only it can address.

## 3. Open decision that selects everything (the one real fork left)

**Q1 — Threat model.** Unchanged from §G and still the gate: (a) stolen
powered-off laptop → FLOOR alone suffices (FDE); (b) running-machine / malware-as-
user / no-FDE → build the LAYER (passphrase-gated AUTHORED crypto); (c) other local
user → verify the default per-user `%LOCALAPPDATA%` ACL first (may already cover
it); (d) the marketing expectation set by "private local-first" → the FLOOR's
*honest typed status* is the right answer to (d) regardless. The remaining §G
questions (Q2 index-in-scope, Q3 passphrase-vs-keystore, Q5 store scope) are
**resolved by this design**: index is out (Q2), passphrase is the only real tier
and OS-keystore-only is a labeled convenience at best (Q3), and the store scope is
"all AUTHORED stores" by class, not an ad-hoc list (Q5).

---

# Reach (2026-06-22)

> Step-back judgment per the assignment: is this an instance of an existing
> principle (conform), and does it reveal one that reaches further (name, scope,
> note violations — but do not build the general structure now)?

## It conforms to two principles that already exist

1. **Projection-vs-authority** (the codebase's spine — SearchTrace, the
   presentation projection, capability-mediated surfaces). At-rest policy, key-loss
   policy, and UI status are all **projections of one authority** (store class ×
   sensed FDE × passphrase state). The design adds no parallel authority; it adds
   one small authority (the class) and derives the rest. The legibility half rides
   the *exact* CAUSE_ROWS over-claim guard 596 already enforces.
2. **Confidence-carrying host sensing** (587). FDE status is sensed and projected
   with the same `(value, source, confidence)` + tri-state shape as GPU/CUDA — not
   a new bespoke probe.

So 629 is **not** a new subsystem; it is three existing seams (recoverability
invariant from 628, host-sensing from 587, condition-legibility from 600/596/628)
composed, plus a thin crypto envelope on one tier.

## The principle it reveals (name it; do not build it)

**Principle — "Store obligations follow the recoverability class."** A store's
cross-cutting obligations — *durability, confidentiality, key-loss handling,
retention, backup, redaction* — are **projections of whether it is DERIVED (a
reconstructable projection of an external source of truth) or AUTHORED (an
authority of record)**, not independently-authored per-store settings. 628 named
the *durability* half ("reconstruct from source-of-truth"); 629 shows the **same
class governs confidentiality**. Two independent obligations falling out of one
class is the evidence it is a *general storage axis*, not a recovery-specific
detail.

**Candidate scope — where else it applies:**
- **Durability / recovery** — 628 (existing member; the index rebuild *is* this
  principle, hardcoded).
- **Backup / export** — DERIVED needs none (rebuildable); AUTHORED needs it. There
  is **no backup story for conversations today** — a latent gap the class makes
  visible.
- **Retention / clean uninstall** — the gap-analysis "uninstall orphans everything
  uniformly" finding: DERIVED can be deleted freely; AUTHORED is the user's own
  data and warrants export-first / consent. Uniform deletion ignores the class.
- **Diagnostics redaction** — 297 (adjacent; what is safe to export follows the
  same sensitivity-by-class reasoning).

**Existing violations already in the tree (recorded, not fixed here):**
- 628's recovery code **hardcodes DERIVED-vs-AUTHORED at three call-sites**
  (`RuntimeSession` index rebuild, `KnowledgeServer` jobs.db restore, the loud-throw
  else) without naming the class — the canonical instance of the principle, forked.
- **No backup/export path for AUTHORED stores** (conversations/memories) — the
  asymmetry the class predicts but the system does not yet honor.
- **Uninstall** (`lib.rs` factory-reset deletes all-but-models uniformly) — treats
  authored and derived data identically.

**The disciplined boundary (deliberate, per assignment):** build the DERIVED/
AUTHORED tag **only for the stores 629 encrypts**, now. Do **not** build the
`dataDir`-wide store-classification registry with all five consumers — that is the
generalization 628 already deferred for good reason, and three of its consumers
(backup, retention, redaction) are not present problems. **Promote the tag to a
first-class registry when the _second non-encryption_ consumer arrives** (most
likely clean-uninstall/retention or an authored-store backup) — that is the moment
two instances justify the shared structure, mirroring 587's own "second capability
is the trigger" logic. Recognizing the principle now and recording its scope is the
capture; building the framework is the thing to resist until a present problem
needs it.

---

# Research refresh (2026-06-22) — current-threat validation

> A second, **scoped** internet pass run *after* the design settled, targeting the
> two judgments that (a) are load-bearing and (b) sit on fast-moving 2025-2026
> security ground: the FLOOR's reliance on FDE, and Q1's "passphrase-free custody
> is theater" claim. Everything else in the design is architecturally stable or
> general enough not to hang on current specifics, so it was deliberately not
> re-researched. **Verdict: both judgments survive 2026 and are strengthened; the
> design changes are caveats and one added option, not a redesign.**

## R1. Does "rely on/recommend OS FDE for the DERIVED tier" still hold? — YES, with caveats

The floor survives: **nothing found justifies app-level encryption of a local-only
index.** Every 2026 FDE attack targets the key-in-RAM / key-release-at-boot moment,
which app-level index encryption shares (its key is in process memory to serve
queries) — so app-encrypting the index buys nothing against these and adds a worse
key-management burden. *But* the default OS posture is no longer self-sufficient,
so the recommendation must carry caveats:

- **Default TPM-only BitLocker is bypassable in 2026** against a stolen powered-off
  device — independent, mostly-unpatchable-by-default lines: the WinRE "BitUnlocker"
  class (CVE-2025-48800/48003/48804/48818, patched Jul-2025 *but downgrade-bypassable*
  because the PCA-2011 boot cert is not revoked), **bitpixie** (CVE-2023-21563),
  **dTPM SPI bus-sniffing** (unpatchable on 2016-2023 hardware), and **YellowKey**.
  Independent 2026 assessments call default TPM-only *"close to decorative"*
  (Elcomsoft, May 2026) and name a **pre-boot PIN** the single most effective
  control (RiskInsight/Wavestone, Feb 2026). → The floor's recommendation is
  **"OS FDE configured with a pre-boot secret (PIN/passphrase)"**, not "BitLocker
  is on, you're fine."
- **Windows 11 24H2 turns device-encryption on by default (all SKUs incl. Home) on
  clean install + MS-account sign-in, and escrows the recovery key to the user's
  Microsoft cloud account by default.** For a *"private, local-first"* product this
  is a **material honesty obligation**: recommending/relying on BitLocker on default
  consumer Windows implicitly relies on a config where the volume key is in
  Microsoft's cloud. The product must say so (and can point to disabling cloud
  escrow / keeping a local key).
- **Cross-platform:** FileVault (macOS 26 Tahoe, default-on, **E2E** iCloud-Keychain
  escrow as of 2025) is now the *strongest* default and the cleanest privacy story;
  passphrase LUKS is strong but opt-in; TPM2-auto-unlock LUKS shares the same
  bus-sniffing weakness (CVE-2026-0714). The common failure mode everywhere is
  *transparent auto-unlock*; the fix everywhere is a *pre-boot secret*.
- **The one thing that would flip the call toward app-level index encryption:** the
  index **leaving the device** — cloud sync / multi-device / off-device backup. In
  the local-only single-device model, FDE remains the right layer.

## R2. Is passphrase-free OS-keystore custody "theater" vs malware-as-user? — CONFIRMED (empirically + structurally)

- **Empirical:** the dominant 2024-2026 infostealer mechanic (Lumma, RedLine, StealC,
  Vidar, …) is *literally* "run as the logged-in user, call DPAPI / ask the OS to
  decrypt." It is the cross-family invariant (Microsoft threat-intel, 2025; Elastic,
  2024).
- **Structural:** Google shipped the best-resourced passphrase-free counter —
  **App-Bound Encryption** (Chrome 127, 2024: a SYSTEM-privileged COM broker +
  caller-path validation, built *because* DPAPI protects between users not between
  same-user processes). It was defeated, **without admin**, by ≥6 distinct same-user
  techniques (COM-elevation replay, DevTools remote-debug, in-process memory scan,
  code injection, the C4-Bomb padding-oracle on SYSTEM-DPAPI, and hardware-breakpoint
  key interception / "VoidStealer", 2026). The 2026 consensus (Elcomsoft, Gen Digital):
  a secret that must be decryptable inside the trusted process *without user presence*
  cannot be protected from same-user code. **TPM/VBS sealing only raises the bar to
  admin/kernel** — it does not stop same-user replay unless paired with a user-presence
  gate.
- **Precision added to the judgment:** passphrase-free custody is not *zero* over FDE —
  it does add (i) dead-box/offline file-theft protection and (ii) cross-user
  separation on a shared box. Those are exactly the deltas the design's *labeled
  convenience tier* claims, and no more. Against the running-machine / malware-as-user
  threat it is ~nil, as stated.

## R3. Design changes this forces (small, surgical)

1. **Floor wording (point 4):** "recommend OS FDE" → **"rely on OS FDE configured
   with a pre-boot secret; be honest that default consumer Windows (24H2) escrows the
   recovery key to Microsoft's cloud."**
2. **FDE sensing + honest status (points 2-3):** the typed status must carry
   **configuration quality**, not just on/off — `protected (pre-boot secret)` vs
   `weak (TPM-only auto-unlock, bypassable)` vs `key-escrowed-to-cloud` vs `off` vs
   `unknown`. An on/off-only status would *over-claim*, which the whole
   honesty-as-typed-guarantee (CAUSE_ROWS) design exists to prevent. (This widens the
   FDE probe's effective view; it stays one resolver, mirroring 587.)
3. **The LAYER's non-replayable factor (points 5, 8):** a **user-presence gesture
   (Windows Hello / Touch ID)** can substitute for a typed passphrase as the
   non-replayable factor — friendlier UX, same security property. Record as an option
   alongside the passphrase. **Lock-on-idle is confirmed essential** (the key must not
   be resident while the machine is unattended), reinforcing design point 8.
4. **Envelope (point 5): unchanged.** Random DEK + Argon2id-KEK + AES-256-GCM (96-bit
   nonce, one-per-encryption) is still standard. **One watch-item:** NIST SP 800-38D
   Rev.1 is a moving draft (pre-draft comments closed Mar-2025, no final as of
   Jun-2026) — re-check before any implementation; its direction does not disturb
   single-envelope GCM.

**Net:** the research did not move the design's spine (store-recoverability →
projection); it hardened the FLOOR's FDE guidance (pre-boot secret + cloud-escrow
honesty), made the honest-status condition carry config-quality, and added Hello as
a non-replayable factor. The "never app-encrypt the mmap'd index" and "passphrase-
gated AUTHORED-only" decisions are now *more* firmly evidenced, not less.

---

# Frontend / user-facing design (2026-06-22)

> Grounded in a **live UI inspection pass** (dev stack on :5173, Chrome
> screenshots of the Settings, System Health, and surrounding surfaces), not the
> tempdoc alone. The thesis: the user-facing layer is **the same one typed at-rest
> authority (§Design 0), projected at four salience altitudes plus one gate** —
> each altitude conforming to a seam that is *already on screen*. There is no new
> bespoke status widget. Floor vs Layer (§Design 1) carries straight through to
> the FE: the status/guidance altitudes are the unconditional FLOOR; the
> control/unlock altitudes are the Q1-gated LAYER.

## UF.0 What is already on screen (the homes I confirmed live)

- **Host-capability sensing is already rendered with source + confidence.**
  System Health shows a **GPU card**: `GPU acceleration: Detected`,
  `Source: nvml · HIGH` (`HealthSurface.ts:1163-1173`), and an ambient **GPU
  capability chip** via `DeclaredSurface.ts` (`title="via NVML · high
  confidence"`, `data-confidence`). This is the 587 projection, visible today —
  **the FDE/disk-encryption status has a ready-made home as a sibling card +
  chip, in the identical shape.**
- **Per-store status cards** exist: the **QUEUE DB card** (`Status: Healthy ·
  Last backup: Not configured · Integrity check: —`) is the 628 durability
  surface and the exact template for a per-store *protection* row.
- **The condition/verdict banner** is live: a warn pill **"● Reindex required"**
  at the top of System Health (the readinessNotice/verdict path).
- **The availability authority** is live: the **"What you can do right now"**
  rows pair a capability with a typed reason + affordance ("Ask AI… The local AI
  model is offline → Open Health"). A locked-conversation state conforms here.
- **The control home** is the **Settings → DATA** section ("If you want to
  uninstall JustSearch or start fresh, you can delete all local data" →
  *Delete all local data*, routing to the Tauri `confirm_delete_data` flow).
  Encryption opt-in/setup belongs here, as its sibling.
- **Security vocabulary already exists** in the UI: Plugins carry a green
  **"trusted"** badge + *Revoke*; **Durable grants** speak of capability-family
  trust. The product already has a trust/affordance lexicon to match.

## UF.1 The four projection altitudes + the gate

Each is a *projection of the one typed at-rest authority*; none invents wording
(CAUSE_ROWS owns it) or a parallel component.

1. **Ambient chip (lowest salience, optional).** An at-rest protection fact in the
   System-Health capability-chip deck (and/or the bottom status strip), via the
   `DeclaredSurface` fact-chip pattern — carrying `{protected, source,
   confidence}`, rendered muted with the `?`/unknown treatment when sensing is
   `UNKNOWN` (never fabricate "protected"). At-a-glance "Encrypted / FDE-only /
   Unprotected".
2. **Diagnostic card (System Health) — the primary surface.** A **"Data
   protection" card** sibling to the GPU and QUEUE-DB cards, projecting:
   (a) **FDE sensing** with source + confidence in the GPU-card shape —
   `Disk encryption: On (TPM+PIN) · manage-bde · HIGH` / `On (TPM-only — weak) ·
   HIGH` / `Off · HIGH` / `Unknown · LOW`; (b) **per-store protection rows** —
   `Index → protected by OS disk encryption`, `Conversations → disk-encryption
   only` (or `passphrase-encrypted · unlocked/locked` when the LAYER is on). This
   is where the **config-quality nuance (R3.2)** lives — it must distinguish
   On-PIN vs weak-TPM-only vs cloud-escrowed, never a binary lock glyph.
3. **Condition / verdict pill (fires only on a real gap).** When authored data is
   genuinely unprotected *and* FDE is off/weak, a warn-level readinessNotice pill
   + a `CAUSE_ROWS` row (`at_rest.unprotected`) + an affordance ("How to protect")
   — via the existing verdict path (600/596). It is **not a constant nag**: a
   fully-FDE'd machine shows the calm diagnostic card, no banner.
4. **Control (Settings → DATA) — LAYER only.** An "Protect chat history" opt-in
   sibling to *Delete all local data*: choose factor (passphrase or Windows
   Hello / Touch ID), set it, **generate + display a recovery key**, and a
   **loud, honest scope-correct warning** — "if you forget this, your **chat
   history** is permanently lost; your **search index is unaffected** (it rebuilds
   from your files)." Cross-link to *Delete all local data* as the escape hatch.
5. **Gate — locked conversation surface (LAYER only).** When encryption is on and
   locked, the **conversation surface** (not the whole app) shows a locked state +
   unlock affordance (a passphrase field or a Hello gesture), conforming to the
   availability authority (596): chat history is "unavailable, typed reason =
   locked, action = unlock". **Search and the index stay fully available** while
   locked (they are not encrypted) — graceful *partial* degradation, a direct
   consequence of the DERIVED/AUTHORED split.

## UF.2 User-visible consequences (the new states/failure modes)

These are the things that *change for the user* and must be designed, not just the
widgets:

- **The app becomes partially usable while locked.** Search/browse/index work;
  chat history is gated. This is a *property* of the tiering (index unencrypted),
  but it is a new UX state — there is **no app-wide hard lock**, only a
  conversation-surface gate. Designing the locked conversation state (not a blank
  screen, not a full lockout) is the main new UX.
- **A new, narrow failure mode: forgot-passphrase = lose _chat history only_.**
  The index rebuilds (628); conversations do not. The setup warning must be loud
  **and** scope-accurate — over-warning ("you'll lose everything") is as wrong as
  under-warning. The recovery key is the one escape.
- **A privacy disclosure obligation.** If the FDE-guidance affordance recommends
  BitLocker, it must disclose that Windows 24H2's default escrows the recovery key
  to the user's Microsoft cloud (R1) — a "private, local-first" product cannot
  silently endorse a cloud-escrow posture. The honest framing: "your OS can
  encrypt the index; note that Windows backs the recovery key up to your Microsoft
  account by default — here's how to keep it local."
- **Honest status replaces any "we encrypt everything" claim.** The index is
  FDE-protected, *not* app-encrypted; the UI says exactly that. Any onboarding /
  marketing copy implying blanket app-encryption would now contradict the typed
  status — and must be reconciled (I saw no onboarding copy this pass; flagged as
  a consideration, not a found violation).

## UF.3 Why this is structurally honest (the guarantee, not discipline)

Every altitude **reads the same typed at-rest authority**, exactly as the backend
half is a projection of the store-recoverability class. So the chip, the card, the
banner, and the settings copy **cannot contradict each other or over-claim** — the
same property `CAUSE_ROWS` already enforces (596 deleted a forked wording map for
precisely this reason). "We can only show what the typed condition says" is the
mechanism that makes the §F.4 honesty constraint *structural* rather than a copy-
review checklist. The FDE config-quality must therefore be a *typed dimension* of
the authority (On-PIN | weak-TPM-only | cloud-escrowed | off | unknown), so the
honest distinction propagates to every altitude for free.

## UF.4 Floor vs Layer, for the FE

- **FLOOR (build regardless of Q1 — no crypto, pure projection):** altitudes 1-3
  (ambient chip, the "Data protection" diagnostic card, the unprotected-condition
  pill) + the FDE-guidance affordance + honest copy. This alone makes the product
  *truthful* about at-rest protection and is correct however Q1 is answered.
- **LAYER (Q1-gated):** altitude 4 (Settings encryption control + setup + recovery
  key) and altitude 5 (the locked conversation gate, lock-on-idle/suspend). Built
  only if Q1 includes the running-machine / no-FDE threat.

## UF.5 What the FE design deliberately does NOT add (scope)

- **No bespoke status component.** Reuse `DeclaredSurface` (chip), the
  HealthSurface host-capability card pattern (FDE card), `readinessNotice`/
  `CAUSE_ROWS` (banner). Wording stays in the one authority.
- **No app-wide lock screen.** Only the conversation surface gates; the index/
  search are not encrypted, so a global lock would be both wrong (nothing to
  protect there) and a usability regression.
- **The ambient chip (altitude 1) is optional**, not required — the diagnostic
  card is the primary surface; the chip is added only if an always-visible
  indicator is wanted, to avoid status-strip noise.
- **No new onboarding flow** is presumed; the honest-status surfaces are additive
  to the existing Settings/System surfaces. A dedicated "set up encryption now?"
  onboarding step is a LAYER refinement, not part of the floor.

---

# Pre-implementation confidence probe (2026-06-22)

> A read-only probe pass run *before* any implementation, to convert the design's
> assumed-conforming claims into verified facts (or named known-unknowns). One
> local Windows status experiment (P1) + five codebase traces (P2-P6). **No
> feature code.** Each finding is **resolved / resolved-with-caveat / refines-design /
> still-open**. Where a probe corrects an earlier section, it is noted inline and
> **supersedes** that section.

## P1 — FDE sensing feasibility → RESOLVED-WITH-CAVEAT (the most consequential finding)

Empirical, on this Win11 box, **as a confirmed non-elevated user** (`ELEVATED=False`):

- **All three standard BitLocker APIs are DENIED un-elevated:** `Get-BitLockerVolume`
  → `CimException: Access denied`; `manage-bde -status` → "An attempt to access a
  required resource was denied… administrative rights"; WMI `Win32_EncryptableVolume`
  → `Access denied`. **So the "sense FDE best-effort like GPU, no admin" assumption is
  FALSE for the rich APIs** — they need elevation.
- **BUT a non-admin path exists and works:** the Explorer shell property
  `System.Volume.BitLockerProtection` (what the drive-padlock uses) returned a value
  un-elevated for every fixed drive (`C: F: I: → 2` = Off; PKEY enum 1=On, 2=Off,
  3=Unknown, 4=NA, 5/6=de/encrypting). This yields **coarse on/off/encrypting only** —
  **not** protector type (TPM-only vs TPM+PIN), **not** cloud-escrow.

**Design consequence (refines §Design 2-3 and §R3.2):** the honest-status FLOOR is
buildable un-elevated, but only at **coarse fidelity** (`on | off | encrypting |
unknown`, confidence ~MEDIUM, source = shell-property). The **config-quality dimension
(PIN vs weak-TPM-only) and escrow that R3.2 said the status "must carry" is NOT
obtainable from a non-elevated Head.** The design must pick one: (a) accept coarse
status + render config-quality as `unknown (needs elevation)`; (b) capture quality
*once* during the Tauri install (which runs elevated) and cache it; or (c) offer a
one-time elevated probe. This is a genuine new fork the design did not have. *(Side
note: this dev box is unencrypted — convenient for testing the `unprotected` path.)*

## P2 — capability → wire → FE pipe → RESOLVED (clean, no proto)

The full GPU pipe is mapped end-to-end (`GpuCapabilities.Effective` →
`GpuStatusView` → `StatusResponse.gpu` → JSON Schema → generated TS/Zod →
`aiState.status.gpu` → `HealthSurface.renderGpu` + `facts.ts 'core.gpu.accel'` chip).
**No `.proto`** — it's a Jackson record chain, so the heaviest change class is avoided.
A new at-rest capability is a **10-artifact checklist**: a `*View` record + a
`StatusResponse` field + a handler `build*()` + **`./gradlew :modules:app-api:updateSchemas`**
(heaviest) + **`node scripts/codegen/gen-wire-schema-types.mjs`** (mandatory, FE type is
generated) + fixture + `HealthSurface` render + a `facts.ts` chip entry. All
well-trodden (the `api-record` skill exists for exactly this). **Confidence: high.**

## P3 — condition surfacing → REFINES-DESIGN (verdict pill is the wrong surface)

The FE verdict pill is **dimension-bound**: `computeVerdict` only degrades on
`retrieval === 'degraded'` (`verdict.ts:174-178`); reason codes only pick *wording*.
Routing at-rest onto `retrieval` would falsely turn the pill "Service degraded" orange
for an *unencrypted-but-working* index — a category error. **The correct, per-subject
native path is a `ConditionStore` tap modeled on `IndexDriftHealthTap`** (subjects
`at-rest.index` / `at-rest.conversations`): it mints first-class HealthSurface
conditions with **no enum/dimension/verdict/gate change**. **This supersedes §UF.1
altitude 3 and §Design 3:** the at-rest condition is a *HealthSurface condition (via a
tap)*, **not** a global verdict-pill driver. (Optionally, a `LifecycleReasonCode` +
`CAUSE_ROWS` row enables per-affordance `reasonFor()` wording; that triggers the
`check-readiness-reason-codes` forward/backward-closure gate — satisfied by either a
`CAUSE_ROWS` row or a `noWordingExempt` entry.) **Confidence: high** (path is clear and
gate-light).

## P4 — store classification + 628 + append-only → RESOLVED (spine holds)

Verified table, **no conflict with 628**:
- **DERIVED** (FDE-protected, rebuildable): Lucene index, `jobs.db` + `path_resolution`,
  **agent-history** (a Lucene projection of agent-runs), logs/crashes/telemetry.
- **AUTHORED** (passphrase-encryptable, not rebuildable): **conversations, memories,
  agent-runs**.
- **One ambiguous (flag for intent):** `memories/memory.json` is AUTHORED today (the
  single canonical store), but its *contents* are agent-distilled from conversations —
  if ever made re-derivable it would reclassify DERIVED. Treat as AUTHORED for now;
  confirm intent before scoping its encryption.
- 628 only ever rebuilds/restores DERIVED stores, never AUTHORED — the correct
  asymmetry, exactly as the spine predicts.

**Append-only AEAD (P4-C):** `FileConversationStore.appendMessage` is
`Files.writeString(json+"\n", CREATE, APPEND)` line-by-line — so app-level AEAD on
conversations **must be per-record (per-line) framed** (each line its own nonce+tag),
not whole-file. `meta.json` / `memory.json` are full rewrites → whole-file AEAD is fine
there. A concrete, known LAYER constraint. **Confidence: high (classification),
medium (crypto-framing detail is real but bounded).**

## P5 — FE gates / settings persistence / custody → RESOLVED (all bounded)

- **(A) FE gates:** manageable. A plain hand-Lit card (like `renderGpu`) does **not**
  trip the declared-surface/composition gates. Requirements: theme tokens not literals
  (`check-color-tokens`, `check-theme-token-closure`), `<h2>/<h3>` not `<h1>`
  (`check-a11y-closure`), `jf-button` for any affordance (`check-controls-a11y`), reset
  any transient `@state` in `settleTransients()` (`check-surface-task-state-retention`).
  The chip is a `facts.ts` entry. Plus the `consult-doc-hint`/`maintain-doc-hint` on any
  `shell-v0` edit. No surprises.
- **(B) Settings persistence:** CONFIRMED prod mode resolves `UiSettingsStore` to
  `IN_MEMORY` (would not persist) — **but the shipped shell currently runs `prod=false`**
  (alpha TODO at `lib.rs:646`), so it *does* persist today; the trap activates only when
  prod is re-enabled. **Fix surface:** persist key-derivation params to a **separate
  durable keystore file** under `$JUSTSEARCH_HOME`, *not* `UiSettings` (decouples key
  material from the prod-IN_MEMORY policy). Bounded.
- **(C) Custody channel:** CONFIRMED the only shell→Head startup channel is spawn-time
  `-D`/env (process-table-visible, unfit for a DEK); **no stdin pipe** (stdin not
  configured) and **no inbound loopback endpoint** (the session token flows Head→shell,
  the reverse). So the DEK handoff is genuinely **net-new** — configuring a stdin pipe
  at spawn, or a shell-only loopback endpoint. Bounded but unbuilt. (Confirms the §6
  custody correction.)

## Confidence rating for the remaining work

**FLOOR (unconditional): 7.5 / 10.** Every piece is now mapped to a verified seam with
a concrete artifact list (P2/P3/P4/P5A). The one real residual is **P1's
config-quality-needs-elevation fork** — the floor ships honest *coarse* status easily,
but the richer "weak TPM-only vs PIN" distinction (R3.2) needs an explicit decision
(accept-unknown / install-time-capture / elevated-probe). Known, not scary.

**LAYER (Q1-gated): 6 / 10.** Three bounded-but-unbuilt mechanisms — per-line AEAD
framing (P4-C), a separate durable keystore (P5B), and a net-new DEK handoff channel
(P5C) — plus the standard envelope crypto. None is a dead-end; all are "real work,
known shape." And it remains gated on the Q1 threat-model decision (a user call).

**Overall: 7 / 10.** The probe pass removed the two genuine unknowns (can we sense FDE
at all un-elevated → yes, coarsely; does at-rest belong in the verdict pill → no, use a
tap). **Top residual risks:** (1) the FDE config-quality elevation fork (P1); (2) the
LAYER's custody-channel + per-line-AEAD are unprototyped; (3) Q1 still gates whether the
LAYER is built at all. No finding contradicted the design's spine (store-recoverability
→ projection); the corrections were to *fidelity* (P1) and *surface choice* (P3), not to
the architecture.

---

# Pre-implementation confidence probe — LAYER (2026-06-22)

> A second probe pass, this time for the **encryption LAYER** (the Q1-gated remaining work),
> which the FLOOR probe (P1-P6) only *named*. Read-only research + code reads + dependency
> checks. **No feature code.** Each finding tagged **resolved / resolved-with-caveat /
> refines-design / still-open**. Headline: the LAYER's biggest unknowns resolved *favorably* —
> it is mostly reuse of known patterns, not new structure.

## L1 — Windows Hello as a non-replayable factor → RESOLVED-FAVORABLY (the big one)

[RESEARCH] **Windows Hello IS a genuine non-replayable defense — keep it, but only the right
form.** The `KeyCredentialManager` + `KeyCredential.RequestSignAsync` (Microsoft Passport key
credential) path produces a **TPM-generated, non-exportable key whose every private operation
requires a fresh PIN/biometric gesture**, and the key material never enters the app's address
space (Microsoft Learn, Windows Hello; `RequestSignAsync` API ref, updated 2026-03). This
**structurally beats DPAPI / Chrome App-Bound-Encryption against same-user malware** — those fail
precisely because there's no per-use gesture, so the OS hands the secret to any same-user process
silently (Kaspersky/SpyCloud/Elastic 2024-26). Malware can't "replay an authenticated call":
there is no get-the-key call, only "sign this *now* with a live gesture."
- **Caveats (design-honesty):** it protects key *acquisition*, not the unwrapped DEK once it's in
  process RAM (a resident attacker can scrape it during the unlocked window — raises the bar from
  "silent/scriptable" to "must catch a live unlocked session + memory-scrape", not infinite). PIN
  reset / TPM lockout destroys the key → a **passphrase recovery path is mandatory**, so Hello is
  an *optional factor*, never the sole one.
- **Reachability:** clean from **windows-rs in the Rust/Tauri shell** (`windows::Security::Credentials::KeyCredentialManager`,
  crate v0.62); the Java path is ugly (no WinRT projection + the consent dialog renders behind the
  window). → **Custody belongs in the Rust shell; Java consumes the unwrapped DEK.**
- **NEVER** use `UserConsentVerifier` (the "Verified" boolean) — that *is* the theater path.

## L2 — DEK handoff channel shell→Head → RESOLVED (stdin pipe)

[VERIFIED] **stdin is the clean channel; the loopback-endpoint alternative is circular.**
`HeadlessApp` reads nothing from stdin today (grep: zero `System.in`), but has a clean insertion
point in `main()` before `resolveConfig()` (`HeadlessApp.java:620`), well before the API binds.
The Tauri spawn pipes only stdout/stderr (`lib.rs:692-693`, no `.stdin(...)`); adding
`.stdin(Stdio::piped())` + `write_all(dek)` + drop is symmetric to the existing stdout drain, and
keeps the DEK **off the process table and off disk**. The loopback-endpoint option can't
distinguish "shell vs webview" (both are `127.0.0.1`, and the webview holds the session token) —
it would need an out-of-band secret to authenticate, which is exactly what stdin already provides
(circular). Net-new but small on both sides.

## L3 — OS-keyring convenience tier → REFINES-DESIGN (likely drop it)

[RESEARCH] The Rust `keyring` crate (v4 / `keyring-core` 1.0; Windows = Credential Manager) is
usable but is **DPAPI-tier — same-user-malware-readable, i.e. the weak tier R2 called theater**,
with a headless-Linux silent-plaintext-fallback footgun (select store explicitly, fail loudly).
**Given L1 makes Hello a real non-replayable factor**, the passphrase-free keyring tier adds
little over BitLocker + Hello + passphrase. → **Recommendation: drop the passphrase-free keyring
"convenience tier" (or ship it only clearly-labeled weak); the real factors are passphrase and
Hello.** Resolves Q3.

## L4 — Store-encryption integration + locked-state → RESOLVED (decorator; critical insight)

[VERIFIED] Wrap sites + write patterns mapped: **per-record AEAD framing** for the two append-only
line logs (`messages.jsonl` `FileConversationStore:140-145`; `events.ndjson` `RunEventStore:101-107`);
**whole-file AEAD** for the rewrite stores (`meta.json` ×2, `memory.json` `FileMemoryStore:88-100`,
`last-session.txt`).
- **Critical locked-state insight:** *every* read today is **fail-soft to empty** (catches
  `IOException` → returns `List.of()`/null: `FileConversationStore:124-127`, `FileMemoryStore:83-85`,
  `RunEventStore:135-138`). So naive encrypt-under-the-catch would make a LOCKED key render as
  **blank/lost history, not "locked"** — the failure mode to avoid. The typed "locked" state must
  be introduced **above** the swallow.
- **Cleanly gateable:** all conversation/memory reads route through the `ConversationStore` /
  `MemoryStore` interfaces (chokepoint), which already have a `noop()` decorator precedent. → A
  **key-aware decorator** that returns a typed `KeyLocked` sentinel / throws `KeyLockedException`
  when locked is the pattern (agent-runs is a concrete single class, gated the same way). Bounded.

## L5 — Crypto envelope → RESOLVED (BouncyCastle already present!)

[VERIFIED] **BouncyCastle is already a dependency** — `org.bouncycastle:bcprov-jdk18on:1.81.1` on
the runtime classpath (`app-launcher` + `indexer-worker` lockfiles). So **Argon2id (`Argon2BytesGenerator`)
needs no new dependency / no license or lockfile concern**; AES-256-GCM is JDK-25 built-in. The
DEK envelope (random DEK + Argon2id-KEK wrap; setup/unlock/change-passphrase/recovery-key) is the
standard shape. **Still-open (minor):** exact Argon2id params untuned — no Argon2 tooling on this
box to measure (python/node/CLI all absent), so the ~250-500 ms target is a **one-time calibration
at impl** (reference OWASP/RFC 9106 floors), a known-bounded step, not a risk.

## L6 — "Locked" status surface + unlock gate → RESOLVED (reuses FLOOR + 596)

[VERIFIED] The new status rides the **exact `atRestProtection` wire pattern** — a
`ConversationProtectionView` record + a `StatusResponse` field + a `buildConversationProtection()`
(direct copy of `AtRestProtectionView`/`buildAtRestProtection`; in-process, so no probe-cache).
The conversation-surface "locked → unlock" affordance has a ready home in the **596 availability
authority**: a `conversations.locked` `CAUSE_ROWS` row with a `remedy:{kind:'operation',
operationId:'core.unlock-conversations'}`, projected via `UnifiedChatView.tabAvailability()` /
`projectAvailability` (`UnifiedChatView.ts:1835-1872`) exactly like `inference.offline`. One
caveat: `projectAvailability` keys on `aiState` today, so the locked signal must ride into
`aiState` via the new status field (the 595-sanctioned extension path).

## L7 — Classification spine placement → RESOLVED (small new enum)

[VERIFIED] No existing store-registry/recoverability enum (grep clean). → a small new
`StoreRecoverability {DERIVED, AUTHORED}` in `app-agent-api` (where the store SPIs live). The four
AUTHORED stores are constructed at two assembly sites (`HeadAssembly:402,484,545` +
`ConversationApiAssembly:198`); the layer reads a `StoreDescriptor(root, tag)` list aggregated in
`HeadAssembly`, wrapping only `AUTHORED` roots. Small enum + descriptor list, not a registry
refactor.

## Confidence rating for the LAYER

**LAYER: 7.5 / 10** (up from the FLOOR-probe's implicit ~6). Every mechanism is now verified
feasible against a concrete, reuse-heavy pattern: Hello is a *real* defense (the biggest risk,
resolved favorably) via windows-rs; the DEK handoff is a simple stdin pipe; BouncyCastle is
already present; store integration is a clean decorator with the locked-state insight nailed; the
status/UX reuses the FLOOR pipe + the 596 authority; the classification is a small enum.

**Top residual risks:** (1) **implementation complexity of the cross-process key flow** — Rust
WinRT (async Hello) → stdin → Java → store decorator → live locked/unlocked transition testing is
several moving parts across two languages (the main thing that can still surprise; needs real
end-to-end testing, not just units); (2) **the DEK-in-RAM residual** (memory-scrape during the
unlocked window) is a fundamental software limit — must be stated honestly + mitigated (re-prompt
per sensitive op, zero the DEK), not over-claimed; (3) **Argon2id param calibration** (trivial,
one-time); (4) the LAYER remains **Q1-gated** (a user/product decision, not a technical risk). No
finding revealed a dead-end or a fundamental blocker; the design is fully mapped to feasible
patterns. Notable design refinements from this probe: **keep Hello (real) / drop the passphrase-
free keyring tier (theater)**, and **gate the locked-state above the fail-soft read catches.**

---

# Implementation — LAYER (as-built, 2026-06-22)

> User decisions: **build the LAYER** (the Q1 commit — defend the running/unlocked-machine
> threat); **passphrase factor** (Hello deferred — it lives in the Tauri shell, not
> browser-validatable in the dev stack); **all AUTHORED stores** chosen. **Live-UI-validated
> end-to-end** for conversations.

**What shipped — passphrase-gated encryption of the conversation store:**
- **Crypto core** (`app-agent-api/.../encryption/` for the shared cipher; `app-services/.../encryption/`
  Head-side): `StoreCipher` (AES-256-GCM, per-record + whole-file, magic-prefixed for plaintext
  back-compat), `DataKeyState` + `KeyLockedException` (typed locked signal), `StoreRecoverability
  {DERIVED, AUTHORED}` (the classification spine — finally built), `EncryptionEnvelope` (random DEK +
  passphrase-KEK wrap + recovery-key envelope), `EncryptionKeystore` (atomic JSON at
  `<dataDir>/encryption/keystore.json`, persists unconditionally — not the IN_MEMORY-in-prod trap),
  `DataKeyManager` (NOT_CONFIGURED|LOCKED|UNLOCKED; lock-on-launch). Unit-tested.
- **Store integration:** `FileConversationStore` seals `messages.jsonl` per-line; `KeyLockedException`
  propagates past `catch (IOException)` so a locked read surfaces **locked, not empty**. Wired via
  `HeadAssembly` (owns the one `DataKeyManager`) → `ConversationApiAssembly`.
- **API:** `ConversationEncryptionController` + `GET /api/conversations/encryption` +
  `POST .../setup|unlock|lock|recover|change-passphrase`; global `KeyLockedException → 423`. (The
  dedicated endpoint replaced the planned status-wire — no schema regen.)
- **Frontend:** a "Chat encryption" section in `SettingsSurface` (setup → one-time recovery key +
  the loud, **scope-accurate** warning: "lose chat history only; your index is unaffected" — lock —
  unlock). Transient feedback reset in `settleTransients()`.

**KDF deviation (honest):** the probe said Argon2id/BouncyCastle was "already a dependency" —
**wrong** (BC is only transitive on the *worker* modules, not app-services → would need a dep add +
full lockfile regen, slow + shared-`main`-risky). Shipped **PBKDF2-HMAC-SHA256 (600k iters,
JDK-native, OWASP/FIPS-acceptable)**; the `kdf` keystore-header field makes Argon2id a drop-in
upgrade later. AES-256-GCM is JDK-built-in.

**LIVE validation (browser, required — PASSED):** (1) setup via the real UI → keystore written with
**only wrapped material**; recovery key shown. (2) a real Qwen chat → `messages.jsonl` is **`JSEv1:`
ciphertext** and the plaintext marker is **absent** (grep-verified). (3) **lock** (real UI) → history
load **423 `{locked:true}`**, no leak. (4) **unlock** (real UI) → history **200**, content readable.
Full setup → ciphertext → lock-gate → unlock cycle works.

**Deferred (with reasons):**
- **Memories + agent-runs** (rest of "all AUTHORED") — NOT a mechanical copy: they **load once at
  construction** (while LOCKED at launch) and never re-load on unlock, and their fail-soft
  `catch (Exception)` would let a later persist **overwrite ciphertext with empty** → agent-memory
  loss. Needs a re-load-on-unlock design + write-guards; a careful follow-on, not a rushed
  extension. The shared `StoreCipher`/`DataKeyManager` are ready.
- **Windows Hello** — real (L1) but Rust/Tauri-shell-side (windows-rs + stdin DEK handoff); not
  browser-validatable here.
- **OS-keyring tier** — dropped (L3, weak). **meta.json title** — left plaintext (graceful locked
  UX). **Lock-on-idle/suspend** — follow-on (rides 630).

**Pre-existing, NOT 629 (logged):** `check-theme-token-closure` on `RecentsMenu.ts`; `class-size`
`EnvRegistry`/`KnowledgeServer` inherited drift (re-pinned via the 629-LAYER changeset).

---

# LAYER fixes — as-built (2026-06-22, post-critical-review)

A post-implementation critical review found the crypto core sound but **three integration defects**,
two defeating the design's own goals, plus the deferred scope. User chose to fix all + complete
"all AUTHORED data". As-built:

**Defect 1 — FE turned "locked" back into "looks deleted" (the most serious).** `conversationListStore.
resumeConversation` swallowed the backend's 423 into `messages:[]`, so a locked conversation rendered
as an empty (deleted-looking) transcript. Fixed: `resumeConversation` now returns `{locked:true}` on
423; `UnifiedChatView` has a `historyLocked` state (reset in `settleTransients`) and renders
`renderHistoryLocked()` — a clear "encrypted & locked" notice + an **Unlock** button routing to the
Settings control via `requestSurfaceNavigation` — instead of an empty thread. (§L4 enforced at the
*consumer*, not just the API.)

**Defect 2 — meta.json leaked content in plaintext.** `firstUserMessage` (verbatim first user message)
+ `contextFloorSummary` were written unsealed. Fixed: **field-level** sealing in `FileConversationStore`
(`withSealedMeta`/`openMetaContent` over the two content fields; structural fields stay plaintext so the
list survives lock). `listSessions` catches `KeyLockedException` **per session** → keeps the locked
session in the list with its title hidden (FE shows "Untitled"; opening it surfaces the locked notice) —
never drops it (would look deleted) and never fails the whole list. Added `StoreCipher.isSealed` to guard
double-seal. (The list-wide `locked` wire flag → 🔒 badge is the one deliberate deferral — it needs a
`SessionSummary` record + schema change; the empty-title fallback + the on-open locked notice cover the
honesty bar this round.)

**Defect 3 + deferred scope — memories + agent-runs encrypted (completing "all AUTHORED").**
- `DataKeyManager` gained `addListener` firing on every real transition (unit-tested incl. no-op + fault
  isolation). `dek()` now returns a defensive **clone** (fixes the lock()-vs-in-flight-crypto race).
- `FileMemoryStore` (eager cache): seals `memory.json` whole-file; **reload-on-unlock + clear-on-lock**
  via the listener (wired in `HeadAssembly`), so encrypted memory is never stuck-empty after unlock;
  `persist()` refuses while locked (never overwrites ciphertext). Unit-tested: ciphertext-on-disk,
  locked-at-launch→unlock-reloads, locked-persist-refused-file-intact.
- `AgentRunStore` (`meta.json`) + `RunEventStore` (`events.ndjson` per-line) seal/open with the shared
  cipher; lazy reads return empty while locked (documented agent-ledger limitation — lower stakes than
  conversations), writes refuse without overwrite.
- Hygiene: malformed-JSON body in `ConversationEncryptionController` → graceful (400/401, never 500).

**Verification.** Compile green; **full governance gates pass** (`class-size` HeadAssembly 1017→1037
covered by the declared-growth changeset); **3382 FE unit tests pass**; new backend unit tests pass —
`DataKeyManagerTest` (listener transitions + fault isolation), `FileMemoryStoreTest` (seal + reload-on-
unlock + locked-persist-refused), `FileConversationStoreTest` (meta field-seal + list-survives-locked).
ui-web gates pass (presentation-purity, controls-a11y, a11y-closure, color-tokens, surface-task-state-
retention — `historyLocked` reset).

**Live UI re-validation — PASSED.** (A first attempt was blocked by an environmental dev-stack failure:
the Worker crash-looped on `IndexError: Index base path is already locked by another process` because
**days of orphaned dev processes** — runners/Vite/HEAD/Worker back to 6/20 — still held
`default.index.lock` and squatted port 5173; each new start crashed the Worker on the held lock and the
runner then tore the stack down. NOT a 629-code issue. Resolved by killing the 9 orphan PIDs, deleting the
stale lock, and running the dev-runner as a bare persistent background supervisor — the runner's children
live in a `KILL_ON_JOB_CLOSE` Job Object, so a `timeout`/pipe wrapper that lets the runner exit takes the
whole stack with it.)

End-to-end through the real UI: (1) setup encryption (Settings button) → unlocked; (2) a real Qwen chat
("SECRET-MARKER-77") → on disk both `messages.jsonl` AND `meta.json` `firstUserMessage` are **`JSEv1:`
ciphertext** with the plaintext marker **absent from both** (defect-2 fix confirmed); structural meta
fields stay plaintext; (3) **lock** → opening the conversation in the **chat surface** renders the
**locked notice** ("This conversation is encrypted and locked … Unlock") — NOT an empty transcript, and
the secret is absent from the DOM (defect-1 fix confirmed); (4) the **Unlock** button routes to the
Settings control (1C); (5) **unlock** → history 200, the transcript **decrypts and re-renders** (locked
notice gone, content visible); (6) the Health "Data protection" card shows **"Conversations: Encrypted
(passphrase) · unlocked"**, distinct from the disk row (1D). Memory reload-on-unlock (Part 2) is covered by
`FileMemoryStoreTest` (locked-at-launch → unlock → reload restores) rather than a live agent-memory drive.

---

# Future directions & research (2026-06-22)

> A **research-only** pass (documentation, no decision, no code) on what the shipped LAYER could become —
> polish, simplify, extend, or new UX. Method: three parallel prior-art surveys (local-first note apps;
> password managers / E2E messengers; file-crypto & encrypted-export tooling) + targeted technical
> deep-dives (WebAuthn PRF; searchable-encryption 2026). ~30 primary sources; the full source-by-source
> evidence lives in the research transcripts, distilled here. Each idea is tagged **value / effort**.
> Nothing here is committed — it is the menu for the next decision.

## What the research VALIDATED (the design calls that held, now with peer evidence)

- **"Index plaintext, authored-data encrypted, rely on OS FDE" is mainstream, not a corner cut.** Anytype
  states it verbatim — *"these indexes are not encrypted… we recommend turning HDD encryption on"* — and
  Obsidian (*"doesn't encrypt your local vault"*), Joplin (encrypts only at sync-serialization; local
  SQLite plaintext), and Logseq (plaintext local) all delegate local at-rest to the OS. The §0/§F tiering
  is the industry norm. *(sources: Anytype data-security, Obsidian Sync security, Joplin e2ee spec.)*
- **The envelope shape is the convergent pattern.** Wrap a random DEK; recovery = a second independent wrap;
  passphrase change = re-wrap-not-re-encrypt. age, restic, Cryptomator, Tink, Standard Notes, Joplin all do
  exactly this — and **our `KeystoreRecord` already has the multi-wrap shape** (`wrappedDek` +
  `recoveryWrappedDek`), so adding factors/exports is additive, never a re-encryption.
- **PBKDF2-600k is exactly OWASP's current floor** — compliant and not broken, but the *minimum of the
  least-preferred* KDF (OWASP order: Argon2id → scrypt → bcrypt → PBKDF2). Even Borg moved 1.x→2.x off
  PBKDF2 to Argon2.
- **Searchable encryption is still not viable for a local full-text index in 2026** (SSE research remains
  cloud/untrusted-server, structured-data, leakage-bounded). The index stays out of app-crypto — the **one
  thing that flips it is the index leaving the device** (sync/multi-device), unchanged from §R1.

## The menu (ranked within each track)

### Track A — Polish: make the implementation match its own design *(internal, cheap; closes last review's gaps)*
1. **Wire the spine** *(value: med · effort: low)* — make `StoreRecoverability {DERIVED,AUTHORED}` actually
   *select* what gets encrypted (a `StoreDescriptor(root, class)` list in `HeadAssembly` that the cipher
   wiring reads), instead of the current hardcoded store list. Fixes the "declared-but-unread enum" and is
   the **prerequisite** for Track C #7/#11 (one place that enumerates AUTHORED stores → encrypt + back-up +
   export-first-on-uninstall).
2. **One typed authority for the LAYER status** *(value: med · effort: med)* — promote conversation-encryption
   state to a real `at-rest.conversations` condition on the same at-rest authority the FLOOR uses, so the
   honesty-as-typed-guarantee (§UF.3) holds for the LAYER and the Health card reads one source, not a
   separate `/api/conversations/encryption` fetch.
3. **Locked affordance via CAUSE_ROWS / availability** *(value: low · effort: low)* — move the bespoke
   `renderHistoryLocked()` wording into `CAUSE_ROWS` and ride `projectAvailability` (conform, don't build),
   per §UF.5.

### Track B — Recovery hardening: the sharpest UX edge *(cheap, high-value)*
4. ★ **Force "I saved my recovery key" before setup completes** *(value: high · effort: low)* — today the
   recovery key is *optional* → users skip it → forgotten passphrase = silent permanent loss. Apple ADP
   **refuses to enable** encryption without a recovery method; mirror that — make declining an explicit,
   friction-ful choice. The single best prevention of the worst outcome.
5. **"Emergency kit" recovery sheet + word-list recovery key** *(value: med · effort: low)* — a savable/
   printable recovery doc (1Password's packaging) and a BIP39/Cryptomator-style **word-list** recovery key
   instead of raw hex (more transcribable, fewer transcription errors).
6. **Loud, scope-accurate no-recovery wording** *(value: med · effort: low)* — we have the warning; align it
   to the blunt zero-knowledge norm (*"we cannot recover this for you; your index is unaffected"*).

### Track C — Extend: new capabilities the spine + crypto unlock *(the marquee work)*
7. ★★ **Class-aware encrypted portable backup / export (+ import)** *(value: high · effort: med)* — **the
   differentiator.** Today the only authored-data export is a single-conversation *plaintext* Markdown dump;
   there is **no backup at all** (the §Reach gap, made visible by the class). Almost no peer ships encrypted
   export (Joplin/Logseq/Anytype = plaintext only; **only Standard Notes** does it well). Build a
   self-describing, versioned, multi-wrap encrypted container (`format` string + KDF descriptor + ≥1 wrap of
   one DEK + AES-GCM payload; age/restic/Standard-Notes synthesis) that **reuses our exact envelope** and is
   offline-decryptable on a new machine. Avoid the 1Password `.1pux` footgun (its export is plaintext).
8. ★★ **Biometric unlock via WebAuthn PRF** *(value: high · effort: med, platform-gated)* — Windows Hello /
   Touch ID as a **genuine non-replayable factor** *from the existing webview* (WebCrypto), added as a **third
   wrap** of the DEK alongside passphrase + recovery (age-recipient style). This **dissolves the L1 "Hello is
   shell-side, not browser-validatable" deferral** on the primary platform: Win11 25H2 + WebView2 returns PRF.
   Unlike typical biometric unlock (1Password/Bitwarden just re-unlock a *cached* key — a convenience, not a
   factor), PRF derives the KEK *from the live gesture* → it is a real factor. Caveats (refined by the mid-2026
   "Research refresh — the one volatile aspect" below): platform support is bleeding-edge and
   **embedded-webview PRF is unverified**, so treat it as an *opportunistic enhancement behind a runtime
   capability probe*, never a dependency; **passphrase stays the always-available recoverable primary** (PIN
   reset / TPM lockout destroys the credential). (PRF *retrofits* onto existing platform credentials — the
   earlier "must enable at creation" line was the older security-key state.)
9. **KDF upgrade PBKDF2 → Argon2id** *(value: med · effort: med)* — via **BouncyCastle's pure-JVM
   `Argon2BytesGenerator`** (no native dep; the earlier "BC not on app-services" note was about the *jar not
   being a dependency there*, not native complexity — it is a clean dep-add + lockfile regen). Target RFC-9106
   config-2 (m=64 MiB, t=3, p=1) for a once-per-session desktop unlock. Migration is **cheap** — passphrase
   only wraps the DEK, and the `kdf` keystore-header field is already there for agility (old keystores
   re-wrap on next unlock). Converts a GPU-cheap memoryless brute force into a memory-bound one at the same
   unlock latency. *(Keep PBKDF2 only if FIPS-140 ever becomes a constraint — it's the sole FIPS option.)*
10. **Auto-lock lifecycle** *(value: med · effort: med)* — the design (§8/R3.3) called lock-on-idle *essential*
    (the unlocked-window is the threat FDE can't cover). Add an idle timer + lock-on-suspend (rides 630) +
    "Lock now" + lock-on-OS-screen-lock, with the Bitwarden-style timeout menu. We already zero the DEK on
    lock; this adds the *triggers*.
11. **Class-aware clean uninstall** *(value: med · effort: low-med)* — factory-reset today deletes
    all-but-models *uniformly* (the §Reach violation): it treats AUTHORED (irreplaceable) like DERIVED
    (rebuildable). Use the spine to **offer "export your chat history first" (via #7)** before wiping; delete
    DERIVED freely.

### Track D — Honesty / communication *(cheap, trust-building)*
12. **A "what's protected vs not" threat-model disclosure** *(value: med · effort: low)* — the most-trusted
    peers (Anytype's layered "what the node can/can't see"; 1Password's "Beware of the leopard"; Obsidian's
    verification blog) all **state what is NOT protected**. Adopt: (a) index-is-plaintext → enable OS FDE;
    (b) the unlocked-window — *malware on a running, unlocked machine can still read your data* (matches our
    DEK-in-RAM honesty); (c) the Win 24H2 cloud-escrow disclosure for BitLocker.
13. **Config-quality FDE status** *(value: med · effort: high — needs elevation)* — distinguish FDE *with a
    pre-boot PIN* vs *weak TPM-only (bypassable)* vs *cloud-escrowed* vs off (§R3.2/P1). Un-elevated Head can
    only sense coarse on/off; the richer signal needs an install-time (elevated) capture or a one-time probe.

### Track E — Strategic / larger horizon
14. **The sync trigger** *(value: high · effort: high — a product direction, not a feature)* — multi-device /
    sync is the one thing that **reopens Q2** (the index leaving the device flips it from FDE-only to
    needing E2E). age-style passphrase-encrypted file sync (what Logseq Sync actually uses) is the natural
    path; the authored-export format (#7) is a stepping-stone to it.
15. **Coverage extension** *(value: low-med · effort: med)* — the deferred §B/Q5 plaintext stores: **crash
    dumps** (can hold in-memory document content — the easy-to-overlook one, worth it), job-queue **paths**,
    telemetry. Lower priority than conversations; the class tells you crash dumps of authored-data sessions
    are the sensitive ones.

## If forced to pick three
- **#7 Encrypted export/backup** — biggest user value + a real differentiator, and the §Reach payoff the spine
  was built for (it makes #1 "wire the spine" pay rent).
- **#8 WebAuthn-PRF biometric unlock** — the best UX upgrade, a genuinely strong factor (not theater), now
  feasible on the primary platform.
- **#4 Force-save the recovery key** — the cheapest fix for the worst, most likely failure mode (silent data
  loss), and a one-afternoon change.

> Cross-cutting note: #1 (wire the spine) + #7 (export) + #11 (export-first uninstall) are one coherent thread
> — a single authoritative list of AUTHORED stores that the system *encrypts, backs up, and protects on
> delete*. That is the moment the DERIVED/AUTHORED class stops being a label and becomes the load-bearing
> storage axis §Reach predicted (the "second non-encryption consumer" trigger — backup/uninstall — has now
> arrived).

---

# Long-term design for the remaining work (2026-06-22)

> Theorized after investigating the existing seams (`StoreRecoverability`, 628's recovery contract, the
> `ConditionStore→CAUSE_ROWS` legibility seam, the `governance/*.v1.json` register pattern, 630's merged
> resume handling, the `confirm_delete_data` uninstall flow). The menu above is the *what*; this is the
> *correct long-term shape* — scoped to the problem the tempdoc actually has, conforming to existing seams,
> adding structure only where the present problem requires it. General, not implementation-level. It
> supersedes the menu's framing where they differ.

## The through-line: the recoverability class is the authority; obligations are projections

The high-value items are not independent features. They are the moment the **DERIVED/AUTHORED class stops
being a label and becomes the load-bearing storage authority** §Reach predicted. §0 already named the shape
("at-rest protection is a projection of store recoverability"); the design here is to *realize* it across the
obligations that have now each accrued a consumer:

| Obligation | Authority it should project from | Status today |
|---|---|---|
| Confidentiality (encrypt) | recoverability class | shipped — but **hardcoded, not projected** (the defect) |
| Durability (rebuild vs terminal-loss) | recoverability class | shipped in **628** — also hardcoded at its call-sites |
| Backup / export | recoverability class | **absent** (the gap the class makes visible) |
| Protect-on-delete (export-first uninstall) | recoverability class | **absent** — `confirm_delete_data` wipes uniformly |

Four obligations, one class. Two already have a consumer (628 durability + 629 confidentiality); backup and
protect-on-delete are the **second and third non-encryption consumers §Reach said would justify promoting the
tag** — now visible, not yet built.

## What the present problem requires NOW (warranted; small; these are defects)

1. **Make the class load-bearing for the one shipped consumer.** The defect: encryption *hardcodes* its
   target stores while `StoreRecoverability` sits unread, so the design's central claim is prose, not
   mechanism. Correct shape: a single authoritative **store list** — `(store, class, dataPath, write-framing)`
   — that the encryption wiring *reads* to decide what to seal, aggregated where the stores are already
   constructed (`HeadAssembly`). Minimal: a descriptor list, **not yet** a governed register. This converts
   "encryption is a projection of the class" from claim into fact and positions the *same list* for the next
   two consumers.

2. **Conform the LAYER status to the one typed at-rest authority.** The shipped LAYER forks a second status
   source (the dedicated `/api/conversations/encryption` endpoint) instead of projecting from the
   `ConditionStore→CAUSE_ROWS` seam the FLOOR already uses (`AtRestHealthTap`/`at-rest.unprotected`). Correct
   shape: a sibling assertion `at-rest.authored` (per-subject) reconciled from the `DataKeyManager` state into
   the *same* ConditionStore, worded once in CAUSE_ROWS, projected to the card + the locked affordance via the
   existing availability path (596) — extend the FLOOR's tap, no new structure. This restores
   honesty-as-a-typed-guarantee to the LAYER: every altitude reads one authority and they cannot drift. (Fixes
   last review's deviation.)

These two are the implementation not matching its own design — present defects, warranted now, both small.

## The correct shape for the future ideas (named; built only when committed)

3. **The envelope is factor-agnostic — one DEK, many independent custody paths.** The keystore already wraps
   one DEK under two KEKs (passphrase, recovery). The long-term shape recognizes these as two instances of a
   *factor* (a way to *produce a KEK* that unwraps the DEK) and generalizes the record to a **list of
   factor-wraps** — the age-recipient / Cryptomator-masterkey / Standard-Notes pattern the research confirmed
   is universal. The core operation is "unwrap DEK given a KEK"; each factor only differs in *how it produces
   the KEK*. Adding a factor re-wraps, never re-encrypts.
   - **Biometric (WebAuthn-PRF) is just a third factor whose KEK is browser-derived** (WebCrypto) — which
     *refines §Design 6*: there are now two custody paths, the shell-side Hello (windows-rs + the §6 stdin DEK
     handoff) and a **simpler webview-side PRF path** (no Rust, no new channel). Passphrase stays the
     always-available recoverable factor.
   - Build the N-factor record **when the third factor (PRF) or the export lands** — two wraps suffice today.

4. **Encrypted export/backup is the same envelope applied to a portable file, selected by the class.** Take
   every AUTHORED store (from the store list, #1), serialize, seal with the *same* factor-agnostic envelope
   (#3) into a self-describing, versioned container (format string + KDF descriptor + factor-wraps + AEAD
   payload — the age/restic/Standard-Notes synthesis). Import is the read side. It composes existing seams (the
   class selects *what*; the envelope seals *how*); the only new artifact is the container format.
   Standard-Notes-style (encrypted, self-contained, offline-decryptable), never the `.1pux` plaintext footgun.

5. **Backup + class-aware uninstall are the second/third consumers — and the moment to promote the store list
   to a governed REGISTER.** When a second obligation reads the list, an unclassified new store silently
   diverges (encrypted-but-not-backed-up, or wiped-without-export). *That* is when the list earns a **governed
   register with a closure gate**, conforming to the existing register pattern (`governance/execution-
   surfaces.v1.json`, tempdoc 553): a declared canonical store list + a build gate that fails when a new
   `dataDir` store is added without a class + the discovery-oracle role. Mirror that shape; do not invent a
   parallel governance mechanism. Until then the descriptor list of #1 is enough — promoting earlier is the
   premature `dataDir`-wide registry §Reach explicitly deferred.

## Conformances that need no new structure
- **Key-loss is a fault that reads the class — fold into 628's recovery contract** (§7, now concrete): DERIVED
  key-loss → rebuild-from-source (628 already); AUTHORED key-loss → terminal, with the recovery-key factor as
  the escape. The class the recovery dispatcher reads is the *same* store list (#1).
- **Auto-lock = triggers that call the existing `DataKeyManager.lock()`.** *Refinement (630 is merged):* 630
  shipped **no** reusable cause-agnostic "Resumed" event — it gated the Worker on Head-PID liveness and made
  the Head health-monitor double as a wake detector. So lock-on-suspend hooks *that* wake detection (or a small
  idle timer); the §8 "rides 630's Resumed signal" assumption is updated here.
- **Recovery hardening** = a state guard in the existing setup flow (don't complete until the recovery factor
  is saved — Apple-ADP gating); **KDF upgrade** = swap the derive behind the existing `kdf` header agility;
  **honest disclosure** = copy projected from the typed status (#2). None is new structure.

## Scope discipline — what this design deliberately does NOT build
- **No `dataDir`-wide registry with all five §Reach consumers** — only the store list the present + next-two
  consumers need (durability already lives in 628; redaction/retention have no present consumer).
- **No factor *plugin* framework** — three concrete factors (passphrase, recovery, PRF), generalized 2→N, not
  an open registry.
- **No app-encryption of the index, and no second status authority** — both re-confirmed by the 2026 research
  (the index stays FDE-only; honesty stays one typed authority).

## Research refresh — the one volatile aspect (WebAuthn-PRF custody, mid-2026)

A scoped pass on the *only* part of this design on fast-moving ground: **biometric unlock via WebAuthn PRF
from the webview** (the rest — the spine, the envelope, Argon2id, recovery UX, export format, the index call
— is internal architecture or settled/already-researched, and post-quantum does not apply to a symmetric
passphrase-wrapped DEK with no key exchange). It resolves a genuine design fork (commit to webview-PRF vs keep
the §6 shell-side Hello) and a contradiction left open last round.

**Current facts (mid-2026):**
- The "Windows Hello lacks `hmac-secret`" blocker is **resolved**: the **Feb-2026 cumulative update KB5077181
  added `hmac-secret` to Windows Hello** on Win11 **25H2 build 26200.7840+**, so Hello now returns PRF — but
  only on that stack, plus **Edge/Chrome 147+** (`WEBAUTHN_API_VERSION_8`) to surface PRF-on-create; 146 is
  auth-only; Win10 never. macOS: iCloud-Keychain PRF since macOS 15 / Safari 18+. (sources: corbado PRF-2026,
  MS Q&A, chromestatus.)
- **The make-or-break residual is still open:** PRF inside an **embedded WebView2 / WKWebView** (vs a
  standalone browser) is **undocumented**, and there is **no production desktop (Tauri/Electron) precedent**
  for PRF-as-local-encryption. WebView2 is Edge-based so it *should* inherit it, but embedded webviews often
  restrict advanced WebAuthn — unverified.
- **RESOLVED (targeted research, 2026-06-23):** the residual is now **answered — and it kills the
  webview-PRF path.** **WebView2 does not support WebAuthn *at all*** (Microsoft: "WebAuthn is not currently
  supported in WebViews"), and Tauri's own tracking issue (#7926) confirms passkeys "will not work" in the
  webview — "webview-based apps are in the worst shape." So the **"simpler webview-side PRF path (no Rust,
  no new channel)"** floated below is **not viable in our Tauri shell**. The proven pattern is the opposite:
  invoke the OS WebAuthn API **natively in the shell** (windows-rs on Windows, Apple `AuthenticationServices`
  on macOS) and hand the PRF-derived KEK to Head over IPC — exactly Vault12's open-source
  `electron-webauthn-mac` (Jan-2026, platform-authenticator PRF for client-side encryption). **This
  vindicates the original §Design-6** (custody belongs to the shell + the stdin/loopback DEK-handoff
  channel): the shell-native route is now the *only* route, not a fallback. (sources: corbado PRF-2026, MS
  WebView2 docs, Tauri #7926, Vault12 electron-webauthn-mac.)
- **Retrofit (corrects last round):** for *platform* authenticators (Hello, iCloud Keychain), PRF retrofits
  onto existing credentials — **no creation-time flag** (the "must enable at creation" caveat was the older
  *security-key* state). Moot anyway if encryption mints a fresh credential. PRF also exposes a dual-salt
  input → clean KEK rotation without re-creating the credential.
- The authoritative guidance is explicit: **treat PRF as an enhancement, not a core dependency.**

**Resolved design stance (sharpens §3 #8 from "verify later" to a committed shape):**
1. **Passphrase stays the one committed, always-available, *recoverable* primary factor.** Biometric is never
   the sole factor (PIN reset / TPM lockout destroys the credential).
2. **Biometric is an opportunistic *additional wrap*, gated behind a runtime capability probe** (`prf.enabled`
   + the OS/runtime version chain above) — offered only where it actually works, never assumed.
3. **Keep both custody paths open as alternatives; do not commit the design to one.** Webview-PRF (simpler —
   no Rust, no §6 channel) is the *preferred-if-it-works* path but is unverified in the embedded webview;
   shell-side Hello (windows-rs + stdin, §6) is the robust fallback if embedded-webview PRF proves
   unavailable. **An on-device capability probe — not the design — picks the path.** This is exactly the
   "enhancement, not dependency" posture the evidence prescribes.

So the research did not move the design's spine; it converted the biometric factor from a vague "verify per
target" into a precise *opportunistic-enhancement-behind-a-probe, passphrase-always-primary* shape, and
re-confirmed that committing the design to a single custody path now would be premature.

---

# Reach (addendum, 2026-06-22)

## It conforms to two principles already in the tree (do not fork them)
1. **§Reach's own "store obligations follow the recoverability class"** (628 = durability; 629 =
   confidentiality; now backup + protect-on-delete). The design adds no parallel classification — it makes the
   *existing* class load-bearing. The trigger §Reach set (the second non-encryption consumer) has arrived.
2. **The codebase's "governed register + projection" pattern** (`execution-surfaces.v1.json`/553,
   `declared-surfaces`, …). The store list, when promoted (#5), is the *same* shape — a declared canonical
   authority + a closure gate + a discovery oracle — not a new governance mechanism.

The design also applies the **projection-vs-authority spine twice**: store-class → {encrypt, backup, delete},
and data-key → {passphrase, recovery, PRF factors}. Two independent obligation-sets falling out of two
single authorities is evidence this is the system's deep shape, not a local trick.

## The shape it reveals — name it, don't build it
**"One protected secret, many independent custody paths; adding a path re-wraps, never re-protects."** The
multi-recipient-envelope invariant. Candidate scope: anywhere the system gates an asset behind credentials.
**Today there is exactly one consumer (the data key)** — so it is named, not generalized; and it is itself an
instance of projection-vs-authority (the DEK is the authority, each factor a derived unwrap path), so it needs
no new principle — only the factor-agnostic record (#3) when a third factor lands.

## Violations the design exposes (record; fix only what the present problem needs)
- **`StoreRecoverability` declared-but-unread** — even the one shipped consumer hardcodes its targets. **Fix
  now** (#1): present defect.
- **The LAYER status forks a second authority** — **fix now** (#2): present defect.
- **628's recovery sites hardcode the class** without naming it (§Reach already recorded) — *record*; migrate
  to read the store list only if/when it exists.
- **`confirm_delete_data` wipes uniformly** (AUTHORED treated as DERIVED) — *record*; fixed by the
  export-first-uninstall consumer (#5) when built.

---

# Frontend / user-facing design — the LAYER's long-term shape (2026-06-22)

> Grounded in a **live inspection pass** (dev stack on :5173; the shipped Chat-encryption control in all
> states, the Health "Data protection" card, the Settings → DATA section, the 596 "What you can do right
> now" authority — Chrome screenshots + shadow-DOM reads). Findings first (do not judge from the tempdoc
> alone), then the design. It extends the FLOOR's §UF altitudes to the LAYER's setup / recovery / export /
> biometric / auto-lock work. General, not implementation-level.

## What the live inspection found (the real UX, not the design's intent)

1. **Setup is a single passphrase field + one "Encrypt chat history" button** — no ceremony, no factor
   choice, no recovery emphasis. The scope-accurate warning is inline (good), but it is *passive copy*, not a
   gate.
2. **The recovery key is the single most dangerous UX gap.** It is shown **once**, as a raw hex string in a
   warning box, **and is gone the moment you navigate away** (it is transient `@state`, reset in
   `settleTransients`). Worse, it is **genuinely un-re-displayable** — the key itself is never stored (only
   its wrap is), so the UI *cannot* show it again. Observed live: after setup + one navigation, the unlocked
   control shows only *"Your chat history is encrypted and unlocked. Lock now"* — no recovery key, no path
   back to it. A user who doesn't copy it in that one transient moment has **silently lost their only
   recovery path**.
3. **The unlocked control has no management affordances** — no view/regenerate recovery key, no
   change-passphrase, no turn-off, no biometric option, no auto-lock setting. It is lock/unlock only.
4. **The Health "Data protection" card showed `Conversations: Not encrypted` while the store was actually
   `unlocked`** — a live staleness bug: the card fetches the LAYER status *once on load* (the separate
   `/api/conversations/encryption` endpoint) and never reacts to state changes. **Direct, observed evidence
   for design #2** (the LAYER status must be one reactive typed authority, not a forked one-shot fetch).
5. **The DATA (delete) section is the encryption control's immediate sibling**, and its "Delete all local
   data" is a **friction-ful, statechart-driven type-"DELETE"-to-confirm ceremony** — the ready-made pattern
   for both the recovery-save gate and export-first-uninstall.
6. **The 596 "What you can do right now" availability authority is live** (each row = a capability + a typed
   reason + an affordance, e.g. *"Ask AI… The local AI model is offline → Open Health"*) — the exact seam the
   locked-conversation gate should ride (it currently uses a bespoke notice).
7. **No authored-data backup/export exists** — only a single-conversation *plaintext* Markdown dump.

## The center of gravity: the recovery key is the one unrecoverable, un-re-displayable failure

Everything else (lock/unlock, status, even forgotten-passphrase-with-a-saved-key) is recoverable or benign.
**Losing the recovery key is the only silent, permanent, irreversible loss** — and the inspection shows the
current UI makes it *easy* to incur (one transient reveal) and *impossible* to undo (un-re-displayable). So
the user-facing design's center of gravity is: **make saving the recovery key unavoidable at setup, make the
stakes legible, and make "I lost it" recoverable only by regeneration** — not a feature among many, the spine
of the recovery UX.

## The design — by salience altitude (conform to §UF.1; extend, do not invent)

1. **Control (Settings) — a setup *ceremony* + an unlocked *management surface* (the bulk of the work).**
   - **Setup = a short statechart ceremony** mirroring the live delete-ceremony (idle → … → confirmed): (a)
     factor choice (passphrase now; "+ Windows Hello / Touch ID" when the PRF factor lands); (b) passphrase +
     a strength hint; (c) a **required recovery-save gate** — present the recovery key as a **word list**
     (transcribable, not raw hex), with **copy + "download emergency kit"**, and **do not complete setup until
     an explicit "I've saved my recovery key" confirmation** (Apple-ADP gating). This is the present-defect
     fix #4/#5/#6, and it conforms to the existing friction-ful ceremony, not a new pattern.
   - **Unlocked = a management surface** (today it is bare): **regenerate recovery key** (since it can't be
     re-shown, regeneration — a new recovery wrap — is the *only* recovery-management affordance, and itself
     re-runs the save-gate), **change passphrase**, **add Windows Hello** (when PRF), **auto-lock setting**,
     **turn off encryption**. Conforms to the existing Settings-section shape.
2. **Status — one reactive typed authority (fixes the observed staleness, #4).** The card, the control, and an
   optional chip all read the **same reactive `at-rest.authored` condition** (design #2), so they cannot go
   stale or disagree. This is the FE half of "honesty as a typed guarantee" — observed broken today.
3. **Gate — the locked chat pane rides the 596 availability authority** (design #3), not a bespoke notice:
   chat history = "unavailable · reason = locked · action = Unlock", while **search/index stay available**
   (partial degradation, a direct consequence of the DERIVED/AUTHORED split).
4. **Data cluster — backup/export + export-first-uninstall as DATA-section siblings.** "Export my data
   (encrypted)" / "Import"; and the delete ceremony gains an **"export first?"** step (offer the encrypted
   export before the uniform wipe — the §Reach protect-on-delete consumer). Shape-for-when-built.
5. **Honest copy — what's protected vs not**, projected from the typed status: the index relies on OS disk
   encryption (enable it); the unlocked window means a live compromised machine can still read the data. The
   most-trusted peers all state this; it builds trust.

## Scope discipline (present fixes vs shape-for-when-built)
- **Present fixes** (the shipped control's real, observed gaps): the **recovery-save gate at setup**, the
  **reactive typed status** (fixes the live staleness), the **management affordances for what already exists**
  (regenerate-recovery / change-passphrase / turn-off), and the **596-conformed locked gate**.
- **Shape-for-when-built** (no UI yet, design recorded): the encrypted **export/import** flow, the **biometric
  factor** in the ceremony, **auto-lock** settings, and **export-first-uninstall**.

## Reach note (FE)
Conforms to existing FE seams only — the delete-ceremony statechart, the Settings-section pattern, the 596
availability authority, the ConditionStore→status projection, the `facts.ts` chip. **No new component class.**
The FE half mirrors the backend spine: *every altitude is a projection of the one typed at-rest authority* —
which the live staleness bug (#4) shows is currently violated and the design restores.

---

# Future-directions confidence probe (F1–F7, 2026-06-22)

> A read-only de-risking pass (+ one browser experiment) **before** any implementation of the
> future-directions work, mirroring the FLOOR `P1–P6` / LAYER `L1–L7` probes. Each converts an asserted
> "conforms to seam X" claim into **confirmed-@`file:line`** / **gap-found → adjustment** / **accepted-limit**.
> No feature code was written.

**F1 — typed-authority status pipe (#2) → CONFIRMED (the wire I skipped *is* the pattern).** The FLOOR taps
are driven **per `/api/status` call** (`CoreApiAssembly:199,221`): `AtRestHealthTap.accept()` re-probes →
`reconcile()` → upserts the `ConditionStore` condition each poll, and the card reads the
`status.atRestProtection` *wire field* it already polls. So the fix is to **re-run the FLOOR's
`atRestProtection` wire pipe for the LAYER** — a `status.conversationProtection` field +
`buildConversationProtection()` + a sibling `at-rest.authored` tap reading `DataKeyManager.state()` (cheap,
synchronous; no listener needed). The live staleness bug exists *because* the LAYER used a one-shot endpoint
instead of the polled wire field. *Adjustment: none — #2 confirmed; effort is the known 10-artifact wire (P2),
not new structure.*

**F2 — export/backup plumbing (#7) → DOWNLOAD half CONFIRMED; import half standard-but-unbuilt.** Precedent:
`AgentSessionController:120-139` streams a bundle with `Content-Disposition: attachment; filename=…` to trigger
a browser download (also `/api/diagnostics/export`, `StatusRoutes:43`), and Content-Disposition works in both
the browser and the Tauri webview → **export needs no Tauri file-save dialog.** So export = a `GET …/export`
endpoint that enumerates AUTHORED stores (F5) → serializes → seals with the envelope → streams as an
attachment. *Adjustment: the marquee's hard half is de-risked; the **import** half (POST-upload → unseal →
write-back with collision semantics) has no direct precedent, and the **mixed serialization** (per-line JSONL
vs whole-file JSON) needs care — that is the real remaining work.*

**F3 — KDF agility (#9) → GAP CONFIRMED (corrects an optimistic claim).** `EncryptionEnvelope.deriveKek`
(`:108-110`) hardcodes `PBKDF2WithHmacSHA256`; `unlock`/`recover`/`changePassphrase` pass `rec.iterations()`
but **never branch on `rec.kdf()`** — the header is stored but unread, so "drop-in Argon2id upgrade" is
optimistic. BC is genuinely absent from `app-services`. *Adjustment: the upgrade is bounded **real** work, not
a swap — (a) add `bcprov-jdk18on` to `app-services` + lockfile; (b) make `deriveKek` **read** `record.kdf()`;
(c) PBKDF2 for existing keystores / Argon2id for new, optional re-wrap-on-unlock migration. The `kdf` header is
the right seam; it just needs to be read.*

**F4 — recovery ops + setup ceremony (#4) → FEASIBLE; one op to record.** `DataKeyManager.changePassphrase`
("the recovery key is preserved") proves the symmetric **regenerate-recovery-key** op (re-wrap the unlocked
DEK under a fresh recovery key, passphrase preserved) is directly analogous — feasible but **unbuilt** (needs
`EncryptionEnvelope.changeRecoveryKey` + `DataKeyManager.regenerateRecovery` + an endpoint). The setup
*ceremony* conforms to the **generic declarative statechart** `createMachine`/`InteractionMachine`
(`substrates/interaction/index.ts`) the delete flow already uses. *Adjustment: record the regenerate-recovery
op as required; the ceremony substrate already exists.*

**F5 — spine wiring sites (#1) → CONFIRMED CLEAN.** Exactly **three** AUTHORED `StoreCipher` injection points:
conversations (`ConversationApiAssembly:201`), memories (`HeadAssembly:564`), agent-runs+run-events
(`HeadAssembly:415` → shared with `RunEventStore`). Matches L7; no stray sites. *Adjustment: none — the
`StoreDescriptor` list is 3 roots; the wiring is small.*

**F6 — locked-gate via 596 (#3) → RECIPE CONFIRMED + sequencing finding.** `unavailableFor('code')` →
`reasonFor(code)` → `{wording, remedy}` (`availability.ts:104,156`; `inference.offline` is the template) is
the recipe — add a `conversations.locked` CAUSE_ROWS row + project it. **But `projectAvailability` derives from
`aiState`, so the locked signal must reach `aiState` — #3 is BLOCKED ON #2.** *Adjustment: sequence — build #2
(status wire) first; #3 rides it.*

**F7 — PRF FE happy-path (#8) → FE half CONFIRMED; WebView2 stays the known-unknown.** Live in the dev browser:
secure context ✓, WebAuthn API present ✓, **a platform authenticator (Windows Hello) available** ✓, and the
**HKDF→AES-GCM KEK wrap/unwrap of a DEK round-trips** ✓ — the browser-side multi-wrap envelope is trivial given
a PRF secret. *Accepted limit (per the research refresh): the actual PRF output from an **embedded WebView2**
is untestable here (dev Chrome ≠ the Tauri webview; a real Hello ceremony was avoided). Non-blocking by design
— PRF is opportunistic behind a runtime probe, passphrase always primary.*

## Confidence ratings (0–10) after the probe
- **Polish (spine #1 / typed status #2 / locked gate #3): 8** — every conformance confirmed against a proven
  seam; #3 only sequences after #2. Lowest-surprise track.
- **Recovery (#4/#5/#6): 7.5** — ceremony substrate + re-wrap pattern confirmed; one analogous op
  (regenerate-recovery) to build.
- **KDF (#9): 7** — a real gap found, but the fix is bounded and the header seam is right (dep-add + branch +
  migration).
- **Export/backup (#7): 6.5** — the hard half (download) de-risked via a real precedent; import + mixed-format
  serialization are the remaining standard-but-unbuilt work.
- **Biometric (#8): 6** — FE half trivially confirmed and the design ships fine as an opportunistic
  enhancement, but PRF-in-WebView2 itself stays unverified (non-blocking by design).
- **Overall remaining work: 7 / 10.** The probe removed the real surprises (F3's KDF gap corrected, F2's
  download precedent found, F1's wire-pattern confirmed, F6's sequencing dependency surfaced) and confirmed the
  design's conformance claims hold. Residual risks are bounded and named: the export import/serialization half,
  the KDF migration, and the (designed-around) WebView2-PRF unknown.

---

# Implementation — remaining work (as-built, 2026-06-23)

Implemented Phases 1–6 of the approved plan; each landed build-green and (for user-visible work)
browser-validated against the live dev stack. Everything extends an existing seam — no new architecture.

## Phase 1 — backend foundation ✅ (validated)
- **1A spine (#1).** `HeadAssembly.storeCipher(StoreRecoverability)` is now the load-bearing selector:
  `AUTHORED → new StoreCipher(dataKeyManager)`, `DERIVED → StoreCipher.disabled()`. All 3 cipher
  injection sites call it (agent-runs + memories in `HeadAssembly`; conversations in
  `ConversationApiAssembly`) — the enum is the decision input, not decoration.
- **1B typed status (#2) — the staleness-bug fix.** New `ConversationProtectionView` record +
  `StatusResponse.conversationProtection` + `status.proto` msg (field 32) +
  `StatusLifecycleHandler.buildConversationProtection()` (direct `DataKeyManager.state()` read; no probe,
  no cache) wired via a `setConversationProtectionSupplier` from `CoreApiAssembly`. **FE:**
  `HealthSurface` Conversations row now reads `status.conversationProtection` — the one-shot
  `loadConvEncState` fetch + its `@state` were deleted. *Live-proof:* `/api/status` tracks
  `not_configured→unlocked→locked` reactively at the wire, and the card reflects the true current state
  on **each load/remount** (was a permanently-stale "Not encrypted" from a pre-setup one-shot fetch).
  **Correction (post-impl re-validation):** the original "updates live without reload" claim is NOT
  proven — in the re-validation `HealthSurface.status` was **frozen post-mount** (`observed_at` stuck for
  minutes while the API advanced), so the card refreshes on remount, not within a session. This is a
  **shared, pre-existing** behavior: `this.status` comes from the app-wide `aiStateStore` poll (not a
  629 surface), affects *every* status field on the surface equally, and was observed during a stack that
  was "Reconnecting" — so it is plausibly a stalled-shared-poll artifact, not 629-specific. Logged to the
  inbox; not fixed here (out of 629 scope). The 629 win that IS proven: the card reads the true
  single-authority status, correct on load, instead of a permanently-wrong dedicated fetch. Scoped out:
  the optional `at-rest.authored` *condition tap* (the wire field is the load-bearing reader; the tap is
  secondary health-events legibility).
- **1C KDF agility (#9) — F3 gap fix, scoped.** `EncryptionEnvelope.deriveKek` now **branches on
  `record.kdf()`** (the header was stored-but-unread — F3); all 5 call sites pass the kdf, unknown KDF
  fails closed. **The Argon2id-via-BouncyCastle swap is a recorded follow-on** (the dep-add + lockfile
  regen is the riskiest, lowest-user-value change; PBKDF2-600k is OWASP-compliant meanwhile). The
  structural gap (dead header) is closed; Argon2id is now a true one-`else if` drop-in.

## Phase 2 — recovery hardening + management surface ✅ (validated)
- **Backend regenerate-recovery (#4).** `EncryptionEnvelope.changeRecoveryKey` (twin of
  `changePassphrase`) + `DataKeyManager.regenerateRecovery()` (requires UNLOCKED) +
  `POST .../regenerate-recovery`. Unit test: the new key recovers, the OLD key no longer does, DEK
  unchanged, locked→`KeyLockedException`.
- **FE (#4/#6).** A **recovery-save gate** (`encAwaitingRecoverySave`): after setup/regenerate the
  control shows the key + **Copy / Download kit / "I've saved my recovery key"** and does not return to
  the management view until acknowledged. *Correction:* this gate is **advisory**, not a true Apple-ADP
  block — backend setup completes before it renders, and navigating away discards the unshown key. **Fix
  3** closes that with a **persistent reminder banner** (see the fixes section): a localStorage-backed
  `encRecoveryUnsaved` flag (survives navigation; set on setup/regenerate, cleared on acknowledge) drives
  a warning banner in the management surface until the user confirms they saved the key. **Management
  surface** (unlocked): Regenerate recovery key · Change passphrase (inline form) · Export encrypted
  backup · Lock now. *Live-proof:* setup → gate → ack → management surface; regenerate issues a fresh
  32-char key (200); the un-saved banner persists across navigation until acknowledged. Implemented as
  `@state` rather than a full `createMachine` ceremony — the force-save gate is the load-bearing behavior;
  the statechart
  mechanism is a noted conformance refinement.

## Phase 3 — locked-conversation gate conform (#3) ✅
- `conversations.locked` added to `CAUSE_ROWS` (`readinessNotice.ts`) + the `feDerived` allow-list
  (`governance/readiness-reason-codes.v1.json`, like `no_documents`); `renderHistoryLocked` now words
  itself + its remedy from `reasonFor('conversations.locked')` instead of hardcoded text — the locked
  affordance speaks the one CAUSE_ROWS vocabulary. Gate + typecheck green; trigger unchanged (the
  validated locked-flow still fires). Scoped out: the full `projectAvailability` tab-gating (the
  transcript-pane notice is the right UX; tab-gating is a refinement).

## Phase 4 — encrypted export (#7) ✅ (validated); import = follow-on
- `ConversationBackupController` + `POST .../export` (requires UNLOCKED; **POST, not GET — Fix 1**, so the
  session token is enforced): reads **all 3 AUTHORED stores** (conversations, memories, **agent-runs** —
  **Fix 2**) decrypted → bundles → **seals with the live DEK** via `StoreCipher` → portable container
  `{format:"justsearch-backup/v1", keystore:<wrapped-DEK record>, data:"JSEv1:…"}`, streamed
  `Content-Disposition: attachment`. Reuses the envelope entirely; the bundled keystore makes it
  offline-decryptable with the passphrase/recovery key. **FE:** "Export encrypted backup" button
  (`doFetch` POST → blob → anchor-download). *Live-proof (post-fix):* `POST` returns 200 + attachment;
  `GET` now 404s; the container was **decrypted in-browser via WebCrypto** (passphrase→PBKDF2 KEK→unwrap
  DEK→open bundle) and the plaintext bundle carries `{conversations, memories, agentRuns}` — proving both
  the all-3-stores completeness AND the design's offline-decryptable claim end-to-end. **Recorded
  follow-on:** import (write-back + collision policy — the net-new fiddly half) and the
  export-first-uninstall step on the delete ceremony.

## Phase 5 — auto-lock (#10) ✅ (validated)
- `utils/autoLock.ts` — app-wide idle watcher started/stopped in `Shell` connected/disconnected
  (outlives surface navigation); reads the timeout from `localStorage['enc-autolock-min']` each tick,
  locks only when idle AND `unlocked`. Settings management surface gets an **Auto-lock** select
  (Off/5/15/30 min). Unit-tested with fake timers + injectable clock (locks when idle+unlocked; no-op
  when off / already-locked / before-timeout). *Live-proof:* the select renders + persists, default
  **Off**. The default is **intentionally Off** (opt-in; user's decision) — NOT the design's suggested
  15-min, so a freshly-encrypted store never surprises the user by auto-locking. Lock-on-suspend stays a
  follow-on (no reusable resume event — F-probe).

## Phase 6 — honest disclosure (#12) ✅ (validated)
- The Health "Data protection" card gains a "What this protects" footer, **projected from the typed
  status** (`convState`): names that chat/memories/agent-runs are passphrase-encrypted (and whether
  readable-now/locked), and that the **index is NOT passphrase-encrypted** (FDE only, rebuilds from
  files) — so the surface never over-claims. *Live-proof:* the disclosure renders + reflects the conv
  state.

## Phase 7 — biometric / WebAuthn-PRF (#8) — DEFERRED at the validation boundary
Not implemented. This is the one phase that **cannot meet the success criterion** ("validate the real UI
through the browser"): the real Windows-Hello ceremony (OS dialog) and the embedded-WebView2 PRF
question are not automatable here, and building the PRF factor-wrap crypto without an end-to-end
round-trip would ship unvalidatable crypto (contra verify-your-work). Per the design's own framing it is
opportunistic + non-blocking; the precise spec (3rd PRF factor-wrap on `KeystoreRecord` +
`add-prf-factor`/`unlock-with-prf-kek` ops + the FE capability probe gating an "Add Windows Hello"
affordance, passphrase staying the recoverable primary) stands ready in Phase 7 of the plan + the
research-refresh section. **This is the reported "cannot continue" boundary.**

## Verification (all green)
`./gradlew build -x test` (class-size pass) + `EncryptionEnvelopeTest`/`DataKeyManagerTest`/
`StatusRecordSchemaTest`; FE `typecheck` + 8 ui-web gates (controls-a11y, surface-task-state-retention,
color-tokens, a11y-closure, presentation-purity, observed-state-collapse, readiness-reason-codes,
wire-schema-types-regen) + 43 unit tests across the touched files.

## Post-review fixes (2026-06-23)
A critical-analysis pass found a security defect, a requirement gap, a design mismatch, and two unproven
claims. Fixed:
- **Fix 1 — export token-protection.** The export was an unauthenticated `GET` (ApiSecurityFilters always
  allows GET), exposing the offline-brute-forceable vault to any loopback caller. Now `POST` (token-gated
  in prod). *Validated:* `GET` 404s, `POST` 200s.
- **Fix 2 — all 3 AUTHORED stores.** The export omitted agent-runs; now bundles
  `{conversations, memories, agentRuns}` (meta snapshot + sealed event ledger via `RunEventStore`).
  *Validated:* in-browser WebCrypto decrypt of the container shows all three sections.
- **Fix 3 — persistent recovery-unsaved banner.** The save-gate was advisory; a localStorage-backed
  `encRecoveryUnsaved` flag now drives a management-surface warning banner until acknowledged. *Validated:*
  banner survives navigation; flag↔banner coupling confirmed.
- **Re-validation findings.** Phase 1B's live-reactive claim **failed** re-validation — `HealthSurface.status`
  was frozen post-mount (a shared `aiStateStore`-poll behavior, surface-wide, observed under a reconnecting
  stack); corrected above + logged. Phase 3's locked-notice live display was not re-shown (needs a stable
  stack + a model-backed conversation) — code/gate-validated only.

## Structural realization — the class as the one load-bearing authority (Phases A–E, 2026-06-23)

A conceptual re-review found the shipped features delivered the design's *features* but, in two places,
as standalone pieces rather than projections of the one authority (the tempdoc's whole thesis). These
phases realize the thesis and complete the design-warranted obligations. All landed build-green; the
through-line table is now true (confidentiality + backup + protect-on-delete all *project from the class*,
the register *governs* it).

- **A — the spine made load-bearing (#1) ✅.** New `StoreCatalog` (the ONE declared classification:
  name→class→framing) + a `StoreDescriptor` registry on `HeadAssembly` (each AUTHORED store registers a
  `BackupSource`/`BackupSink` at construction; conversations register cross-module from
  `ConversationApiAssembly`). Cipher selection now reads `StoreCatalog.X.recoverability()` (not a bare
  `AUTHORED` literal); the export *iterates the list* (`authoredStores()`) with no per-store code. *Live:*
  WebCrypto-decrypt of the export shows all three sections, now keyed by the catalog dirNames; unit-tested.
- **B — the `at-rest.authored` condition (#2) ✅.** New `ConversationProtectionHealthTap` (mirror
  `AtRestHealthTap`), wired in `CoreApiAssembly` + `accept()`-ed in `StatusLifecycleHandler`. *Correction to
  the earlier review:* the FLOOR card reads the `atRestProtection` **wire field**, so the LAYER
  `conversationProtection` wire field was already consistent — the genuine gap was only the missing
  *condition*, now added.
  - **Retune (2026-06-23):** the condition was first wired to fire on LOCKED (INFO), but a *locked* store
    is the *secure* state. Per FE altitude-3 it now fires **only on a real gap** — the AUTHORED stores have
    NO at-rest protection at all: `DataKeyManager.state()==NOT_CONFIGURED` **AND**
    `DiskEncryptionProbe.current()==NOT_ENCRYPTED` (UNKNOWN FDE never warns) — at **WARNING** severity with
    two-remedy guidance (set up chat encryption, or enable device encryption). It **clears the moment
    app-encryption is configured, even with FDE off**, making the LAYER's value legible. The locked-state
    "unlock to read" legibility stays in the chat affordance (#3), unchanged. Unit-tested
    (`ConversationProtectionHealthTapTest`: asserts when unprotected, clears otherwise).
  - **Live-validation status:** the backend *asserts* the condition correctly (verified: unit test + the
    live inputs `not_configured`+`NOT_ENCRYPTED` make the supplier return true on each `/api/status`). The
    Health "Recent events" *display* could **not** be re-shown this session: the
    `/api/health/events/stream` SSE delivered **nothing for ANY condition** (the FLOOR's `at-rest.unprotected`
    included) across two fresh stacks — a **pre-existing, app-wide event-delivery failure** (broadens the
    logged `aiStateStore` frozen-status finding), not the retune. Logged to the inbox.
- **C — protect-on-delete ✅.** The delete ceremony offers **"Export encrypted backup first"** (the
  list-driven export) when encryption is unlocked, with an honest "encrypted and can't be rebuilt" warning.
  *Live:* the affordance renders in the confirm state.
- **D — the governed register (#5) ✅.** `governance/store-recoverability.v1.json` +
  `scripts/ci/check-store-recoverability.mjs` (PARITY: StoreCatalog↔register; NO-HARDCODE: every
  `storeCipher(...)` reads the catalog — a bare literal is how an unclassified store would slip a cipher).
  Wired into `ci.yml` + the pre-merge table; gate green on the tree, the test proves it fails on
  drift/hardcode.
- **E — import write-back ✅.** `POST .../import` (token-gated) decrypts the container with ITS OWN
  keystore (an ad-hoc `DataKeyState` over the backup's unwrapped DEK), then restores via the descriptor
  **sinks** (conversations + memories full, skip-existing; agent-runs restore the run meta — event-ledger
  replay is lossy via the append APIs, noted). FE: an Import file+passphrase flow. *Live:* a WebCrypto-
  crafted container imported → `{conversations:1, memories:1}` restored, and a re-export confirms the
  content **persisted**.

**Decisions honored:** KDF Argon2id deferred (PBKDF2-600k OWASP-compliant; BouncyCastle not on the
classpath); import included. **Verification:** `build -x test` (class-size pass) + the new
`StoreCatalogTest` / `ConversationProtectionHealthTapTest` / `check-store-recoverability.test.mjs`; FE
typecheck + the ui-web gate set + 22 unit tests.

## Deferred / follow-on register (carried, not lost)
Argon2id-via-BC swap (user-deferred) · setup-ceremony `createMachine` conformance · `projectAvailability`
tab-gating · ~~agent-run event-ledger replay on import (meta-only today)~~ DONE 2026-06-23 (appendRawEvents) · lock-on-suspend · shared
`aiStateStore` live-status propagation (HealthSurface frozen-post-mount — pre-existing, app-wide) ·
config-quality FDE status (needs elevation) · turn-off/disable-encryption (bulk plaintext rewrite) ·
Phase 7 biometric/PRF (validation-limited).

---

# Post-implementation future directions (research pass, 2026-06-23)

> A "what could we do with this now?" research pass, taken **after** Phases A–E + the retune shipped. Pure
> ideation (no code) — web-researched against 2026 best practice + analogous systems, ranked, not
> committed. The earlier `# Future directions` menu (2026-06-22) is now mostly **shipped**; this looks
> *beyond* it. Nothing here is urgent (no users, not in production).

## What the field VALIDATES (confidence, no work)
The implemented design is not bespoke — it matches the industry-standard shapes, which is reassuring:
- **The DERIVED/AUTHORED classification == iOS Data Protection classes.** iOS files carry one of four
  protection *classes* keyed on the lock lifecycle (Complete / Unless-Open / Until-First-Auth / None), each
  a per-file key wrapped by a *class* key wrapped by passcode+UID. Our 2-class spine is the same idea; our
  current "unlock once, stays unlocked until lock/idle" is precisely iOS **Class C** (Protected Until First
  User Authentication, the iOS default), and DERIVED ≈ Class D / None (FDE only). [Apple Platform Security]
- **The envelope (random DEK wrapped by a passphrase/recovery KEK) == the cloud-KMS / Cryptomator standard.**
  Generate the DEK locally, wrap it, never persist it raw — textbook envelope encryption. [Google KMS]
- **PBKDF2-600k is OWASP-compliant** — but it is the *weakest acceptable* tier; OWASP-2026 ranks
  **Argon2id (≥19 MiB, t=2, p=1)** first, then scrypt (2^17), then PBKDF2 (it is the only non-memory-hard
  option, so cheapest to brute-force on GPUs). Cryptomator uses scrypt for exactly this reason. [OWASP]
- **The factor-agnostic envelope + biometric-as-a-factor == how Bitwarden/1Password unlock vaults with a
  passkey today**, and **WebAuthn-PRF matured in 2026**: Android robust, Apple solid via iCloud Keychain,
  and **Windows Hello began returning PRF values after the Feb-2026 update**. *But (targeted research,
  2026-06-23) the OS having PRF is not enough for us:* **WebView2 does not expose WebAuthn at all**, so the
  webview-side PRF path is dead — biometric custody must go **native-in-the-shell** (windows-rs /
  `AuthenticationServices`, handing the KEK to Head over IPC, à la Vault12's `electron-webauthn-mac`). See
  the Research-refresh resolution above. [Corbado, Bitwarden, MS WebView2 docs, Tauri #7926, Vault12]

## The menu (ranked within each lens)

### Extend — what the foundation cheaply unlocks (the marquee)
1. **A key hierarchy: one master KEK → per-conversation (or per-store) DEKs.** Today there is a *single*
   DEK for all AUTHORED data. Generalizing to per-record DEKs (the iOS per-file-key model, the cloud-KMS
   per-record model, Obsidian's per-note keys — "the most practical approach") is the **single
   highest-leverage extension**, because it unlocks three features at once:
   - **(a) Granular crypto-shredding** — deleting one conversation = destroying *its* DEK = the bytes are
     instantly, *mathematically* unrecoverable, no file-scrubbing. This is GDPR/ICO/CNIL-recognized erasure
     (needs AES-256 ✓ + irreversible + auditable key destruction). It is the natural endgame of
     protect-on-delete (Phase C).
   - **(b) Retention / auto-expiry** — "forget conversations older than N days" becomes destroy-the-sub-key,
     not a bulk rewrite.
   - **(c) Selective + efficient export/sync** — export/sync one conversation; sync only changed ciphertext.
   - *Caveat the research is explicit about:* crypto-shred only erases what *we hold* — an already-**exported**
     backup carries its own wrapped DEK and is NOT shredded. The UX must say so honestly. [key-hierarchy +
     crypto-shredding sources]
2. **Crypto-shred as the delete/panic primitive (even without the full hierarchy).** "Destroy all chat
   history now" = destroy the master DEK = instant irreversible wipe (vs today's export-first + uniform file
   delete). iOS's Class-D "effaceable storage" is exactly this. Cheap; honest caveat as in 1(a).
3. **E2EE multi-device sync — the export container is the seed.** The *same* envelope synced to the user's
   own cloud/peer, decryptable only with the passphrase (the Obsidian-Sync model: "encrypted using your
   backup password as the key"). The key hierarchy (1) makes incremental sync efficient. Large feature, but
   the crypto foundation already exists. [Obsidian, Cossack Labs]
4. **Argon2id (or scrypt) KDF** — the OWASP-confirmed hardening; needs BouncyCastle (the only blocker, since
   the JDK ships neither). The `kdf` header agility is already in place → a clean drop-in.
5. **PRF biometric unlock (Phase 7)** — ecosystem caught up in 2026; the factor-agnostic envelope is ready.

### New UX
1. **A dedicated "Security & Privacy" surface** — today the at-rest story is split across Settings (the
   control) and Health (FDE status + conditions). Consolidating control + posture + backup/import + auto-lock
   into one home is the iOS "Privacy & Security" model and makes the whole built story legible at once.
2. **Protection-level choice (iOS-inspired)** — surface auto-lock as a clear *policy* pick: "lock the moment
   I quit" (≈ Class A) vs "stay unlocked until I lock" (≈ Class C, today) vs idle-timeout. Turns an abstract
   timer into a posture choice.
3. **Word-list (BIP39-style) recovery key** instead of hex — human-friendly to transcribe + verify (the
   wallet/Cryptomator norm; the FE design already wanted it).
4. **A crypto-shred "panic" affordance** (rides Extend-2) + **backup-health nudges** ("last backup N days
   ago"), making protect-on-delete + backup proactive rather than only on-uninstall.
5. **The ambient at-rest chip (altitude 1)** — still optional, but a glanceable Encrypted / FDE-only /
   Unprotected posture indicator.

### Polish / Simplify
1. **~~Fix the Health-events delivery layer~~ — SUPERSEDED by the 2026-06-23 de-risk pass.** The at-rest
   conditions appeared not to display, but isolation proved the **backend SSE streams correctly** (curl with
   `Accept: text/event-stream` delivers the conditions incl. the retuned `at-rest.authored` WARNING); the
   empty `events` array was a **CDP-automation-harness artifact**, not a product defect. The legibility works
   in production + a real browser → **no fix needed here.** (Robustifying SSE *under CDP automation* is a
   dev-tooling nicety, not 629 work.) See "De-risk findings (2026-06-23)".
2. **Seal structure, not just content (or document the limit).** At-rest sealing covers content
   (per-line/whole-file) but not *filenames / session-id dir names / file counts / mtimes* — an attacker
   with raw disk access (no FDE) still learns *how many* conversations and *when*. Cryptomator encrypts even
   filenames. Either accept it (FDE covers structure → it's the DERIVED tier's job) as a documented decision,
   or seal the structure.
3. ~~**Faithful agent-run import** (raw event-write API → full ledger replay; today meta-only)~~ **DONE
   (2026-06-23)** — `RunEventStore.appendRawEvents` + the wired sink; live A/B validated. ·
   **signed/versioned container config** (Cryptomator's vault config is a signed JWT — tamper-proof cipher
   choice; ours is a plain `format` string) · **blocking recovery-save ceremony** (vs today's advisory
   banner).

### Strategic
- **The store-classification is now a small platform.** With the class load-bearing, *more* obligations can
  project from the one `StoreCatalog`: retention/expiry, redaction (ties to 297), sync, selective-shred — all
  the same "class selects what, mechanism does how" shape §Reach predicted. The key hierarchy (Extend-1) is
  the substrate that makes the shred/retention/sync trio real.

## If forced to pick three
1. **Key hierarchy → granular crypto-shredding** (Extend 1+2) — the marquee: an industry-standard
   generalization that turns "delete" into instant, irreversible, per-conversation erasure and unlocks
   retention + selective export/sync from one change.
2. **A unified "Security & Privacy" surface** (UX 1) — the consolidation that makes everything already built
   legible and gives shred/backup/auto-lock/recovery a single honest home.
3. ~~Repair the event-delivery legibility layer~~ — **removed: the de-risk pass proved it isn't broken**
   (CDP-automation artifact). Replace with **Argon2id KDF** (Extend 4) — now the cheapest *real* win: BC is
   already in the build (`1.81.1`), the `kdf` header is a clean drop-in, and it lifts password hashing from
   OWASP's weakest tier to its first choice.

## Sources
- Apple Platform Security — Data Protection classes: https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web
- OWASP Password Storage Cheat Sheet (Argon2id/scrypt/PBKDF2): https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- Crypto-shredding (recognition + pitfalls): https://en.wikipedia.org/wiki/Crypto-shredding · https://www.thoughtworks.com/radar/techniques/crypto-shredding · https://secupi.com/crypto-shredding-is-not-nirvana-for-right-of-erasure-or-rtbf-compliance/
- Envelope / per-record DEK key hierarchy: https://docs.cloud.google.com/kms/docs/envelope-encryption
- WebAuthn-PRF 2026 maturity: https://www.corbado.com/blog/passkeys-prf-webauthn · https://bitwarden.com/blog/prf-webauthn-and-its-role-in-passkeys/
- Embedded-webview PRF resolution (2026-06-23): WebView2 has no WebAuthn (https://learn.microsoft.com/en-us/answers/questions/4035587/windows-hello-support-for-webauthn-prf-extension) · Tauri passkey blocker (https://github.com/tauri-apps/tauri/issues/7926) · native-shell pattern, Vault12 (https://github.com/vault12/electron-webauthn-mac)
- Cryptomator (scrypt, filename encryption, signed vault config): https://cryptomator.org/
- Obsidian local-first E2EE sync model: https://openalternative.co/alternatives/obsidian

---

# Long-term design — the data lifecycle as projections of the recoverability class (theorized 2026-06-23)

> A step-back design theorization for the *ideas + remaining work* above (crypto-shred, retention, sync,
> the key hierarchy). It is **general, not implementation-level**, and its scope is deliberately bounded by
> what the present problem actually requires. It supersedes the earlier "correct shape for future ideas"
> framing (2026-06-22) by unifying it with the adjacent designs (628/627) into one shape.

## The present-problem judgment (why this is mostly *recording*, not *building*)

629 (data-at-rest encryption) is **shipped and conforms** to a seam that already exists three times over
(below). The ideas above (erasure/crypto-shred, retention/expiry, multi-device sync) are not new
subsystems — they are the **same seam extended** with more obligations. Crucially, **none is required
now**: adjacent tempdoc 632 (go-public legal) confirms there is **no near-term right-to-erasure / retention
obligation** (the EU CRA is product-compliance, obligations land 2027; no GDPR data-subject duty in the
initial release), and the granular features (per-conversation shred/retention/selective-sync) do not yet
exist. So the correct long-term design **records the shape and the triggers**; it builds no new structure
today. New structure (the key hierarchy) is warranted *exactly* when the first granular lifecycle
obligation is committed — and not before.

## What already exists — conform, do not fork

The "recoverability class is the authority; obligations are projections" shape is **not unique to 629**; it
is a recurring seam:

- **§Reach (629), realized A–E:** `StoreCatalog` (the one classification) + the `StoreDescriptor` list +
  the governed `store-recoverability` register + gate. Confidentiality, backup, and protect-on-delete all
  project from it.
- **628 (durability):** the *"reconstruct from source-of-truth"* invariant — *"any derived store whose
  source-of-truth is still present should recover by reconstruction, never serve the damaged copy."* This
  is the **DERIVED tier's lifecycle, already designed.** (`IndexRecoveryPolicy` still hardcodes the class
  rather than reading `StoreCatalog` — the §Reach-recorded violation, a 628-side migration not required by
  629.)
- **627 (process supervision):** the *isomorphic architecture* — "declared policy + pure decision function
  + governance-registered contract + test guards" (`SupervisionPolicy`/`SupervisionDecision` +
  `supervision-contract.v1.json`). **629's Phase-D register + gate is literally this shape**, so 629 already
  conforms; the future lifecycle obligations reuse it (a declared per-store policy + a pure decision),
  never a parallel mechanism.
- **587 (host sensing):** the confidence-carrying FDE projection (the FLOOR). Also conformed.

So the design extends an established seam. The job is to state the *full* shape it implies and bound it.

## The design — two axes, one class

**Axis 1 — obligations (horizontal): the whole data lifecycle is a projection of the class.** The
through-line table, extended with the now-visible obligations:

| Lifecycle obligation | Projects from | Owner / status |
|---|---|---|
| Confidentiality (protect) | recoverability class | 629 — DONE |
| Durability (recover) | recoverability class | **628** — DONE (hardcoded; §Reach violation) |
| Backup (export + import) | recoverability class | 629 — DONE |
| Protect-on-delete | recoverability class | 629 — DONE (uniform byte-wipe) |
| **Erasure / crypto-shred** | recoverability class | future (no obligation yet) |
| **Retention / expiry** | recoverability class | future |
| **Multi-device sync** | recoverability class | future |

Each obligation is a per-store **capability** the descriptor exposes (`source`/`sink` today; a `shred` /
`expire` capability when those land), and the class selects *which* stores it applies to. The two tiers
own complementary halves of the *same* lifecycle: **DERIVED-tier recovery/delete is owned by 628**
(handle = the source files), **AUTHORED-tier recovery/delete is owned by 629** (handle = the encryption
key). One class, both tiers, one register.

**Axis 2 — crypto substrate (vertical): single DEK → key hierarchy, only when granularity is committed.**
Today: one DEK per install — sufficient for confidentiality and for *all-or-nothing* backup/shred. The
future substrate is a **key hierarchy (master KEK → per-store, then per-record DEKs)** — the iOS Data
Protection / cloud-KMS / Obsidian-per-note standard — which is what makes the lifecycle obligations
**granular** (shred / retain / selectively-sync *one* conversation by operating on *its* sub-key). It is a
**generalization of the existing envelope** (one DEK becomes many DEKs under the same KEK), not a parallel
crypto path. **Build it when the first granular obligation is committed; the single DEK is enough until
then.**

## What the present problem requires NOW
- **No new structure.** Record the shape + the triggers (above).
- The one *present* defect in the built legibility — the at-rest conditions (`at-rest.unprotected`,
  `at-rest.authored`) can't reach the user because the shared **event-delivery layer (Health-events SSE +
  status poll) is broken** — is a **cross-cutting infrastructure fix, not 629 structure** (logged, app-wide;
  the conditions are asserted correctly, only the delivery is dark).

---

## Reach — the principle this reveals

**Is it an instance of an existing principle?** Yes. It is the §Reach "class-as-authority, obligations-as-
projections" principle, which is *also* 628's reconstruct-from-source invariant and 627's declared-policy
architecture. 629 conforms to all three; the lifecycle obligations are more consumers, not a new shape.

**Does it reveal a principle that reaches further? Yes — name it: the _recoverability handle_.**

> *Each store's recoverability class determines its **lifecycle handle**, and every lifecycle obligation
> (recover, delete, retain, back up) is an operation on the **handle**, not on the bytes. For a DERIVED
> store the handle is its **source-of-truth** (recover = rebuild from source; delete-of-source ⇒
> derived-empties). For an AUTHORED store the handle is its **encryption key** (recover = recovery-key;
> delete = destroy the key — crypto-shred; retain = destroy old keys). Erasure is the absence of a handle,
> not the absence of bytes.*

This subsumes both 628 (source-as-handle) and 629 (key-as-handle) under one invariant: the class picks the
handle; the obligations act on the handle.

- **Where it would apply beyond 629:** any store with a recoverability class — every current `dataDir`
  store, any future encrypted store, the index/jobs.db (DERIVED, handle = source), and the 297 redaction
  concern (a projection of the same class). It is the encryption-tier complement of 628's invariant.
- **Existing violations (record, don't fix unless the present problem needs it):**
  1. **Protect-on-delete uniform byte-wipe** (629 Phase C) operates on *bytes*, not the *handle* — the
     handle-correct primitive for the AUTHORED tier is *destroy the DEK* (crypto-shred). Acceptable today
     (single DEK, the wipe works), but it is a violation of the principle the moment a key hierarchy exists.
  2. **628's `IndexRecoveryPolicy` hardcodes the class** instead of reading `StoreCatalog` (already
     recorded). The handle (source-of-truth) is right; the *classification read* is forked.
- **Do not build the generalized structure now.** No granular lifecycle obligation exists; 632 confirms no
  near-term erasure/retention duty. The principle is captured and scoped; building the key hierarchy /
  per-store policy table is the work that becomes warranted when the first granular obligation lands —
  recognizing the principle is deliberately separated from building its structure, to avoid premature
  abstraction.

---

# De-risk findings — confidence pass on the deferred work (2026-06-23)

> A pre-implementation confidence pass (investigation + read-only experiments, **no feature code**) to
> reduce surprises in the deferred/future work. Each finding corrects or sharpens a recorded assumption.

- **Event-delivery legibility — NOT a product defect (corrects a recorded assumption).** The at-rest
  conditions appeared to "never display." Isolation: `curl -N -H 'Accept: text/event-stream'` against the
  backend SSE (`/api/health/events/stream`) **streams perfectly** — the snapshot frame carries the live
  conditions **including the retuned `at-rest.authored` at `severity:WARNING, reason:AuthoredUnprotected`**
  (so the retune is now also backend-validated end-to-end). The FE sends the correct `Accept` header
  (`HealthSurface.startEventStream`), and the Vite dev proxy forwards headers + pipes the stream. The empty
  `events` array only ever occurred **under the claude-in-chrome CDP automation harness** (Phase B saw 5
  events under the *same* harness → flaky), and the proxy code already documents SSE misbehaving "under CDP
  automation" (tempdoc 545). **Conclusion:** the legibility we built works in production (Tauri → direct
  loopback, no proxy/CDP) and in a real dev browser; the "broken event-delivery" was the *automation
  harness*, not the product. Confidence: **high** that no legibility rework is needed. (The earlier
  "logged app-wide" framing was over-pessimistic — superseded by this.)
- **Key hierarchy — bigger than "a generalization" (sharpens the long-term design).** The crypto stack is
  **uniformly single-DEK-assuming at every layer** — `KeystoreRecord` (scalar `wrappedDek`/`recoveryWrappedDek`),
  `EncryptionEnvelope` (scalar `byte[]` everywhere), `StoreCipher`/`DataKeyState` (one `dek()`),
  `DataKeyManager` (one `volatile byte[] dek`). So a master-KEK→per-record-DEK hierarchy is a **cross-layer
  change + a keystore-format version bump**, not a local refactor. *Mitigant:* the migration can be
  **additive/lazy** (the existing DEK becomes the master; new records mint sub-keys; old data stays openable
  under the master) → **no bulk re-encrypt required.** Confidence: **medium** — well-understood scope, but a
  real multi-layer change; not a quick add.
- **Argon2id / BouncyCastle — clean (raises confidence).** BC is **already on the build's resolved graph**
  (`bcprov-jdk18on:1.81.1` in indexer-worker/worker-services/app-launcher/system-tests lockfiles). Adding it
  to `app-services` is a **version-aligned, zero-conflict** add + a lockfile regen. The `kdf` header agility
  already exists. Confidence: **high** — the dep-add risk I'd flagged is largely gone.
- **Native-shell biometric (Phase 7) — buildable, but net-new + untestable.** `windows-rs` **does expose**
  the WebAuthn PRF API (`WebAuthNAuthenticatorGetAssertion`, API v8 HMAC-Secret), so the native path is
  technically buildable. **But** there is **no inbound shell→Head secret channel today** (only spawn-time
  args/env + the Head-written manifest + the token-auth HTTP API), so it needs: a new `/unlock-with-kek`
  Head endpoint + an N-factor keystore wrap + net-new Rust unsafe FFI — and it stays Windows-only and
  OS-dialog-validation-limited. Confidence: **low–medium** — feasible, but the most net-new and least
  testable of the set; correctly stays deferred.

---

# Implemented — Argon2id KDF + unified Security & Privacy surface (2026-06-23)

The two warranted, extend-existing items from the de-risk pass shipped this round (the granular-obligation
work — key hierarchy / crypto-shred / retention / sync — and native biometric stay deferred-by-design).

**Part 1 — Argon2id KDF (OWASP first-tier; replaces PBKDF2 for new keystores).** `EncryptionEnvelope`
now derives the KEK with **Argon2id** (RFC-9106, m=64 MiB / t=3 / p=1) via BouncyCastle
(`bcprov-jdk18on:1.81.1`, version-aligned with the worker modules — a clean add). The params travel
*inside* a self-describing `kdf` header (`ARGON2ID;v=19;m=65536;t=3;p=1`) so the export container stays
offline-decryptable. Legacy `PBKDF2-HMAC-SHA256` keystores remain unlockable via the legacy branch
(back-compat by construction); because the single header governs both wraps, only `setup()` adopts
Argon2id and passphrase/recovery rotation preserves the record's own KDF. Tests: Argon2id round-trip,
legacy-PBKDF2 unlock, unknown-KDF fail-closed (green). **Live-verified end-to-end**: a real in-app setup
wrote `"kdf":"ARGON2ID;v=19;m=65536;t=3;p=1"` to disk (Argon2id ran on the real backend classpath — it
would have thrown if bcprov were absent).

**Part 2 — unified "Security & Privacy" surface (the realized UX-1).** A new RAIL surface
`core.security-surface` (`SecuritySurface.ts`) now homes the whole at-rest story: the read-only
data-protection status (shared `renderAtRestCard`, extracted from `HealthSurface` so both render the
identical card) above the chat-encryption control + recovery + encrypted backup/import + auto-lock —
**moved verbatim out of Settings** (the block was only coupled to surface-generic deps). Settings now shows
a pointer row; the delete-confirm's export-first affordance points here too. Registered in the Java
`CoreSurfaceCatalog` (18 → 19), the FE `CorePlugin`/lazy registry, i18n, and a real `jseval ui-shot
security` step. **Live browser validation (all green)**: the surface mounts in the rail with its i18n
label; setup → recovery-save gate → unlocked management (Regenerate / Change passphrase / Export / Import /
Lock / Auto-lock) all work; the shared card reactively shows "Encrypted (passphrase) · unlocked" in **both**
Security and Health; Settings no longer hosts the control and shows the pointer.

Verification tiers passed: FE typecheck + 3394 unit tests; the ui-web surface/a11y/token/contrast gate set
for the new surface; `app-observability` + `app-services` encryption tests; live browser + on-disk
keystore. (Unrelated reds in the shared checkout — `theme-token-closure` on `RecentsMenu.ts`,
`accent-as-text` on `ActionLedgerView.ts`, and the `core.reconcile-root` operation-handler tests — are
neighbour WIP, not in this change's diff.)

**Follow-up — faithful agent-run import (2026-06-23).** The encrypted backup exported each agent run's full
event ledger but the import sink restored META ONLY (`writeRunMeta`) — a restore gave back empty
agent-session shells (silent data loss). New `RunEventStore.appendRawEvents(sessionId, records)` replays the
ledger VERBATIM (preserving original timestamps; no listener re-fire — the live `appendEvent` re-stamps
`now()` + fires projectors, wrong for replay; coercion lives in `appendRawEvents` so HeadAssembly growth is
minimal, declared-growth changeset). The agent-run `BackupSink` now calls it. Tests: `RunEventStoreTest`
(verbatim timestamps/types, no listener fire, faithful round-trip **through JSON** = the container path,
empty/null no-op). **Live A/B validated**: a stale-jar run accidentally reproduced the old meta-only bug
(0 events restored); after `installDist`, the fresh backend restored the run **with its events.ndjson** (2
sealed lines) via the real export→delete→import cycle on the user-facing endpoints. Closes the
deferred-register line. With this, 629's warranted work is complete; the remaining deferred set (key
hierarchy / crypto-shred / retention / sync, native biometric, word-list recovery, signed container config,
lock-on-suspend, config-quality FDE, turn-off-encryption, 628 `IndexRecoveryPolicy` migration,
structure-leak) each stays gated on a trigger that has not fired.

---

# Status: CLOSED (2026-06-23)

Tempdoc closed. The design is implemented and validated end-to-end through the real UI; the one bug surfaced
during validation (the agent-history search scope dropped by `KnowledgeSearchController`) is fixed, tested,
and live-verified (commits `dd125d12a` / `93d53f18e`). Restored agent runs are searchable
(`reindexRestoredRun`, `7ca902626`). What remains is explicitly **out of scope for this tempdoc** and carried
as named follow-ups, not unfinished work: **Open issues #3** (a general `AgentHistoryIndexer`
backfill-from-ledger — 585's domain) and **#4** (canonical docs / API-map rows), plus the documentational
**Future directions** research (D1-D5) — all options, no obligations. Phase 7 (biometric/WebAuthn-PRF, task
#25) stays deferred and is reframed by the research as an OS-keychain unlock (D1). Nothing below is unfinished
629 work.

---

# Status: IMPLEMENTED (conceptual closure, 2026-06-23)

A full conceptual re-read against §0 / §1 (FLOOR+LAYER) / §2 (scope) / §Reach / the long-term through-line
confirms the implementation **realizes the tempdoc's central design**: the recoverability class is now the
load-bearing authority (StoreCatalog + the one store list + the governed register), and confidentiality,
backup/import, and protect-on-delete all *project* from it; the FLOOR's honest typed at-rest status ships
(conditions + the Data-protection card, index FDE-only), the LAYER's passphrase-gated AEAD on AUTHORED stores
ships (Argon2id envelope + recovery key + save-gate + auto-lock), and the agent-run backup is now faithful.
Scope discipline holds (no index crypto, no host-capability framework, no key hierarchy), and every deferral
matches a tempdoc-stated trigger that has not fired.

**Discovered limitations / indirect follow-ups (not regressions; recorded for whoever picks them up):**
- **~~Restored agent runs are viewable but not yet *searchable*~~ — CLOSED (2026-06-23).**
  `AgentHistoryIndexer.reindexRestoredRun` now replays a restored run's terminal `done`/`error` event
  through the existing indexing path, and the agent-run `BackupSink` calls it after `appendRawEvents`. So a
  restored run is indexed into the searchable `agent-history` collection **identically to a live run** (the
  live listener is correctly not fired on import; this is the targeted re-index, not a broad rebuild).
  Tests: `AgentHistoryIndexerTest` (transcript written from a restored terminal event; nothing without one).
  **Live-verified**: a real export → delete → import cycle ran `reindexRestoredRun`, wrote the transcript,
  and the **Worker indexed it into `agent-history`** (`readModifyWrite …agent-history/<sid>.md`). The
  query-retrieval tier (a scoped search returning the run) is the existing 585 search path — restored and
  live transcripts are now indexed the same way, so 585's search retrieves both identically. Remaining
  follow-up (585's domain, deferred): a general `AgentHistoryIndexer` backfill-from-ledger for runs that
  failed to index live.
- **Documentation:** the shipped feature has **no canonical doc** (nothing under `docs/explanation`,
  `docs/reference`, `docs/how-to`, `docs/decisions`) and the encryption endpoints
  (`/api/conversations/encryption/*`, `export`, `import`) are **absent from `docs/reference/api-contract-map.md`**.
  A canonical explanation of the DERIVED/AUTHORED at-rest model (+ optionally an ADR), a how-to (set up
  encryption / back up / restore), and the API-map rows are the durable-record follow-up now that the tempdoc
  is the design record rather than the only record.

---

# Open issues — investigated 2026-06-23 (surfaced while validating restored-run searchability)

**1. ✅ RESOLVED (2026-06-23) — The agent-history search SCOPE was dropped by the HTTP controller.** 585's
"search your agent history" was non-functional via the API (restored *and live* runs indexed but not
retrievable). Verified by direct read: `KnowledgeSearchController` parsed every filter EXCEPT `collection`,
so `filters.collection` defaulted to empty and the worker's default branch fired
`MUST_NOT collection:agent-history` (`QueryFilterBuilder.java:96-108` / `:238-239`), *excluding* agent-history
from every search. Everything downstream was already correct + unit-tested — the wire record
(`KnowledgeSearchRequest.java:50`), the mapper (`SearchRequestMapper.java:44-47`), the proto
(`indexing.proto:259`), the worker scope + ingest tag (`IndexingDocumentOps.java:144`),
`QueryFilterBuilderTest:235-264`. Only the controller wiring was missing — a gap no test could catch while
the parsing was inline. **Fix (commit `dd125d12a`):** extracted the inline parsing into a testable
`KnowledgeSearchController.parseFilters(...)` and wired `.collection(...)`. **Live-verified end-to-end** on a
real export → delete → import: the agent-history-scoped search returned the restored transcript by both the
unique marker (`count:1, restored-searchable-run.md`) and by content ("pelicans"), while the *default* scope
correctly returned `count:0` for the marker (the default-exclude still holds — no leakage). So 585's "search
your agent history" works again, and 629's `reindexRestoredRun` is now proven retrievable, not just indexed.
Pre-existing 585 bug; surfaced and fixed by 629's searchability validation.

**2. ✅ RESOLVED (2026-06-23) — No test covered the controller's collection-scope extraction.** Coverage was
only the Worker-level `QueryFilterBuilderTest` (query construction) — nothing asserted the controller
*extracts* the scope, which is exactly why #1 shipped undetected. The `parseFilters` extraction created the
testable seam; **`KnowledgeSearchControllerFiltersTest` (commit `dd125d12a`)** now locks it: a
`{"collection":["agent-history"]}` filter map parses to `filters.collection() == ["agent-history"]`, and an
absent key yields the empty (default-exclude) scope. (A full HTTP→worker→retrieval integration test remains a
nice-to-have, but the controller-parse lock + the live end-to-end together cover the regression surface.)

**3. (LOW, 585's domain) No general agent-history backfill-from-ledger.** `AgentHistoryIndexer` indexes only
via the live listener + (now) `reindexRestoredRun` at import. A run that failed to index live (worker down at
finish) has no rebuild path. The proper DERIVED-rebuild (re-index every un-indexed run, dedup via the
per-session `.md`) is deferred; not required to close the 629 import gap.

**4. (LOW) Canonical documentation.** The shipped feature has no canonical doc and its endpoints are absent
from `docs/reference/api-contract-map.md` (see the IMPLEMENTED note above). A separate documentation pass.

---

# Future directions — internet research (2026-06-23, 2 rounds, 5 research agents)

A pure-research pass on "what the 629 substrate could become." Documentational only — nothing below is
committed work; it is a grounded map of the adjacent possible. Sourced from 2024-2026 primary material
(W3C/MDN, NIST, vendor + local-first/AI-memory literature); key citations inline.

## The unifying finding

629 did not just ship a feature — it produced a **small set of composable primitives**: the wrapped-DEK
envelope (DEK ← passphrase-KEK + recovery-KEK), the **DERIVED/AUTHORED** store spine, the per-store
`StoreCipher`, the portable encrypted container, and the **"erasure = destroy the recoverability handle"**
principle. Five major capabilities slot onto this substrate **additively — no re-architecture** — because of
two structural properties that the research repeatedly confirmed:

1. **The DEK never changes**, so every new way IN is just **another independent wrap** of the same DEK
   (a device-keychain wrap, a per-device wrap, a recovery wrap — all parallel). Adding/removing a wrap never
   re-encrypts data.
2. **DERIVED rebuilds from AUTHORED**, so sync, deletion, and memory all reduce to **"move or destroy the
   AUTHORED authority, then re-derive the rest."** This is textbook state-machine-replication / event-sourcing
   (Kreps, *The Log*; event-sourcing + projections) — our spine already is that architecture.

So the "recoverability handle" generalizes into a verb set: a handle can be **added** (wrap = a new unlock),
**moved** (ship the handle/log = sync), **destroyed** (crypto-shred = delete) — and destruction must
**cascade** to derived copies (rebuild-without-source). That single sentence organizes everything below.

## Five directions (each grounded; tags = shippability)

**D1 — Passwordless unlock on the trusted device `[shippable-now]`.** A **third KEK wrap** sourced from the
OS credential store (`keyring-rs`, mature, Windows Credential Manager / macOS Keychain / libsecret) so the app
unlocks transparently on the trusted device while passphrase + recovery key remain the portable escrow.
Reframes the deferred **Phase 7 (task #25)**: the genuinely shippable win is the **OS-keychain wrap**, *not*
WebAuthn-PRF — PRF is unavailable in WebView2, and Windows Hello PRF only landed Feb-2026 (KB5077181) so most
users lack it. PRF via a native Tauri plugin / `webauthn.dll` is an opportunistic second tier, reliable today
only with an external FIDO2 key. Risk: a vault-stored KEK is readable by any process running as the user —
convenience, not in-memory isolation; keep the Phase-6 honesty copy accurate.

**D2 — Granular crypto-shred deletion `[shippable-now → maturing]`.** Promote today's single DEK to a **Master
DEK**, and seal each shred-unit (per-conversation, and per-time-epoch for the append-only agent-run ledger)
under its **own sub-DEK wrapped *only* by the Master DEK**. "Delete this forever, even from backups" = destroy
that one wrapped sub-DEK. The **recovery key keeps wrapping the Master DEK only** (not sub-DEKs), which
elegantly resolves the per-item-key-vs-one-recovery-key tension: recovery reconstructs the Master DEK → every
*surviving* sub-DEK, but a destroyed sub-DEK stays gone **even after recovery** (AWS Encryption SDK
multi-wrap-one-data-key pattern). NIST SP 800-88r2 (Oct-2025) keeps Cryptographic Erase a valid Purge — and
its precondition "encryption from initial deployment" is *met* (pre-production). The simplest hierarchy that
delivers true delete: **two existing KEKs → one Master DEK → flat per-unit sub-DEKs** + a transactional keys
store + an audit log. Do NOT build a deep key tree or per-message ratchet.

**D3 — AI personal memory `[cheap → moderate]`.** We already own the hard part the memory products
(mem0/Zep/Letta/LangMem) are built around: **hybrid BM25+vector search over the user's own agent-history +
a local LLM**. Cheap wins, mostly presentation on infrastructure we have: **auto-recall** at run start (inject
top-K relevant past runs), an **"ask your past" RAG** scope, a **"you asked this before"** banner, and
**source attribution** ("this answer reused memory M from run R"). Moderate: **consolidation/reflection** — a
post-run async LLM pass extracting durable facts/preferences into a small semantic store (LangMem pattern;
Generative-Agents recency·importance·relevance scoring) — and a **memory-control panel** where **"forget" =
crypto-shred (D2)**. Gate memory ops on **unlock** (reuse the Phase-3 / 596 locked gate); keep a
**content-free metadata layer** (Opal, arXiv 2604.02522) so recall can *rank* without decrypting bulk
ledgers. Caveat: a quantized local LLM caps extraction quality → treat consolidated facts as
**confirm-not-trust**, dated and editable. Do NOT build a Letta-style agent-OS runtime.

**D4 — Multi-device E2E sync `[moderate]`.** The DERIVED/AUTHORED split **is** the canonical sync
architecture: **sync only the encrypted AUTHORED append-only log to a dumb/blind object store; rebuild DERIVED
locally on each device.** Append-only NDJSON ledgers are a **G-Set CRDT** → merge = set union (no LWW, no
per-type conflict logic). The **recovery key is the cross-device pairing root** (precedent: Matrix SSSS,
1Password Secret Key, Bitwarden), and the encrypted export container is already a *manual one-shot* of exactly
this. Hard parts that survive: causal ordering of concurrent appends (a **cleartext envelope** — Lamport clock
+ device-id + prev-hash — around each sealed record) and compaction-under-encryption. **Scope to
single-USER-multi-device** — multi-*user* sharing is the group-key-management tarpit; avoiding it removes ~80%
of the crypto difficulty. Nothing off-the-shelf is both E2E *and* usable from our Rust+Java stack
(Jazz/Evolu are JS, Ditto is commercial) → the fit is **DIY encrypted-log-over-object-storage**, using
Jazz/CoJSON as the design reference.

**D5 — Deletion-cascade correctness (a meta-finding, and a latent gap) `[shippable-now]`.** Crypto-shred is a
**whole-system property, not a per-store one**: the Lucene index is **DERIVED but holds plaintext-derived
terms/vectors** extracted from the encrypted AUTHORED ledgers — a *surviving second copy* that defeats "truly
delete." Lucene `deleteDocuments` only **tombstones** until a segment merge rewrites the segment (terms stay
forensically readable; force-merge is a 2-3× disk-spike, threshold-gated) — so the **guaranteed** purge is
**rebuild the affected DERIVED collection from the surviving sources** (seconds-to-a-minute at our scale; gone
*by construction* because the shredded source is never an input). This is precisely our spine's
"DERIVED rebuilds from source." Per-document vector deletion is a **row delete, not machine-unlearning** (the
encoder is frozen). Secondary: the persisted DERIVED index arguably should be **sealed at rest too** (Proton/
Signal/iOS precedent) — a plaintext-on-disk index built from encrypted sources is the documented anti-pattern;
"rely on OS FDE" is acceptable only if we don't want shred to mean "even from someone with the disk." This
also flags a **current latent gap**: deleting an agent run today does not purge its terms from the
`agent-history` index — wiring delete → DERIVED-rebuild closes it. (And: sweep *all* plaintext-derived copies
— query caches, exports — not just the index.)

## The keystone primitive

The **general `AgentHistoryIndexer` backfill-from-ledger** — already logged as deferred follow-up #3 — is the
concrete **"rebuild DERIVED from surviving source"** capability that **D2 (delete-cascade), D4 (per-device
rebuild), and D5 (purge-on-shred)** all depend on. It was scoped as a nice-to-have; the research reframes it
as **the single highest-leverage next primitive** — build it once, three futures inherit it. (It also remains
the right fix for runs that failed to index live.)

## Honest caveats / do-NOT-build list

- **Don't build:** multi-*user* sharing (group-key tarpit), a Letta-style agent-OS runtime, Searchable
  Symmetric Encryption (wrong threat model for single-user-local; actively defeated by leakage attacks),
  machine-unlearning for embeddings (it's a row delete with a frozen encoder), deep key hierarchies or
  per-message ratchets.
- **WebAuthn-PRF is not webview-ready** and Windows Hello PRF is too new (early-2026) — OS-keychain is the real
  near-term unlock; treat PRF as opportunistic.
- **Local-LLM quality caps memory consolidation** → confirm-not-trust, never auto-act on low-confidence facts.
- **Crypto-shred's hard truth:** any plaintext-derived copy (index, cache, swap, export) that isn't under the
  destroyed key defeats it; "count the wrapped key copies and destroy them all" + "rebuild every derived copy"
  is the whole guarantee. Backups holding ciphertext-only is *fine* (restore is unreadable) — a stray *key*
  copy is the killer.
- **Always-on memory fights at-rest encryption** — resolve explicitly (unlock-gated ops + content-free
  metadata layer), don't paper over it.

## Suggested sequencing (if any of this is ever pursued)

1. **The keystone backfill** (D5/follow-up #3) — unlocks D2/D4/D5. 2. **D1 OS-keychain unlock** — biggest UX
win, lowest risk, self-contained. 3. **D3 cheap memory wins** (recall + ask-your-past + provenance) — high
value on owned infrastructure. 4. **D2 per-conversation crypto-shred + the D5 index-rebuild cascade** — the
privacy payoff; "forget" becomes real. 5. **D4 sync** — largest build; only single-user-multi-device.
Everything is additive on the shipped substrate; none requires re-encrypting existing data.

---

# End-to-end validation + issues surfaced (2026-06-23)

A final validation pass against the LIVE stack + the local LLM, covering both the 629 feature and the app's
general functionality (indexing / search / agent conversations). All core functions confirmed working; the
issues surfaced are recorded here (and, for out-of-scope ones, in `docs/observations.d/`).

## Validated green (live stack)
- **Encryption lifecycle (629):** setup (recovery key generated) → export → delete → import (faithful
  restore) → reindex, all through the running app. The Security surface renders the honest at-rest
  disclosure (Disk/Index = "Not encrypted" — FDE-only **by design**; Conversations = "Encrypted (passphrase)").
- **Indexing:** the help-doc corpus indexes; search returns real documents.
- **Document search:** API + the **real UI in a fresh tab** both return + render relevant results (e.g.
  `keyboard-shortcuts.md` with content).
- **Agent conversations:** with the local LLM active (`/api/inference/mode` `online`), a live
  `/api/chat/agent` run **reasoned → invoked the `core_search_index` RAG tool → streamed tokens → produced a
  correct, cited answer** ("the Escape key clears the search box [1]"). The finished run was auto-indexed into
  `agent-history` and is searchable via the scope; the default scope correctly **excludes** it (no leakage).

## Issues surfaced (severity · where tracked)
1. **[MED · this doc §Future-directions D5] Crypto-shred does not cascade to the DERIVED search index —
   confirmed live.** The `agent-history` Lucene index holds the full **plaintext** transcript extracted from
   the encrypted AUTHORED ledger, so deleting a run leaves its words searchable: **"delete" is not yet
   "gone."** Fix: rebuild the DERIVED collection from surviving sources on delete (the keystone backfill,
   Open-issue #3, is the primitive). This is the sharpest real gap — recommended for a fix **before there are
   users**, since it is a privacy-promise issue, and it is exactly the deletion-cascade correctness point the
   research surfaced.
2. **[LOW · observations] Stale FE backend port across a dev-stack restart.** An open ui-web tab keeps a dead
   absolute `apiBase` → silent "Failed to fetch / Reconnecting…" + empty results until a hard reload; relative
   `/api` (Vite proxy) keeps working and **masks** it (cost real debugging time). Dev-ergonomics, not product.
3. **[LOW · observations] First-run search-degraded window.** A fresh/legacy index logs `BLOCKED_LEGACY` and
   blocks hybrid/vector queries until an auto `REBUILDING` reindex completes; during it the **default
   (hybrid)** search is weak/empty while `mode:text` BM25 works. Self-healing; verify first-run UX signals
   "index warming," not "no results."
4. **[VERIFY · observations] Agent reasoning interleaved in the answer stream.** The raw SSE chunks carried the
   model's planning text before the final answer; confirm the chat UI renders reasoning as a distinct/
   collapsible node, not as the answer.
5. **[VERIFY] Reranker skipped** (`Cross-encoder skipped BELOW_MIN_THRESHOLD`) in the dev run — likely intended
   for a tiny corpus; confirm production reranks at scale.
6. **[LOW] Eventual-consistency race** — a search fired immediately after import / run-finish can briefly show
   no results until the async `agent-history` index commits (~1-2 s).

**Net:** the app's general functionality (indexing, hybrid search, agent RAG with the local LLM) is sound, and
629 ships correctly end-to-end. **#1 is the one real correctness gap recommended for a fix**; the rest are
dev-stack artifacts or verify-items, all tracked.
