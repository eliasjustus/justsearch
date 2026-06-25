---
title: "543 — Extensibility Substrate: Prior-Art Survey"
---

# 543 — Extensibility Substrate: Prior-Art Survey

**Status**: open
**Context**: informs follow-up design discussion for the kernel-rendered Provenance / Scope / Action / HoverPreview substrate documented in worktree 507. Plugin-side surface = "plugins request, kernel renders."

The substrate already in place:

- **Provenance** — `{tier, contributorId, version}` on every registry entry; kernel renders chips.
- **Scope** — flat-key snapshot consumed by a VS Code-style `when`-evaluator; subset persists via Workspace Profiles.
- **Action** — `(AddressableKind, Scope) → handler(args) → Effect`; params declared as JSON Schema with optional `x-ui-renderer` extension; `Effect` is a closed union (noop / navigate / open-pane / toast / invoke-operation).
- **HoverPreview** — `'hover-preview'` SurfaceContextKind in the aggregate-substrate; kernel owns popover host + debounce + dismiss.

---

## 1. VS Code contribution points

VS Code's manifest exposes ~35 `contributes.*` entries spanning UI surfaces, language tooling, debugger plumbing, theming, and chat. The full list (from the canonical reference) includes: `authentication`, `breakpoints`, `chatInstructions`, `chatPromptFiles`, `chatSkills`, `colors`, `commands`, `configuration`, `configurationDefaults`, `customEditors`, `debuggers`, `grammars`, `icons`, `iconThemes`, `jsonValidation`, `keybindings`, `languages`, `menus`, `problemMatchers`, `problemPatterns`, `productIconThemes`, `resourceLabelFormatters`, `semanticTokenModifiers`, `semanticTokenScopes`, `semanticTokenTypes`, `snippets`, `submenus`, `taskDefinitions`, `terminal`, `themes`, `typescriptServerPlugins`, `views`, `viewsContainers`, `viewsWelcome`, `walkthroughs`. Source: [Contribution Points](https://code.visualstudio.com/api/references/contribution-points).

