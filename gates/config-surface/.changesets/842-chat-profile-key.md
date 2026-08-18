---
classification: declared-growth
tempdoc: 842
---

`env_sysprop_pairs` 240 → 241: `EnvRegistry.CHAT_PROFILE`
(`justsearch.chat.profile` / `JUSTSEARCH_CHAT_PROFILE`, default `standard`).

**This replaces a knob rather than adding one, but the replacement is staged.** The
key selects the llama-server engine's *(model, mmproj)* pair by name — the axis
`justsearch.vlm.profile` already carries at the wrong altitude (package-private
inside `InferenceConfig`, invisible to health/manifest/evidence). Tempdoc 842 §5
orphan 1 retires that private encoding onto the hoisted `ChatModelProfile`; until
the inference-layer migration lands, `justsearch.vlm.profile` stays as a legacy
fallback and the surface carries both. The count returns to 240 when it is
withdrawn — that withdrawal is owned by 842, not deferred to a later sweep.

**It is a selection, not a tunable.** The value is one of a closed enum
(`standard` | `compact`), each member an atomic file pair the registry declares.
Nothing here is a number to tweak; picking `compact` on a dev stack is the same
kind of launch-time choice `justsearch.mode` (`InstallIntent`) already is, and it
is carried the same way — one `EnvRegistry` key, resolved once, republished on
the runtime manifest so the advertised profile cannot drift from the loaded one.

**Why it is not deletable.** The alternative to a named pair is the per-file
`JUSTSEARCH_VLM_MODEL` / `JUSTSEARCH_MMPROJ_MODEL` overrides, which are exactly
how the current dev stack ended up running llama-server with no `--mmproj` at all
(842 §2.3, verified live). A half-swap must be unrepresentable, and that requires
one key naming both files.
