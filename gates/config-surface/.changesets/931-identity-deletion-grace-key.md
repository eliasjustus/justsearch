---
classification: declared-growth
tempdoc: 931
---

Declares ONE configuration key: `index.identity.deletion_grace_ms` /
`JUSTSEARCH_INDEX_IDENTITY_DELETION_GRACE_MS`, default `2592000000` (30 days). Net
`env_sysprop_pairs` growth is exactly this key; `yaml_keys` and `config_keys` are unchanged.

**Why it is a key and not a constant.** It is the one number that decides between two failures the
codebase cannot distinguish structurally, and the right value is a property of the user's storage,
not of the product. Identity rows (`document_identity`, schema V11) have no GC, so before §C.6 a
replacement file at a previously-deleted path silently inherited the old document's uid — and with
it every label, citation, and disposition ever recorded against the document that used to live
there. The obvious fix, deleting the row on removal, is worse: an unmounted network drive, a sync
client that hides a file mid-write, and a OneDrive placeholder all read as "gone", so identity would
break permanently on an ordinary restore. The grace window is the compromise, and it is exactly the
knob a user with a flaky NAS or an aggressive cloud client needs to widen — and one with a
short-lived scratch corpus may want to narrow. This changeset ships the window; it makes no claim
that 30 days is right for every corpus, which is why it is configurable.

**Not shadowed by a hardcoded constant** (tempdoc 754's check). The single default lives at
`ResolvedConfig.Index.DEFAULT_IDENTITY_DELETION_GRACE_MS` and is used both as the resolve default
and by `SqliteDocumentIdentityStore`'s convenience constructor;
`IdentityDeletionGraceConfigForwardingTest` asserts the registry string and the code constant are
the same number rather than two that can drift.

**It resolves onto `ResolvedConfig.Index` and reaches the Worker through the ordinal-450 config
snapshot**, not a raw `EnvRegistry` read inside the Worker JVM. The identity store is constructed in
`KnowledgeServer` (the Worker), and 885 [R1] found a Worker-side knob whose only setter lived on the
Head, so it could never fire. `IdentityDeletionGraceConfigForwardingTest` walks the whole Head →
snapshot → Worker path for this key, the same shape `CommitTimerConfigForwardingTest` pins.

**Resolved as a long, not an int.** 30 days is 2,592,000,000 ms, past `Integer.MAX_VALUE`; resolving
it as an int would silently truncate the default. The test pins that inequality directly so a later
refactor to `resolveInt` fails loudly.

**An unparseable value falls back to the default, not to zero.** A zero window re-mints the uid of
every file that reappears after any confirmed deletion, which is the exact harm the key exists to
prevent — so a typo must not be the most destructive setting.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the key it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 250 | **251** | +1 = exactly the key above |
| `yaml_keys` | 111 | 111 | unchanged — the key has no YAML contribution |
| `config_keys` | 56 | 56 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=111 env_sysprop_pairs=251 config_keys=56 rows=307`). The pre-change pin of 111/250/56 is
what `main` measures, so this branch's delta is fully attributable and the ratchet keeps its
meaning — it still only ratchets DOWN from here.