**Gaps relative to current substrate** (categories we don't yet have analogues for):

- `viewsContainers` + `views` + `viewsWelcome` — a *declarative* tree-view contribution surface (plugin says "I have a view", kernel renders container + empty-state). Stronger than ad-hoc panes.
- `walkthroughs` — onboarding/getting-started flows as declarative step lists; a natural fit for "plugin requests, kernel renders."
- `customEditors` — full editor surfaces for non-text resources. Conceptually distinct from `open-pane` Effect.
- `taskDefinitions` + `problemMatchers` + `problemPatterns` — structured long-running-job + diagnostic surface. We have invoke-operation but no "matcher" notion.
- `jsonValidation` — bind JSON Schema to file patterns. Useful if plugins ship their own config files.
- `submenus` — nesting in command-menu UIs; relevant once the palette has hundreds of actions.
- `colors`, `iconThemes`, `productIconThemes`, `semanticToken*` — theming surface for chips/badges. Provenance chips are an obvious consumer.
- `resourceLabelFormatters` — pluggable display formatting for URIs. Maps cleanly to "how does kernel render an addressable's label."
- `terminal` profiles, `authentication` providers — these are platform-y and probably out of scope, but worth noting they're declarative.

## 2. VS Code `when` clause keys

Categories ([when-clause-contexts](https://code.visualstudio.com/api/references/when-clause-contexts)):

- **Editor state**: `editorTextFocus`, `editorFocus`, `editorReadonly`, `editorLangId`, `isInDiffEditor`.
- **Resource attributes**: `resourceExtname`, `resourceScheme`, `resourceFilename`, `resourcePath`. (We have `selectionKind` but not the per-resource attribute fan-out.)
- **View context**: `view` (which view has focus), `viewItem` (tree-item context value — set by extensions), `focusedView`, `explorerResourceIsFolder`.
- **Workbench**: `workbenchState` (`empty` | `folder` | `workspace`), `workspaceFolderCount` (numeric).
- **Input/list focus**: `textInputFocus`, `inputFocus`, `listFocus`, `listMultiSelection`, `listDoubleSelection`.
- **Debug / terminal**: `inDebugMode`, `debugState`, `debuggersAvailable`, `terminalFocus`, `terminalIsOpen`.
- **Custom keys**: extensions set via `vscode.commands.executeCommand('setContext', 'my.key', value)`.

**Worth importing**: (a) per-resource attributes (analog of `resourceExtname` for a search result's URI / document); (b) `viewItem`-style per-row context (set when rendering each result row, so a `when` can gate on "this row is a citation from PDF"); (c) numeric and string-equality grammar (we have flat keys, the grammar should support both); (d) `focusedView` analog tied to surface kind.

## 3. Raycast Arguments & Preferences

Two distinct concepts, both declared in `package.json` (custom shape, **not** JSON Schema — a superset of npm manifest). Source: [preferences](https://developers.raycast.com/api-reference/preferences), [manifest](https://developers.raycast.com/information/manifest).

- **Preferences** (configured once, in settings UI): 7 types — `textfield`, `password`, `checkbox`, `dropdown`, `appPicker`, `file`, `directory`.
- **Arguments** (provided per-invocation, inline in the launcher): only 3 types — `text`, `password`, `dropdown` (with `data: [{title, value}]`). Plus `name`, `placeholder`, `required`.

Key insight: **arguments are deliberately a strict subset of preferences**. The inline launcher is a lightweight strip, so file/directory/appPicker are settings-only. This is a useful split for us: per-invocation `Action.args` should probably be lighter than what a plugin's persistent settings UI supports.

**Actions** ([actions API](https://developers.raycast.com/api-reference/user-interface/actions)) are React components inside an `ActionPanel`. Built-in types include `Action.Push` (navigation), `Action.OpenInBrowser`, `Action.Open`, `Action.OpenWith`, `Action.CopyToClipboard`, `Action.Paste`, `Action.SubmitForm`, `Action.ShowInFinder`, `Action.Trash`, `Action.CreateSnippet`, `Action.CreateQuicklink`, `Action.ToggleQuickLook`, `Action.PickDate`. **There is no explicit "Effect" return type** — actions take `onAction`/`onCopy`/`onPaste`/`onOpen` callbacks. The built-in components *are* the effect taxonomy (close parallel to our closed `Effect` union, but expressed as components rather than data).

## 4. Obsidian commands

`addCommand({id, name, callback | checkCallback | editorCallback | editorCheckCallback, hotkeys})`. The `*CheckCallback` variants receive a `checking: boolean` flag — first call asks "would this run?", second call performs. Hotkeys: `{modifiers: ['Mod', 'Shift'], key: 'a'}` with `Mod` abstracting Ctrl/Cmd. Source: [Commands](https://docs.obsidian.md/Plugins/User+interface/Commands).

**No analog to our Effect.** Commands are freeform callbacks that mutate state directly via the `app` object (e.g., `app.workspace.openLinkText(...)`). The structured-Effect pattern in our substrate is *more disciplined* than Obsidian's — they pay for it in plugin-conflict surface area and in the inability to record/replay/preview what a command will do.

The `checkCallback` two-phase pattern is interesting: it's a poor man's `when` clause (the plugin gates its own visibility imperatively). Our declarative `when` is cleaner, but the two-phase shape may matter for actions whose availability depends on side-effectful state the `Scope` snapshot doesn't capture.

## 5. JetBrains action system

`AnAction` overrides `update(AnActionEvent)` (called frequently, must be fast) and `actionPerformed(AnActionEvent)`. `AnActionEvent` carries a `DataContext` (project / editor / selection / virtual file). `ActionPlace` is a string constant ("MainMenu", "EditorPopup", "ProjectViewPopup", "MainToolbar", ...) passed inside the event — actions can branch on place to vary presentation. Registration happens in `plugin.xml` via `<action id="..." class="..."><add-to-group group-id="..." anchor="..."/></action>`, and one action can `add-to-group` into multiple places. Source: [action-system.html](https://plugins.jetbrains.com/docs/intellij/action-system.html).

**Conceptual parallel to AddressableKind**: ActionPlace is a *string* (open set), whereas our `AddressableKind` is a closed union. The closed union is safer (kernel can exhaustively enumerate) but locks the surface. JetBrains' approach lets third-party plugins introduce new "places" — useful if we ever want plugins to contribute *surfaces* rather than just contributions to existing surfaces. The two-phase `update()` / `actionPerformed()` shape also mirrors Obsidian's `checkCallback`; a hot-path `update()` is exactly what our `when`-evaluator already does declaratively.

## 6. JSON Schema + UI rendering

Three approaches in the ecosystem:

- **JSONForms** ([jsonforms.io](https://jsonforms.io/docs/)) — *separate* UI Schema document, addresses fields via JSON pointers (`#/properties/name`), explicit layouts (`VerticalLayout`, `Group`, `Control`). Strong separation, more verbose.
- **react-jsonschema-form (RJSF)** ([uiSchema reference](https://rjsf-team.github.io/react-jsonschema-form/docs/api-reference/uiSchema/)) — UI hints in a parallel object literal that mirrors the data schema's structure: `{"ui:widget": "textarea" | "password" | "select" | "radio" | "checkboxes" | <custom>, "ui:options": {...}}`. Embedded alongside, not separate file.
- **Inline `x-*` extensions** ([Custom Annotations Will Continue](https://json-schema.org/blog/posts/custom-annotations-will-continue)) — JSON Schema explicitly reserves the `x-` prefix (mirroring HTTP headers) for ad-hoc annotations that won't collide with future vocabularies. Examples in the wild: `x-options`, `x-condition`, `x-hidden`.

**Our `x-ui-renderer` is a well-precedented convention** — not a known *standard name*, but the `x-*` shape is the canonical extension mechanism. Worth knowing: the JSONForms / RJSF camp argues for keeping UI hints *out of* the data schema (data schema is portable; UI schema is per-app). We've chosen the embedded approach. The tradeoff: portability of plugin's schema declaration vs. ergonomics of co-located hints. RJSF is the closest analogue to our shape.

## 7. Plugin trust / attribution UX

**VS Code** ([Security and Trust in Marketplace](https://developer.microsoft.com/blog/security-and-trust-in-visual-studio-marketplace), [Extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)) — blue checkmark "Verified Publisher" after 6 months of publishing + 6-month-old domain ownership verified. Marketplace scans for leaked secrets at publish time; publishing blocks on detection. v1.97 added a "do you trust this publisher?" dialog on first install from a third-party publisher. **Known caveat**: badge verifies domain ownership, not code safety; multiple 2025 supply-chain incidents (AsyncAPI token theft) demonstrate the gap.

**Obsidian** ([Plugin security](https://obsidian.md/help/plugin-security)) — "Restricted Mode" is the default; user must explicitly disable to allow community plugins. No granular permissions ("Obsidian cannot reliably restrict plugins to specific permissions") — plugins inherit full app capabilities (filesystem, network, subprocess). Initial review at submission, no per-release review. Heavy reliance on community reporting.

**Raycast** ([Publish an Extension](https://developers.raycast.com/basics/publish-an-extension), [Security](https://developers.raycast.com/information/security)) — all store extensions are open-source (mandatory). PR-based review by Raycast staff + community; CI validates manifest, assets, author identity, build/types. Stricter human-review gate than VS Code or Obsidian, lower volume.

**Pattern synthesis**: three signal axes — (1) *identity* (verified publisher chip = domain proof), (2) *review* (was code human-reviewed? at-submission vs. at-each-release), (3) *capability* (sandbox / permissions / restricted mode). Our `tier` enum (CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN) collapses these three axes into one — that's a simpler UX but loses the ability to display *why* something is trusted. Worth considering a richer provenance record that exposes the underlying signals.

## 8. Workspace Profiles in VS Code

[Profiles docs](https://code.visualstudio.com/docs/configure/profiles): profiles bundle settings, extensions (with enable/disable), keybindings, snippets, tasks, MCP servers, UI layout. **Restore semantics are selective/hybrid, not pure replace**: when creating a profile you can "limit the new profile to only include a subset of configurations…and use the rest of the configurations from the Default Profile." Inactive profiles supplement/replace only explicitly configured elements, not full replacement.

Settings Sync ([docs](https://code.visualstudio.com/docs/configure/settings-sync)) supports profile sync, but extensions don't sync to Remote-SSH / WSL / Dev Containers (environment-scoped). Conflict resolution UI offers Accept Local / Accept Remote / Show Conflicts (diff editor).

**Known pitfalls** (issues + docs):

- No profile *inheritance* — can't say "this profile = base + overrides." Each profile is a flat snapshot. ([Profiles docs limitations])
- "Apply Extension to all Profiles" extensions don't sync cleanly between machines ([vscode#196718](https://github.com/microsoft/vscode/issues/196718)).
- Machine-specific settings excluded from sync deliberately.
- A "different profile active on each machine" silently appears as "extensions not syncing."

**Implication for us**: the selective-subset model is the right default (our flat-key Scope persists "a subset"). The inheritance gap is real — at some point users will want "team profile + my overrides." Plan for it now or document the constraint.

## 9. Hover lifecycle (VS Code)

[VS Code API hover docs](https://code.visualstudio.com/api/references/vscode-api) + issues: multiple `HoverProvider`s for the same language are **asked in parallel and results merged** ([order issue #152897](https://github.com/microsoft/vscode/issues/152897)); a failing provider doesn't fail the operation. When document-selector scores tie, last registered wins.

Settings: `editor.hover.delay` (ms to show), `editor.hover.hidingDelay`, `editor.hover.sticky` (whether mouse-leaving hover keeps it). **Known issue**: when hovering item B while item A's tooltip is open, the transition takes `hover.delay * 2` to swap ([issue #228835](https://github.com/microsoft/vscode/issues/228835)) — A's hide-delay then B's show-delay, no fast-swap.

**Recurring complaint patterns** (from issue search): flicker on rapid mouse movement, hover-traps-keyboard when sticky mode is on, no way to refresh in-place ([discussion #2574](https://github.com/microsoft/vscode-discussions/discussions/2574)).

**Design hints for our HoverPreview**:

- Make the delay configurable, not hard-coded.
- Plan for *multiple providers* — once two plugins both contribute hover for the same kind, you need a merge policy (concatenate sections? first-non-empty? prioritized?).
- Fast-swap when moving from one previewable to an adjacent one (skip the second delay).
- Sticky-mode is a footgun for keyboard focus; design dismiss-on-keyboard-event from day one.

## 10. Command palette UX (Raycast / Linear / Slack / Notion)

Synthesized from [uxpatterns.dev command palette](https://uxpatterns.dev/patterns/advanced/command-palette), [command.ai blog](https://www.command.ai/blog/command-palette-past-present-and-future/), [Superhuman blog](https://blog.superhuman.com/how-to-build-a-remarkable-command-palette/), [Mobbin glossary](https://mobbin.com/glossary/command-palette), Linear's Cmd-K via [storylane tutorial](https://www.storylane.io/tutorials/how-to-use-linears-command-menu).

State of the art:

- **Frecency ranking** (frequency × recency) on recent-used commands above pure search relevance — Raycast and Linear both do this.
- **In-palette argument forms** — selecting a command that requires args opens a *form within the palette* (Raycast Arguments, Linear's chained selection). No modal dance.
- **Chained / nested actions** — each selection in the palette moves to the next set of choices; effectively a stack-machine inside the launcher (Linear, Raycast `Action.Push`).
- **AI command generation / free-form prompt** — when the typed text doesn't match a registered command, hand it to an LLM to either propose a command or directly execute as a natural-language request (newer pattern, surfacing across multiple launchers).
- **Context-aware suggestions** — palette knows the active surface and ranks accordingly (open citation → "Copy citation" floats up).
- **Recent items + destinations** alongside commands — Linear blends "go to issue X" with "create issue" in one list.
- **Keyboard-first** — entire experience navigable without mouse, including form input.

## Cross-cutting observations

1. **Closed-union Effects vs. open callbacks** — Obsidian (callbacks), JetBrains (`actionPerformed`), and Raycast (component-as-effect) all give up the data-shaped Effect we have. Our closed `Effect` union is the *strictest* design in the survey. Strength: kernel can preview, log, undo, render. Weakness: every new affordance requires extending the union. The tradeoff is real but tilts in our favor for a kernel-rendered architecture.

2. **Two-phase action gating is universal** — Obsidian's `checkCallback`, JetBrains' `update()`, RxRaycast's `revalidate` patterns, and our `when` evaluator all converge on "is this enabled right now?" as a hot-path predicate. The declarative-`when` form is the cleanest of these, but be aware that some availability checks need to consult live state the Scope snapshot doesn't include — plan an escape hatch.

3. **Subset-restriction for inline vs. settings UI** — Raycast's deliberate split (3 argument types, 7 preference types) is a good pattern. Our `x-ui-renderer` should similarly distinguish "inline-palette form" (lightweight) from "plugin settings page" (full).

4. **Resource-attribute fan-out is missing from our Scope** — VS Code's `resourceExtname` / `resourceScheme` / `viewItem` per-row context is a strictly richer model than our flat top-level keys. Adding `selectedItem.*` fields (filled per addressable as kernel evaluates `when`) would unlock per-result-row gating without a new substrate.

5. **Trust signal is multi-dimensional** — collapsing identity-verified / human-reviewed / sandboxed into one `tier` enum loses information that all major ecosystems surface separately. A richer provenance record (publisher-verified bool, last-review-date, capability-scope) future-proofs the chip UI.

6. **Tree-views and walkthroughs are declarative-substrate-shaped** — both are "plugin describes structure, host renders chrome." Natural next contribution kinds; they fit the kernel-rendered design without new infrastructure beyond a `views` registry.

7. **Frecency + chained actions + in-palette forms** is now table-stakes for command palettes. The Action substrate + JSON-Schema args is the right foundation for chained-args UX; frecency is a kernel-side concern (the kernel ranks; plugins don't).

8. **Hover merging is inevitable as the ecosystem grows** — design the HoverPreview registry to assume *multiple* contributions per `(kind, context)` and pick a merge strategy (concat with separators is the VS Code default) before the first conflict appears.

9. **`x-*` extension keys are a real convention** — JSON Schema explicitly reserves them. `x-ui-renderer` is well-precedented; future hints (`x-validation-message`, `x-default-from`, `x-data-source`) have a clear naming pattern.

10. **Profile inheritance is the most-asked-for missing feature** in VS Code's profiles. If we ship a Workspace Profile concept and gain users, this request will arrive. Decide now whether profiles are flat snapshots (simpler) or have a base/overlay model (more powerful).

---

## Sources

- [VS Code contribution points](https://code.visualstudio.com/api/references/contribution-points)
- [VS Code when-clause contexts](https://code.visualstudio.com/api/references/when-clause-contexts)
- [VS Code Profiles](https://code.visualstudio.com/docs/configure/profiles)
- [VS Code Settings Sync](https://code.visualstudio.com/docs/configure/settings-sync)
- [VS Code Extension Marketplace security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)
- [Security and Trust in VS Marketplace](https://developer.microsoft.com/blog/security-and-trust-in-visual-studio-marketplace)
- [vscode#152897 hover provider order](https://github.com/microsoft/vscode/issues/152897)
- [vscode#228835 hover hiding-delay](https://github.com/microsoft/vscode/issues/228835)
- [vscode-discussions#2574 hover refresh in-place](https://github.com/microsoft/vscode-discussions/discussions/2574)
- [vscode#196718 extension sync across profiles](https://github.com/microsoft/vscode/issues/196718)
- [Raycast Preferences](https://developers.raycast.com/api-reference/preferences)
- [Raycast Actions](https://developers.raycast.com/api-reference/user-interface/actions)
- [Raycast Manifest](https://developers.raycast.com/information/manifest)
- [Raycast Publish an Extension](https://developers.raycast.com/basics/publish-an-extension)
- [Raycast Security](https://developers.raycast.com/information/security)
- [Obsidian Commands](https://docs.obsidian.md/Plugins/User+interface/Commands)
- [Obsidian Plugin Security](https://obsidian.md/help/plugin-security)
- [JetBrains action-system](https://plugins.jetbrains.com/docs/intellij/action-system.html)
- [JSONForms docs](https://jsonforms.io/docs/)
- [RJSF uiSchema](https://rjsf-team.github.io/react-jsonschema-form/docs/api-reference/uiSchema/)
- [JSON Schema custom annotations](https://json-schema.org/blog/posts/custom-annotations-will-continue)
- [Command Palette pattern (uxpatterns.dev)](https://uxpatterns.dev/patterns/advanced/command-palette)
- [Command palette past/present/future (command.ai)](https://www.command.ai/blog/command-palette-past-present-and-future/)
- [Superhuman: building a command palette](https://blog.superhuman.com/how-to-build-a-remarkable-command-palette/)
- [Linear command menu walkthrough](https://www.storylane.io/tutorials/how-to-use-linears-command-menu)
