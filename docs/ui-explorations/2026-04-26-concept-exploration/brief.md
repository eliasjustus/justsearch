# JustSearch UI — Concept Exploration Brief

## 1. What this is

A request for distinct UI direction concepts for JustSearch — not a redesign and not a single proposal.

The current UI works. There's no specific problem being solved. The goal is to see what JustSearch could look like from different starting points so I can react to them and figure out what I'm attached to. This is the "what if…?" phase.

I have no aesthetic preferences, no reference anchors, and no anti-references. Surprise me.

## 2. What JustSearch is

A **local-first desktop app for finding and reasoning over personal files**.

It indexes folders on the user's machine, searches across them with a hybrid of keyword + vector matching, and (optionally) runs a local LLM for summaries and Q&A. Everything stays on the device — no cloud, no telemetry leaves the machine.

The product category is open to interpretation. JustSearch isn't a notes app, isn't a cloud service, isn't a command launcher. Beyond those negatives, what kind of object it is — power tool, workspace, reading room, library, native utility, conversational agent, something else — is for each direction to decide.

## 3. The user (loose, refinable)

Someone with a meaningful collection of personal files — documents, PDFs, code, notes, images — who wants to find things and ask questions about them. Likely values privacy. Likely has tried OS-level search or cloud notes apps and found them lacking.

This is a sketch, not a persona. Each direction can refine or replace it.

## 4. The moments that matter

A direction's identity comes through in how it handles five experiences:

1. **First open.** What greets a new user before anything is indexed?
2. **Searching.** Typing a query and scanning results.
3. **Reading and understanding.** Inspecting one result.
4. **Asking the AI.** Posing a question (across one or many files) and reading the streamed answer with citations.
5. **Managing what's indexed.** Adding folders, seeing index status, knowing what's searchable.

Cover all five per direction. Dark mode required; light mode optional.

## 5. The ask

Produce **four UI direction concepts**. Each should:

- Have a strong, distinct point of view about who the product is for and what kind of object it is.
- Be internally consistent across the five moments.
- Come with a 1-page written rationale explaining the underlying belief and the trade-offs.

The four directions should clearly span the space — not variations on a theme. At least one should be willing to throw out conventional information architecture entirely.

## 6. Feelings to land anywhere on

I have no preferences on these axes. Each direction is free to position itself differently — and the four directions should differ across them, not converge:

- Calm ↔ alert
- Spacious ↔ dense
- Approachable ↔ technical
- Serious ↔ playful
- Foreground ↔ background (wants attention vs. fades into the OS)
- AI-prominent ↔ AI-quiet
- Modern ↔ timeless

These are examples, not a checklist. Articulate other axes if useful.

## 7. Hard constraints

These come from the product's nature, not taste:

- **Desktop app.** Windows-first, with macOS / Linux possible. Lives in a window, not a webpage.
- **System fonts only.** Segoe UI / SF Pro / system stack. No custom typography that requires network fonts.
- **Local-first / privacy is the brand.** Never imply cloud, syncing, or off-device storage. Phrasing and chrome should reinforce that files don't leave the machine.
- **Accessibility floor.** WCAG AA contrast in all themes, visible focus, screen-reader friendly, motion-reducing settings respected.
- **AI is opt-in.** The design must look complete without any AI surface. Some users will never install the LLM; their experience should feel finished, not crippled.
- **AI runtime is bimodal.** When installed, the system is either in "Online" mode (LLM loaded, chat available) or "Indexing" mode (embedding model loaded, vectorizing files), but never both simultaneously, due to GPU/VRAM. The user needs to know which mode they're in.
- **Narrow widths matter.** Usable down to roughly 960px window width.

## 8. What's open

Everything not in §7. Information architecture, navigation model, layout, aesthetic, density, modality, AI integration, naming, color, motion — all of it.

## 9. What success looks like

- Alternatives I wouldn't have come up with.
- Awareness of which assumptions are principle vs. habit.
- Raw material I can pull from later, even if I don't commit to one direction wholesale.
- Four directions different enough that picking favorites is a real choice.

**Not** looking for:

- Pixel-perfect fidelity. Mid-fidelity is plenty.
- A single recommended direction.
- Production-ready specs or component libraries.
- Marketing / brand work — out of scope.

## 10. Output

For each of the four directions:

- **Visual depictions** of all five moments (§4) at typical desktop dimensions. If visuals aren't possible, describe each moment in enough concrete detail to picture it.
- **A 1-page written rationale** explaining the underlying belief about the user and the product, and the trade-offs the direction makes.

Plus, after the four directions:

- **A comparison summary** — how the four differ on the axes that matter (target user, primary metaphor, AI integration, density, anything else relevant).
