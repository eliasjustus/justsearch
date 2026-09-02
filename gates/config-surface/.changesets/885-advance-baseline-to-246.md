---
classification: declared-growth
tempdoc: 885
---

Advances `env_sysprop_pairs` 244 → 246 to what `main` already measures. **This PR adds no
configuration key.** `git diff origin/main --name-only` on this branch lists no `EnvRegistry`, no
`ResolvedConfig*`, no YAML schema and no `gates/config-surface/**` file; `--preflight origin/main`
reports "No gates affected by this diff". The growth being pinned here is not this branch's.

**Whose growth it is.** The two keys are `justsearch.indexing.foreground_duty_pct` and
`justsearch.indexing.foreground_cooldown_ms`, added by tempdoc 885 item 3 and **already declared**
in `885-indexing-foreground-pacing-keys.md`, which merged with #598. They are legitimate, reviewed
surface; nothing about them is being re-litigated here.

**Why a second changeset is needed for keys that were already declared.** The changeset loader
honours only a changeset present in the CURRENT diff against the baseline ref. The moment #598
merged, its declaration stopped being visible to every later PR — but the pin stayed at 244 while
the measured count became 246. So `main` measures 246 against a 244 pin, and **every** subsequent PR
that carries no config changeset of its own fails `config-surface/silent-growth` for growth it did
not cause. That includes CI's Public-claims job, which runs the gate bare.

This is the structural gap lane A recorded in tempdoc 883: a PR that declares growth must advance
the pin **in the same commit**, or the declaration evaporates on merge and leaves a red behind it.
#598 declared the +2 and left the pin at 244. `883-advance-baseline-to-108-244.md` and
`854-w1-advance-baseline-to-112-243.md` are the two prior instances of exactly this remedy; this is
the third, and it is applied for the same reason and in the same shape.

**The other two counters are already correct and are not touched.** Measured `yaml_keys` is 108
against a pin of 108, and `config_keys` is 56 against a pin of 56 — both equal, so `baseline.txt`
moves on one line only. Recorded here so a reader does not go looking for the other two edits.

`verify-runtime-config-matrix` was already content at 246 before this change; it is the ratchet pin,
not the surface, that was behind.
