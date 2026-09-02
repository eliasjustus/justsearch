---
classification: declared-growth
tempdoc: 883
---

Advances the baseline to what `main` already measures, and declares this branch's one
allowlist entry. Three counters move; none of them licenses new surface.

**`env_sysprop_pairs` 243 → 244 — closing a red `main` inherited from #595.** The three
`justsearch.extraction.sandbox.*` keys were declared by lane C in
`885-extraction-sandbox-pool-keys.md` and merged in #595. The changeset-loader honours only a
changeset present in the CURRENT diff against the baseline ref, so the moment that PR merged, its
declaration stopped being visible to every later PR — which then compared live 244 against the
stale 243 pin and failed `config-surface/silent-growth` with no eligible changeset. That is
main's red, not this branch's growth: this branch adds no `EnvRegistry` entry at all
(`git diff origin/main...HEAD -- .../EnvRegistry.java` is empty). Exactly the situation
`854-w1-advance-baseline-to-112-243.md` documents, and this is the same remedy.

**`yaml_keys` 112 → 108 — a rebalance DOWN, not growth.** The measured count has been below the
pin since #592, and this PR moves it once more in the same direction: it adds one `putYaml*`
contribution (`search.pipeline.profile`, which shipped in `config/application.yaml` with a value
that reached nothing) and the net is still four under the old pin. Tightening a ratchet needs no
licence; it is recorded here so the two edits to `baseline.txt` are explained in one place rather
than one of them looking unexplained.

**`sysaccess-allowlist` 104 → 105 — one entry, inherited via the merge.** The entry is
`io.justsearch.indexerworker.extract.ExtractionSandboxCommand#javaBinary`, added by lane C in
#595 and reaching this branch through the `origin/main` merge, not written here. It is a
`java.home` FALLBACK for the JVM's own launcher path when `ProcessHandle.Info#command()` is
unavailable — JVM self-introspection, not user configuration — and it carried the same named
exemption in `IndexerWorkerGuardrailsTest` that this PR converts into an allowlist line for all
five of that rule's exemptions. The count is therefore +1 on the ledger and ±0 on what is actually
permitted.

Worth recording because the ratchet *found* it: the funnel test was seeded at 104 before the
merge, went red on the merged tree naming this one site, and went green once it was listed. That
is the cross-module case the six per-module rules this PR deletes could only handle one module at
a time. CI would not have caught it on this PR — the allowlist file does not exist on
`origin/main` yet, so `readFileAtRef` returns null and the growth check is skipped — which is
precisely why it is declared here rather than left to the first PR that inherits a real baseline.
