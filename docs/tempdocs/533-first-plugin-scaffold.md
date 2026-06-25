---
title: "533 — First plugin: pick one concrete plugin and ship it as the PluginHostApi forcing function"
type: tempdocs
status: shipped
created: 2026-05-20
category: substrate-validation / C-018
related:
  - tempdoc 521 (the plugin substrate)
  - tempdoc 527 §6.2 (9 of 14 PluginHostApi sub-interfaces hollow)
  - tempdoc 527 §6.3 (host.ai bridge unconsumed)
  - tempdoc 491 Phase D (plugin-contributed ConversationShapes — unblocks when first plugin needs one)
  - tempdoc 532 (VirtualOperationCatalog — natural test for plugin-emitted agent verbs)
---

# 533 — First plugin scaffold

## The pressure

521 designed the plugin substrate (`PluginHostApi`, SES Compartment lockdown, signature verification, manifest validation, contribution registries, lifecycle). The orchestration substrate (CommandRegistry, KeybindingRegistry, EmptyStateRegistry, ShellContext, TemplateCatalog, StatusBarRegistry, InspectorTabRegistry) is production-consumed. The plugin-facing surface is not.

527's audit found 9 of 14 PluginHostApi sub-interfaces have zero production callsites outside HostApiImpl. The substrate is structurally correct but consumer-pending. The author of every PluginHostApi method has been correct about what plugins will need — but no plugin has ever needed it.

This is exactly the F1 (speculative generality) shape that ADR-0014, ADR-0017, and the `ai-worker`/`app-ai`/pipeline-engine deletions warned about. The cure named in every one of those postmortems is the same: **build a real consumer before the substrate calcifies further.**

## The idea

Pick one concrete plugin scope. Ship it as the forcing function. Three constraints:

1. **Small enough to ship in one slice.** Days of work, not weeks.
2. **Exercises ≥3 hollow sub-interfaces.** The plugin must actually use `discovery` / `settings` / `search` / `inspector` / `selection` / `theme` / `platform` / `utilities` / `data` to count. A plugin that only registers a command (via the already-consumed `registration` sub-interface) doesn't move the needle.
3. **Useful enough that a real user would install it.** Not a smoke-test plugin. The bar is "if this were published, someone would download it."

The plugin should land as a third-party-shaped install — real lockdown, real signature verification, real source loading — even though the author is the project itself. The whole point of 521 was to validate the substrate against an untrusted-by-construction caller; shipping a plugin that bypasses lockdown defeats the calibration.

## Candidates

| Plugin | Surfaces exercised | User value | Effort |
|---|---|---|---|
| **Highlight-TODOs annotator** | search results decoration, inspector tab (TODO list view), settings (toggle + glob patterns), selection (jump-to-TODO), data (read file content) | high for code corpora | medium |
| **Markdown checklist tracker** | inspector tab, navigation (jump to checkbox), data (read+write — write is the calibration), settings, selection | high for notes corpora | medium |
| **Citation-graph viewer** | new surface (graph rendering), data (resolve docId→neighbors), search, inspector | high for research corpora | high |
| **Quote-of-the-day status-bar widget** | status-bar contribution, settings (source), platform (HTTP fetch via host) | trivial | small |
| **Plain theme** (Nord, Solarized, etc.) | theme sub-interface only | medium | small |

The trade-off:

- **Status-bar widget / theme**: small, validates the smallest sub-interface, doesn't exercise the load-bearing ones.
- **Highlight-TODOs / Markdown checklist**: exercise data-read (and write, for checklist), settings persistence, inspector tab contribution, selection — i.e., the sub-interfaces 521 designed for real plugin use.
- **Citation-graph**: ambitious; would also exercise the new-surface mounting path and stress-test the data sub-interface against a non-trivial query.

## Recommendation

**Markdown checklist tracker.** Reasons:

- Mid-effort. Days, not weeks.
- Exercises ≥6 sub-interfaces (data, settings, inspector, selection, navigation, registration).
- Calibrates the trust attenuation in a real way: a plugin that *reads* file content is a different trust profile from one that *writes*. The substrate's distinct treatment of read vs write must hold.
- The user value is genuine — notes-corpus users immediately benefit.
- Forces the resolution of 532 (VirtualOperationCatalog) and 491 Phase D (plugin shapes) if the plugin wants to expose agent verbs or a conversation shape. If it doesn't, both substrates stay unconsumed — which is the right signal to retract.

