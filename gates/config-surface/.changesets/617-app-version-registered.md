---
classification: declared-growth
tempdoc: 617
---

`env_sysprop_pairs` 239 → 240: `EnvRegistry.APP_VERSION`
(`justsearch.app.version` / `JUSTSEARCH_APP_VERSION`).

**This is a knob becoming visible, not a knob being added.** The property already
existed in the tree — the upgrade surfaces read it as a raw
`System.getProperty("justsearch.app.version", "")` at three sites
(`LocalApiServer` ×2, `HeadlessApp`). The `checkNoDirectJustsearchSysProp` build
gate refuses that pattern, so registering it in `EnvRegistry` was the required
fix, and registration is what made the surface counter able to see it. Declining
the growth here would mean either reverting to an unregistered raw read — which
the other gate blocks — or leaving the branch red.

**It is a runtime fact, not a tunable.** The desktop shell injects the version of
the binary it is running; Head reads it to decide whether a durable update intent
describes the source build or the target build. That distinction is what keeps
restart reconciliation from treating a version match as proof of a successful
install (tempdoc 617 §7.5). Nothing chooses a value here — there is no
configuration a user or operator would set, and no behaviour to tune. It is
carried on the config surface because that is where this codebase puts
process-level inputs, not because it is a decision deferred to runtime.

**Why it is not deletable.** The alternative to an injected version is the shell
and Head each deriving it independently, which reintroduces exactly the
divergence the upgrade path cannot tolerate: two components disagreeing about
which build is running while a durable intent is open.

Per 799 K.4's framing, the pressure this gate applies is against *regrowth of
deferred decisions*. This entry defers no decision; it records one process fact
in the one place the codebase reads process facts from.
