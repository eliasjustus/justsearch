---
name: blast-radius
description: >-
  Find what a change breaks somewhere else before it ships, and prove the one
  fact it is safe because of by running real code. Rare — run manually only.
---
<!-- generated from .claude/skills by scripts/docs/codex-skills-projection.mjs; do not edit -->

> Codex projection: `$skill-name` is the equivalent of a Claude `/skill-name` invocation. When this workflow names a Claude-only tool, use the available Codex capability that preserves the same policy and acceptance criteria.

Hand-authored for this repo, adapted from pstack's `blast-radius` (cursor/plugins @ `195d9359`). It is a vendored copy on purpose: nothing syncs it upstream, no regen step rewrites it, and it does not auto-invoke. Edit it in place when this repo's verification tiers change.

Your task is to find what a change breaks *somewhere else*, before it ships. Listing the callers is not the job — you can grep those in a second. The job is the breakage grep will not show you.

## Don't trust your own writeup

A blast-radius writeup that sounds right is worthless. It reads as convincing whether or not it is true, and that is the trap. So do not hand back the writeup. Find the one or two facts the whole thing depends on and prove them by running code. Words are where you start, not what you ship. This is `interrogate-results` applied to your own reasoning: the most convincing version of a finding is the one that agrees with what you already believed.

## The rung ladder

For each fact the change's safety depends on, get it as far down this list as is cheap, and **state the rung you stopped at**. A safety fact reported without a rung counts as rung 1.

1. **Asserted.** You said so. Worthless on its own.
2. **Cited.** A real `file:line` in this tree, or the dependency's own source in `~/.gradle` / `node_modules`.
3. **Walked.** You traced the failure path step by step and it cannot reach. Name the step where it dies.
4. **Ran.** Real code executed the exact path the fact is about, and it fails loud if you are wrong: `./gradlew.bat :modules:<module>:test`, a new regression test, `cd modules/ui-web && npm run test:unit:run`, or a throwaway node/python script under the scratchpad.
5. **Reproduced live.** The running stack did it: dev-stack MCP with `ai_activate` for anything AI-facing, `jseval ui-shot <step>` plus its `.measure.json` for anything visual or a11y-shaped, `capture_evidence` for a citable run-id.

**Predictable evasion — read this before claiming rung 4.** "The build is green and the suite passes" is *not* rung 4. Rung 4 means the specific code path your safety fact is about ran and would have failed. A green suite that never touches that path is rung 1 wearing a rung 4 costume — that is `unreachable-seed-green` and `subset-isnt-the-suite` in one move. Likewise, "I could not reach the live tier" is almost never true here: `ai_activate` loads a model in seconds and `ai-offline-isnt-a-wall` exists because three rounds were spent declaring otherwise. If rung 5 is genuinely unavailable, name the reason, do not round rung 4 up to it.

## Steps

1. **Read the change.** The diff, the symbols it adds, changes, and deletes, and what it now does differently — including the part the diff does not spell out.
2. **Find the one fact it is safe because of.** Most changes that look frightening are safe because of a single fact ("this only drops already-dead entries and does nothing else"). Find it. If it holds, most of the scary cases die at once. Spend your time here, not on a long list of maybes.
3. **Look where grep stops.** In this repo that means: the gRPC/protobuf contracts under `contracts/**` and anything generated from them; the dual-copy SSOT catalogs (`SSOT/catalogs/**` and the `adapters-lucene` resource copy); FE↔Java surface parity and the wire-schema codegen; the governance registers under `governance/*.v1.json` and whatever gate reads them; reason-code enums paired with their TypeScript renderers; on-disk Lucene index format and anything in `<dataDir>/runtime/`; the installer's staged model manifest. Also: a JSON shape an API returns, a feature flag's resolved default, code three hops downstream in another process. Head/Worker/Brain are separate processes — a change safe inside one can be a wire break across two.
4. **Be honest about each risk.** Real likelihood, real cost. Keep the risks you confirmed; list what you checked and cleared separately. Cite a real `file:line`. A search that finds nothing is still an answer. Never invent a caller or an API.
5. **Prove the one fact.** Write the script or test, run it, paste what happened. If you cannot prove it cheaply, mark it **unproven** and say which rung you reached. Do not round up.
6. **Log what you found outside this change's scope** with `node scripts/agent-analytics/note-observation.mjs "<desc> — \`<file:line>\`"` and keep going. Do not fix it here.

## What to hand back

- **What it does** — including the part that is not obvious from the diff.
- **The one fact it is safe because of** — stated plainly, with its rung, and the proof pasted. Or the word `unproven`.
- **Risks** — only the real ones. Each names how it breaks, a `file:line`, likelihood and cost, and the cheapest way to check.
- **Cleared** — what you checked and why it is fine. Nulls count: say what you searched for and did not find.
- **Before you merge** — the cheapest test or repro that catches the real bug, including the script you wrote.

This repo is public and PRs are visible immediately. If the blast radius touches a credential, a private path, or a security-sensitive seam, flag it in chat instead of writing the detail into a branch, PR, or tempdoc.