## Composition with other tempdocs

- **521**: this is 521's overdue consumer.
- **527 hollows**: closes 6–9 of them.
- **532**: if the plugin exposes an agent verb (`vop_extract_checklists`), Candidate B from 532 ships. Otherwise 532 picks a different consumer.
- **491 Phase D**: if the plugin needs a custom ConversationShape (probably not, but possible), Phase D opens.
- **531 (drift detection)**: the consumer expectations for each exercised sub-interface ratchet from "≥0 within grace period" to "≥1" after this plugin ships.

## What this is NOT

- Not the plugin marketplace (H3-5 — gated on a sandboxing thesis).
- Not auto-update.
- Not a multi-plugin coordination story.
- Not a smoke-test plugin. The whole bet is that a real plugin surfaces the calibration; a smoke-test plugin trades that for false confidence.

## Open questions

- **Author identity**: does the plugin author claim to be a third party, or honestly the project? Probably honestly the project, with a manifest field that distinguishes "first-party plugin" from "third-party plugin." Trust tier reflects.
- **Distribution**: where does the plugin source live? `modules/` (in-repo) or `third_party/` or a separate repo? In-repo for V1; the install path treats it as third-party (loaded via the plugin loader, not by direct import).
- **Signing**: the lockdown design implies signed sources. For the first plugin, generate a key, sign the source, validate at load. This is the validation that the signing path works end-to-end.
- **Failure surface**: what happens if the plugin's `data.read` is denied? Does the plugin gracefully degrade or throw a confusing error? The trust attenuation needs a real test case.

## Decision (2026-06-04)

**The actual first plugin is the Token Editor** (a dogfood-by-extraction of the existing `TokenEditorSurface`),
diverging from this doc's checklist recommendation — see **tempdoc 560 §22** for the full reasoning. Short version:
the Token Editor already exists in the right shape (read + live-preview + export, never writes core tokens), so it is
the *lower-greenfield, lower-risk* first plugin and is the maintainer's pick; it forces the `theme` sub-interface (one
of the 8 implemented-but-unconsumed slots, 527 §6.2) to be completed as its first consumer.

> *Note — the de-risk's earlier "ruled out the Token Editor" framing (560 §20.3) was retracted as unfair: "extracting
> it forces capability work" was a double standard, since this doc's checklist forces capability work too. Both are
> legitimate forcing functions.*

**This doc's Markdown-checklist tracker remains the recommended SECOND plugin** — it is the higher-leverage
*pure-validation* probe: it exercises six implemented-but-unconsumed sub-interfaces *and*, uniquely, the read/**WRITE**
trust calibration the Token Editor (read-only) leaves untested. Sequencing it right after the Token Editor pays down
the breadth/write-calibration gap.

De-risk facts (560 §20, verified) that de-risk *either* pick: the loader → SES sandbox → attenuation pipe is
**live-proven** (scaffold loaded; namespace proxy + `@kernel/data` allowlist fired); the Settings **"Load from URL"**
entry point exists; `lockdown()` is a staged-but-test-proven flip; signature verification has a built seam (Sigstore
V1.5.2-pending). Two open questions above are partly answered: **author identity** → first-party, loaded *as*
third-party through the real loader; **failure surface** → UNTRUSTED `@kernel/data` denial is a clean console
rejection, not a crash.

**Not yet implemented.** Preconditions when starting (Token Editor): fix the broken scaffold `dev-server.js`
(CommonJS in a `type:module` package — won't run); extend `PluginThemeState` with a token-tree read + a **value-based**
`previewTokens` (host generates the scoped `<style>` — never raw plugin CSS, which would be a clickjacking vector);
keep V1 FE-only + **agent-verb-free** (`VirtualOperationCatalog` is not booted — 560 §20 — so a plugin verb would
silently no-op; 532 stays deferred). The checklist's `data` **write** path is the second plugin's forcing-function cost
("write is the calibration").
