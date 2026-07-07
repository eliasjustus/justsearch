# jseval baseline-relaxation changesets

Tempdoc 664. Mirrors the discipline-gate kernel's changeset-escape-hatch convention
(`gates/<id>/.changesets/README.md`, tempdoc 530) — reused as a *convention*, not wired into
that JS kernel, since jseval's ratchets are advisory and live-stack-dependent, not fast-CI-gated.

`perf-gate --update-baseline`, `leak-gate-derive`, and `jseval release` all refuse to write a
per-metric relaxation (a floor going the wrong direction vs. the previous pin) unless a matching,
justified changeset is present here.

## Adding a changeset

Scaffold one with `python -m jseval changeset-new --gate <gate> --dataset <slug[:metric]> --tempdoc <n>
[--reason "..."]` (tempdoc 664, twelfth pass) — writes the exact frontmatter shape below, refusing to
overwrite an existing file. Or create a `.md` file in this directory by hand with frontmatter:

```markdown
---
classification: baseline-relaxation
gate: perf-gate            # perf-gate | leak-gate | release | "*" (any gate)
dataset: beir/scifact:ce_p50_ms   # "<dataset>:<metric>" for perf-gate/release, "<dataset>" for
                                   # leak-gate, or "*" (any dataset/metric for this gate)
tempdoc: 636                # required — the tempdoc/decision that accepted this regression
---

Free-form body explaining why the relaxation was accepted.
```

An **improvement** never needs a changeset — only a relaxation (a floor moving the wrong
direction) is checked. The `tempdoc:` field is required and must be non-empty; a changeset
without one does not justify anything.

**Not single-use.** Unlike the JS discipline-gate kernel's changesets (which are scoped to a PR's
diff), a changeset here currently justifies *every* future relaxation of its declared
`(gate, dataset)`, not just the one it was originally written for — jseval's callers don't
currently pass the `baseline_ref` needed for PR-scope discovery. Delete or narrow a changeset
once its specific relaxation is no longer the current, intended state.
