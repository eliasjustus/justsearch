// SPDX-License-Identifier: Apache-2.0
import { css } from 'lit';

/**
 * Tempdoc 621 Phase 1 — the chat window's body styles, extracted verbatim from UnifiedChatView.ts.
 * Behaviour-neutral move: the host's `static styles` array still composes this alongside the imported
 * style modules and the in-host `composeGridStyles(CONVERSATION_ZONES)` call (the composition-surfaces
 * seam stays in the host file).
 */
export const unifiedChatBodyStyles = css`
    :host {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      padding: 1rem;
      gap: 0.75rem;
      box-sizing: border-box;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    /* Tempdoc 585 §D Phase 1 (C1) — run-replay scrubber bar. */
    .replay-bar {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      margin-bottom: 0.5rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
    }
    .replay-label { flex: 0 0 auto; color: var(--text-secondary); }
    .replay-slider { flex: 1 1 auto; }
    .replay-btn, .replay-exit, .replay-fork {
      flex: 0 0 auto;
      cursor: pointer;
      color: inherit;
      background: var(--surface-hover);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.125rem 0.5rem;
      font-size: var(--font-size-sm);
    }
    .replay-btn[disabled] { opacity: 0.4; cursor: default; }
    /* Tempdoc 585 §D Phase 3 (C2) — the inline fork editor. */
    .fork-editor {
      display: flex;
      gap: 0.5rem;
      align-items: flex-end;
      margin-bottom: 0.5rem;
    }
    .fork-input {
      flex: 1 1 auto;
      min-height: 2.5rem;
      resize: vertical;
      font: inherit;
      color: inherit;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
    .fork-run {
      flex: 0 0 auto;
      cursor: pointer;
      color: inherit;
      background: var(--accent-primary-weak);
      border: 1px solid var(--accent);
      border-radius: 0.375rem;
      padding: 0.375rem 0.75rem;
      font-size: var(--font-size-sm);
    }
    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding-bottom: 0.5rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .shape-indicator {
      font-size: var(--font-size-xs);
      padding: 0.15rem 0.4rem;
      border-radius: 0.25rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
    }
    /* Tempdoc 561 P-B3 (Tier-1 fix): both planes are always present and the inactive one is
       [hidden]; the [hidden] rule below must win over the planes' own display:flex, hence the
       !important. [hidden] also removes the plane (and its descendants) from the tab order and
       a11y tree, so focus and landmarks stay correct without extra wiring. */
    [hidden] {
      display: none !important;
    }
    .answer-plane {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    /* Tempdoc 565 §12.3.D — the three-zone composition. Single column by default (narrow / non-agent);
       at a wide viewport it becomes [conversation | evidence-rail], the group centred so the answer
       stops sprawling full-bleed (Probe R: ~76% empty at 2400px) and the right margin is consumed by
       the persistent grounding rail rather than dead gutter. */
    .conversation-zone {
      flex: 1;
      min-height: 0;
      /* §13 Pillar B — the grid FRAME (display:grid + grid-template-columns + gap + per-zone
         placement) is GENERATED from CONVERSATION_ZONES by composeGridStyles (appended to the styles
         array below). This block keeps only the zone's flex sizing (container role, not the frame). */
    }
    /* Tempdoc 565 §12.3.D/F — the left run-spine: a persistent vertical status minimap of the latest run
       in the conversation's left margin (the answer the terminal node), so the run reads as one ordered,
       status-scannable timeline at a glance. Hidden below the wide breakpoint; positioned just-left of
       the centred conversation+rail group (≈61rem) so it sits beside the column, not in the dead margin.
       Fixed (the zone does not scroll; only .conversation does) — a minimap, not an inline duplicate. */
    .run-spine {
      display: none;
    }
    @media (min-width: 64rem) {
      .run-spine {
        /* Tempdoc 565 §19.4 — a POSITION-PROPORTIONAL minimap: a full-height relative track within the
           generated grid zone. Each node is absolutely placed at its conversation item's scroll
           fraction (§19.4 computeSpinePositions), so the spine MAPS the conversation (scroll position
           ↔ node position) rather than top-packing a flex stack. The zone does not scroll (only
           .conversation does); decorative + aria-hidden — a minimap, not a duplicate. */
        /* grid-column is GENERATED (composeGridStyles over CONVERSATION_ZONES). */
        justify-self: center;
        align-self: stretch;
        position: relative; /* anchor for ::before + the absolutely-placed nodes */
        display: block;
        width: 0.9rem;
      }
      .run-spine::before {
        content: '';
        position: absolute;
        top: 0.3rem;
        bottom: 0.3rem;
        left: 50%;
        /* §16.3 — a legible connector: the spine must read as one continuous thread, not scattered dots. */
        width: 2px;
        transform: translateX(-50%);
        background: var(--border-subtle);
      }
      /* §21 AFFORDANCE — the viewport indicator is an OPERABLE scrollbar thumb (role="scrollbar"): drag it
         to scroll the reading column (the minimap IS the scrollbar). A comfortable grab width + grab cursor;
         it sits BEHIND the operable jump-nodes (lower z-index) so a dot click still hits the dot and an
         empty-track grab hits the thumb. The accent band still reads as the on-screen "you are here" slice. */
      .run-spine-viewport {
        position: absolute;
        left: 50%;
        width: 0.9rem;
        min-height: 0.6rem;
        transform: translateX(-50%);
        border-radius: 0.45rem;
        background: var(--accent-tint);
        opacity: 0.16;
        cursor: grab;
        touch-action: none;
        z-index: 0;
        transition:
          top var(--duration-instant) linear,
          height var(--duration-instant) linear;
      }
      .run-spine-viewport:hover {
        opacity: 0.26;
      }
      .run-spine-viewport:active {
        cursor: grabbing;
        opacity: 0.34;
      }
      .run-spine-viewport:focus-visible {
        outline: 2px solid var(--accent-tint);
        outline-offset: 2px;
        opacity: 0.34;
      }
    }
    .run-spine-node {
      /* §19.4 — absolutely placed at the conversation scroll fraction; the top + the box --node-size
         (the §19.3 declared PROMINENCE_SCALE) + opacity are set inline. The translate centres the
         node on (fraction, connector). */
      position: absolute;
      left: 50%;
      top: 0;
      /* §21 — the jump-nodes layer ABOVE the scrollbar thumb (z-index:0) so a dot click hits the dot
         (click-jump) while an empty-track grab hits the thumb (drag-scroll). */
      z-index: 1;
      width: var(--node-size, 0.55rem);
      height: var(--node-size, 0.55rem);
      transform: translate(-50%, -50%);
      border-radius: 50%;
      box-sizing: border-box;
      /* §17 — the button is the sized DISC wrapper; <jf-run-node> renders the glyph+tone visual inside.
         A surface disc + subtle ring covers the connector behind each node so it reads as segments. */
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      /* §13 Pillar A — the node is an operable jump control (a <button>); reset the chrome. */
      appearance: none;
      padding: 0;
      margin: 0;
      cursor: pointer;
    }
    .run-spine-node:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    /* §13 Pillar A — the scroll-spy active node (the current reading position) gets a tint ring. */
    .run-spine-node.active {
      box-shadow: 0 0 0 3px var(--accent-tint);
    }
    @media (min-width: 64rem) {
      .conversation-zone {
        /* §13 Pillar B — the wide grid-template-columns + the per-zone placements (.run-spine col 2,
           .conversation col 3, .evidence-rail col 4) are GENERATED from CONVERSATION_ZONES by
           composeGridStyles. §13.9 — the outer margins are CAPPED (minmax(0, 8rem)) and the
           spine/rail tracks are content-sized (collapse when unmounted), so the group sits fuller-width
           with bounded gutter rather than centred in unbounded 1fr margin. justify-content centres the
           bounded track group (no left-shift) while the capped margin tracks stay the gutter floor. */
        width: 100%;
        justify-content: center;
      }
    }
    .conversation {
      flex: 1;
      min-width: 0;
      min-height: 0;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      /* §13.9 — these scroll regions live in the shadow root, so the global ::-webkit-scrollbar styling
         in tokens.css can't reach them and they fell back to a chunky 15px native bar mid-column. Use the
         standard thin + subtle scrollbar (the app's convention) so the affordance is unobtrusive. */
      scrollbar-width: thin;
      scrollbar-color: var(--border-subtle) transparent;
    }
    /* §21 AFFORDANCE — when the run-spine is mounted (agent + wide) it IS the scroll control, so hide the
       reading column's native scrollbar; scrollbar-gutter:stable reserves the gutter so hiding it
       causes no reflow (the Spike A 10px shift). The thin native bar stays the fallback otherwise. */
    .conversation.spine-scrolled {
      scrollbar-width: none;
      scrollbar-gutter: stable;
    }
    /* The webkit/Chromium half of "hide the native bar" is the ambient \`.jf-scrollbar-none\` utility
       (574 §16 — ::-webkit-scrollbar is a Class-B shadow-scoped pseudo, owned by ambientStyles); the
       template adds that class alongside \`spine-scrolled\`. */
    .evidence-rail {
      display: none;
      min-width: 0;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      scrollbar-width: thin;
      scrollbar-color: var(--border-subtle) transparent;
    }
    @media (min-width: 64rem) {
      .evidence-rail {
        /* grid-column:4 is GENERATED (composeGridStyles over CONVERSATION_ZONES). §13.9 — the rail's
           track is fit-content(20rem), which sizes to the element's content and COLLAPSES to 0 when the
           rail is unmounted (Documents/RAG mode / no sources). These bounds give it a 15-20rem width WHEN
           present so it doesn't shrink below a legible rail. */
        display: block;
        min-width: 15rem;
        max-width: 20rem;
      }
    }
    /* Tempdoc 561 P-B3: the action plane fills the one window's body; the hosted agent view
       provides its own scroll + composer below the shared affordance bar. */
    .agent-plane {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
    .agent-plane jf-agent-view {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }
    .message {
      max-width: 85%;
      padding: 0.5rem 0.75rem;
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
      line-height: 1.5;
      white-space: pre-wrap;
    }
    .message.user {
      align-self: flex-end;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    /* Tempdoc 577 §2.14 Root I (#19) — the ambient turn-boundary timestamp under the user turn. */
    .turn-time {
      display: block;
      margin-top: 0.2rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      opacity: 0.7;
      text-align: right;
    }
    /* Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam between restored history and the
       live run: a faint full-width rule with a centred label, so a resumed thread reads as two runs. */
    .run-seam {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0.6rem 0;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .run-seam::before,
    .run-seam::after {
      content: '';
      flex: 1;
      border-top: 1px dashed var(--border-subtle);
    }
    .run-seam-label {
      text-transform: uppercase;
      letter-spacing: 0.06em;
    }
    .message.assistant {
      align-self: flex-start;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      max-width: 95%;
      /* Tempdoc 565 §12.3.B — assistant messages hold components (markdown block, tool-call card)
         that manage their own whitespace. The parent .message pre-wrap (for raw user text) INHERITS
         across the shadow boundary into the tool-call card, rendering every template newline as
         visible vertical whitespace (the ~200px of empty space that bloated the tool cards). Reset
         it so the rows are dense; user messages keep pre-wrap. */
      white-space: normal;
    }
    /* Tempdoc 565 §3.C — a reading measure: prose answers don't run edge-to-edge across the full
       window. Caps the line length of the canonical answer block; code/tables inside markdown can
       still overflow-scroll on their own. Applies to every mode (agent + chat/RAG). */
    .message.assistant jf-markdown-block {
      display: block;
      max-width: 88ch;
    }
    /* Tempdoc 565 §15.C (fix) — the explicit workflow run affordance (replaces auto-run-on-mount). */
    .workflow-trigger {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem 0;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    /* Tempdoc 565 §26.C — the workflow picker (replaces the single-id label). */
    .workflow-picker-label {
      font-weight: 600;
    }
    .workflow-picker {
      font: inherit;
      color: var(--text-primary);
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0.2rem 0.4rem;
    }
    .message-shape-tag {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.2rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    /* Tempdoc 565 §12.3.C/F — the run trace: a collapsible, status-coloured spine that subordinates
       the run so the answer leads. Closed = one summary line; open = a vertical spine of status nodes. */
    .run-trace {
      align-self: stretch;
    }
    /* Tempdoc 565 §26.B — a workflow NODE rendered as a labelled segment: a header naming the node
       over its steps. The node structure §15.C flattened, made visible without nesting the item model. */
    .run-segment {
      align-self: stretch;
      border-left: 2px solid var(--border-subtle);
      padding-left: 0.6rem;
      margin: 0.2rem 0;
    }
    .run-segment-header {
      display: inline-flex;
      align-items: center;
      gap: 0.5ch;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.2rem;
    }
    .run-segment-name {
      font-weight: 600;
    }
    .run-segment-kind {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0 0.35ch;
      text-transform: lowercase;
    }
    /* §26.D — a background run reads as a dashed-edge segment (it ran "while you were away"). */
    .run-segment.origin-background {
      border-left-style: dashed;
    }
    /* Tempdoc 565 §26.B — a spine node that opens a workflow node segment: a small node-boundary tick. */
    .run-spine-node.node-boundary::after {
      content: '';
      position: absolute;
      left: -0.35rem;
      top: 50%;
      width: 0.3rem;
      height: 1px;
      background: var(--text-tertiary);
      transform: translateY(-50%);
    }
    /* Tempdoc 565 §30 — a human STEERING directive is a distinct human-origin spine landmark (accent ring). */
    .run-spine-node.steer-landmark::before {
      content: '';
      position: absolute;
      inset: -0.18rem;
      border-radius: 50%;
      border: 1px solid var(--accent-command);
    }
    /* Tempdoc 565 §30 — the "Your direction" body chip for an acknowledged steer (human-origin, accent). */
    .steer-directive {
      display: inline-flex;
      align-items: baseline;
      gap: 0.5ch;
      padding: 0.2rem 0.55rem;
      border-radius: 0.4rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      font-size: var(--font-size-xs);
    }
    .steer-directive__label {
      font-weight: 600;
      letter-spacing: 0.04ch;
    }
    /* Tempdoc 565 §30 — the live-run steer input row (the DIRECTION authority's interject affordance). */
    .run-steer {
      display: flex;
      gap: 0.5ch;
      align-items: center;
      margin-bottom: 0.4rem;
    }
    .run-steer__input {
      flex: 1;
      padding: 0.35rem 0.6rem;
      border-radius: 0.4rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-hover);
      color: var(--text-primary);
      font-size: var(--font-size-xs);
    }
    /* Tempdoc 565 §29 Tier-2 — per-segment elapsed time (dim, right of the node name). */
    .run-segment-elapsed {
      margin-left: auto;
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
      font-variant-numeric: tabular-nums;
    }
    /* Tempdoc 565 §29 Tier-2 — a run-health tick: an error step reads in the danger tone on the spine. */
    .run-spine-node.has-error::before {
      content: '';
      position: absolute;
      inset: -0.18rem;
      border-radius: 50%;
      border: 1px solid var(--accent-danger);
    }
    .run-trace-summary {
      list-style: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 0.45ch;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.15rem 0.4rem;
      border-radius: 0.3rem;
      user-select: none;
    }
    .run-trace-summary::-webkit-details-marker {
      display: none;
    }
    .run-trace-summary:hover {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .run-trace-caret {
      font-size: var(--font-size-xs);
      opacity: 0.8;
    }
    .trace-spine {
      margin: 0.3rem 0 0.3rem 0.5rem;
      padding-left: 0.85rem;
      border-left: 1px solid var(--border-subtle);
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .trace-row {
      position: relative;
    }
    /* §17 — the trace node is the sized DISC wrapper for <jf-run-node> (glyph + tone), sitting on the
       trace connector (.trace-spine border-left). Bigger than the spine node so the glyph reads. */
    .trace-node {
      position: absolute;
      left: -1.22rem;
      top: 0.2em;
      width: 0.72rem;
      height: 0.72rem;
      border-radius: 50%;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      box-sizing: border-box;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
    }
    /* §16.4/§17 — the run-step label: the backend's already-human message, NOT CSS-uppercased. */
    .trace-label {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      line-height: 1.4;
    }
    /* Tempdoc 565 §12.3.C — declared prominence: secondary recedes (dense), ambient is faint. */
    .trace-row.prominence-secondary {
      font-size: var(--font-size-md);
    }
    .trace-row.prominence-ambient {
      font-size: var(--font-size-sm);
      opacity: 0.72;
    }
    .trace-row.prominence-ambient .message-shape-tag {
      margin-bottom: 0;
    }
    /* Tempdoc 565 §12.3.E — the source-chip row: compact grounding chips under the answer, the third
       ambient-grounding surface (inline [n] · chips · rail), cross-highlighted via the selectedSource store. */
    /* §13.8 P3 — the "Sources · N" disclosure (mirrors CitationsPanel's "N sources" toggle). */
    /* Tempdoc 565 §14 ④/⑤ — the grounding-honesty badge (readiness + N-of-M coverage), beside the answer. */
    /* Tempdoc 577 Move 4 — the grounding badge is a native <details> disclosure (the "Why uncited?"
       tier): the summary is the inline "Grounded · N of M" chip; expanding reveals the breakdown +
       why the uncited sentences carry no mark. Keyboard/AT-accessible by construction. */
    .grounding-badge {
      margin-top: 0.4rem;
      font-size: var(--font-size-xs);
      letter-spacing: 0.02em;
      color: var(--text-success);
    }
    .grounding-badge-summary {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      cursor: pointer;
      list-style: none;
    }
    .grounding-badge-summary::-webkit-details-marker {
      display: none;
    }
    .grounding-why {
      margin-top: 0.3rem;
      color: var(--text-secondary);
      font-style: italic;
      max-width: 60ch;
    }
    /* Tempdoc 577 §2.12 Move 3 — the epistemic answer FRAME line (generalizes the round-1
       uncited-note): a one-line trust header above an answer that is not fully grounded, so an
       ungrounded model answer cannot pose as index-grounded. Ambient by default; the ungrounded
       arm is marked a touch more present (it is the trust-critical case). */
    .answer-frame {
      margin-bottom: 0.4rem;
      font-size: var(--font-size-xs);
      font-style: italic;
      color: var(--text-secondary);
    }
    .answer-frame-ungrounded {
      color: var(--text-warning);
      font-style: normal;
    }
    /* Tempdoc 603 D-4 — the SOURCED frame: the answer drew on retrieved documents but per-sentence
       grounding was not verified (document-level retrieval). Informational, NOT a warning — a calm
       secondary note (provenance is real), distinct from the italic default and the warning ungrounded arm. */
    .answer-frame-sourced {
      color: var(--text-secondary);
      font-style: normal;
    }
    /* Tempdoc 603 C3 — an extraction (transform) is the model's own structuring, not retrieved data.
       Clean JSON reads as authoritative, so the marker is UNMISSABLE (a tinted, bordered strip that
       abuts the result below), not the subtle italic header — a user skimming the values can't miss it. */
    .answer-frame-transform {
      color: var(--text-warning);
      font-style: normal;
      font-weight: 500;
      padding: 0.3rem 0.5rem;
      border-left: 3px solid var(--accent-warning);
      background: var(--surface-2);
      border-radius: 0.25rem 0.25rem 0 0;
      margin-bottom: 0;
    }
    /* Tempdoc 603 C2 — the decontextualized standalone question a follow-up's retrieval ran on. */
    .rewrite-note {
      margin-bottom: 0.4rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .grounding-dot {
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--accent-success);
    }
    .source-disclosure {
      margin-top: 0.5rem;
    }
    .source-disclosure-summary {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.1rem 0;
      font-family: inherit;
      font-size: var(--font-size-xs);
      letter-spacing: 0.03em;
      color: var(--text-secondary);
      background: none;
      border: none;
      cursor: pointer;
    }
    .source-disclosure-summary:hover,
    .source-disclosure-summary:focus-visible {
      color: var(--text-primary);
      outline: none;
    }
    .source-disclosure-summary:focus-visible {
      text-decoration: underline;
    }
    .source-disclosure .disclosure-chevron {
      display: inline-block;
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .source-disclosure .disclosure-chevron { transition: none; }
    }
    .source-disclosure .disclosure-chevron.open {
      transform: rotate(90deg);
    }
    .source-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-top: 0.35rem;
    }
    /* Tempdoc 610 §J.3 — the source chip + its hide/restore toggle, as one unit. */
    .source-chip-wrap {
      display: inline-flex;
      align-items: center;
    }
    .source-chip-wrap.hidden-source .source-chip {
      opacity: 0.45;
      text-decoration: line-through;
    }
    .source-exclude {
      all: unset;
      cursor: pointer;
      margin-left: 0.15rem;
      padding: 0 0.3rem;
      font-size: var(--font-size-xs);
      line-height: 1;
      color: var(--text-muted);
      border-radius: 0.75rem;
    }
    .source-exclude:hover,
    .source-exclude:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: none;
    }
    .source-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.45ch;
      max-width: 24ch;
      padding: 0.15rem 0.55rem;
      font-size: var(--font-size-xs);
      font-family: inherit;
      border: 1px solid var(--border-subtle);
      border-radius: 1rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .source-chip:hover,
    .source-chip:focus-visible {
      background: var(--surface-hover);
      color: var(--text-primary);
      border-color: var(--accent-command);
      outline: none;
    }
    .source-chip.selected {
      background: var(--surface-hover);
      border-color: var(--accent-command);
      color: var(--text-primary);
    }
    .source-chip-n {
      font-weight: 600;
      color: var(--text-tint);
    }
    .source-chip-name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .extract-output {
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.75rem;
      font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, monospace;
      font-size: var(--font-size-sm);
      overflow-x: auto;
      white-space: pre-wrap;
      word-break: break-word;
    }
    .affordance-bar {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      padding-bottom: 0.25rem;
    }
    /* Tempdoc 561 C-2: the supervision dial sits at the trailing edge of the crossing control. */
    .affordance-dial {
      margin-left: auto;
    }
    /* Tempdoc 561 surface tier: the retrospective "Activity" button sits at the trailing edge. */
    .affordance-trailing {
      margin-left: auto;
    }
    /* Tempdoc 561 C-2: the rail summary names the approval posture (graded by the dial). */
    .activity-rail > summary {
      color: var(--text-secondary);
    }
    .affordance-btn {
      padding: 0.2rem 0.5rem;
      font-size: var(--font-size-xs);
      border-radius: 0.25rem;
      border: 1px solid var(--border-subtle);
      background: transparent;
      color: var(--text-secondary);
      cursor: pointer;
      transition: all var(--duration-fast) var(--ease-standard);
    }
    .affordance-btn:hover {
      border-color: var(--accent-tint);
      color: var(--text-primary);
    }
    /* Tempdoc 577 Ext III — the retrospective toggle is a panel control, not a posture: a gap
     * separates it from the autonomy dial so it cannot read as a fourth segment. */
    .retrospective-toggle {
      margin-left: 0.75rem;
    }
    /* Tempdoc 565 §12.3.E — at the wide breakpoint the persistent evidence rail replaces the toggle
       drawer, so the "Sources · N" affordance (which opens that drawer) is redundant and hidden. */
    @media (min-width: 64rem) {
      .sources-affordance {
        display: none;
      }
    }
    .affordance-btn.active {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-color: var(--accent-tint);
    }
    .affordance-btn:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
    .affordance-btn:disabled:hover {
      border-color: var(--border-subtle);
      color: var(--text-secondary);
    }
    /* Tempdoc 596 — the capability tabs are jf-control. The visual box is the inner button
       (::part(control)); the host is a bare wrapper so the base .affordance-btn box doesn't
       double up. The dimmed unavailable look is owned by jf-control (button[aria-disabled]). */
    jf-control.affordance-btn {
      border: none;
      background: transparent;
      padding: 0;
    }
    jf-control.affordance-btn::part(control) {
      padding: 0.2rem 0.5rem;
      font-size: var(--font-size-xs);
      border-radius: 0.25rem;
      border: 1px solid var(--border-subtle);
      background: transparent;
      color: var(--text-secondary);
      cursor: pointer;
      transition: all var(--duration-fast) var(--ease-standard);
    }
    jf-control.affordance-btn:hover::part(control) {
      border-color: var(--accent-tint);
      color: var(--text-primary);
    }
    jf-control.affordance-btn.active::part(control) {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-color: var(--accent-tint);
    }
    /* Tempdoc 561 P-A/P-B (Slice 3): the secondary Activity rail — demoted agent chrome (budget),
       collapsible so the conversation stays primary. */
    .activity-rail {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      padding: 0.2rem 0.4rem;
      background: var(--surface-2);
    }
    .activity-rail summary {
      cursor: pointer;
      user-select: none;
    }
    .activity-lifecycle {
      padding-top: 0.25rem;
      color: var(--text-secondary);
    }
    .activity-lifecycle .lifecycle-state {
      color: var(--text-primary);
      font-weight: 600;
    }
    .activity-budget {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding-top: 0.25rem;
    }
    .activity-budget .budget-bar,
    .context-meter .budget-bar {
      flex: 1;
      height: 4px;
      border-radius: 2px;
      background: var(--surface-1);
      overflow: hidden;
    }
    .activity-budget .budget-bar-fill,
    .context-meter .budget-bar-fill {
      height: 100%;
      background: var(--accent-tint);
    }
    /* Tempdoc 577 §2.14 Root II (#14) — the context-headroom meter's status-graded fill (these are
       BACKGROUND fills, not text, so the accent-* status tokens are the right authority here). The
       chat-surface context-budget meter (610 §E.4) shares this one fullness→colour authority. */
    .activity-context .budget-bar-fill.context-fill-green,
    .context-meter .budget-bar-fill.context-fill-green {
      background: var(--accent-success);
    }
    .activity-context .budget-bar-fill.context-fill-yellow,
    .context-meter .budget-bar-fill.context-fill-yellow {
      background: var(--accent-warning);
    }
    .activity-context .budget-bar-fill.context-fill-red,
    .context-meter .budget-bar-fill.context-fill-red {
      background: var(--accent-danger);
    }
    /* Tempdoc 610 §E.4 — the chat context-budget meter row, sits just above the composer. */
    .context-meter {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
      padding: 0.15rem 0.25rem 0.35rem;
    }
    .context-meter-label {
      color: var(--text-muted);
      font-size: var(--font-size-xs);
      white-space: nowrap;
    }
    /* Tempdoc 610 §K — the meter label doubles as the inspector trigger (opens "what the assistant sees"). */
    .context-meter-trigger {
      all: unset;
      cursor: pointer;
      color: var(--text-muted);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      border-radius: 0.2rem;
    }
    .context-meter-trigger:hover,
    .context-meter-trigger:focus-visible {
      color: var(--text-primary);
      text-decoration: underline;
      outline: none;
    }
    /* Tempdoc 610 §I.2 — the per-phase attribution, revealed on hover/focus (compact by default). */
    .context-meter-breakdown {
      flex-basis: 100%;
      display: none;
      color: var(--text-muted);
      font-size: var(--font-size-xs);
    }
    .context-meter:hover .context-meter-breakdown,
    .context-meter:focus-within .context-meter-breakdown {
      display: block;
    }
    .context-meter-breakdown .cmb-est {
      color: var(--text-tint);
      font-style: italic;
    }
    /* Tempdoc 610 §I.2 — the "N turns hidden · Include all" aggregate row. */
    .excluded-summary {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.1rem 0.25rem 0.35rem;
    }
    .excluded-summary-label {
      color: var(--text-muted);
      font-size: var(--font-size-xs);
    }
    .excluded-summary-action {
      all: unset;
      cursor: pointer;
      color: var(--text-primary);
      font-size: var(--font-size-xs);
      text-decoration: underline;
      padding: 0.1rem 0.3rem;
      border-radius: 0.2rem;
    }
    .excluded-summary-action:hover,
    .excluded-summary-action:focus-visible {
      background: var(--surface-2);
    }
    .activity-rail .over-budget {
      color: var(--text-danger);
      font-weight: 600;
    }
    /* Tempdoc 577 Ext III — posture POLICY text is grammatically + typographically distinct from
     * live status (italic secondary, never the bold status treatment). */
    .activity-rail .posture-policy {
      font-style: italic;
      font-weight: 400;
    }
    /* Tempdoc 577 Ext III — the over-budget remedies (halt / raise) beside the figure. */
    .budget-actions {
      display: inline-flex;
      gap: 0.375rem;
    }
    .budget-action {
      padding: 0.1rem 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    /*
     * Override imported composerStyles for UnifiedChatView's outer wrapper:
     * the composer is a vertical stack (affordance bar / preview / schema
     * input / inner composer-row) rather than the row layout used by the
     * four simple chat views. The inner .composer-row IS the row-flex.
     */
    .composer {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .composer-row {
      display: flex;
      gap: 0.5rem;
      align-items: stretch;
    }
    /* Schema input textarea: not migrated to jf-composer (no submit). */
    .composer textarea.mono {
      font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, monospace;
      font-size: var(--font-size-sm);
    }
    .schema-label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .error {
      padding: 0.5rem 0.75rem;
      background: var(--accent-danger-08);
      border: 1px solid var(--accent-danger-30);
      border-radius: 0.375rem;
      color: var(--text-danger);
      font-size: var(--font-size-sm);
    }
    .preamble {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.35rem 0.5rem;
      background: var(--surface-2);
      border-radius: 0.25rem;
      font-style: italic;
    }
    .resume-prompt {
      padding: 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    /* Tempdoc 577 §2.13 #17 — the authority-space panel container (toggled by the Abilities affordance). */
    .abilities-panel {
      padding: 0.75rem;
      margin-bottom: 0.5rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .resume-prompt em {
      color: var(--text-primary);
      font-style: normal;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .resume-actions {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.25rem;
    }
    .resume-btn {
      all: unset;
      font-size: var(--font-size-xs);
      padding: 0.2rem 0.6rem;
      border-radius: 0.25rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      cursor: pointer;
    }
    .dismiss-btn {
      all: unset;
      font-size: var(--font-size-xs);
      padding: 0.2rem 0.6rem;
      color: var(--text-muted);
      cursor: pointer;
    }
    .dismiss-btn:hover {
      color: var(--text-primary);
    }
    .new-chat-btn {
      all: unset;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      cursor: pointer;
      padding: 0.15rem 0.4rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      margin-left: 0.5rem;
    }
    .new-chat-btn:hover {
      color: var(--text-primary);
      border-color: var(--accent-tint);
    }
    .affordance-preview {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      padding: 0.15rem 0;
    }
    /* Tempdoc 610 §D.2 — the ONE per-turn action bar (ChatGPT/Claude grammar):
       a hover-revealed icon row BELOW the message. Assistant: inside the message
       box (bottom). User: a right-aligned sibling row beneath the bubble. */
    .turn-actions {
      display: flex;
      align-items: center;
      gap: 0.1rem;
      margin-top: 0.2rem;
      opacity: 0;
      transition: opacity var(--duration-fast);
    }
    .turn-actions.user-actions {
      align-self: flex-end;
    }
    .turn-actions.assistant-actions {
      align-self: flex-start;
    }
    /* Reveal: assistant bar lives inside the message; user bar is a sibling. The
       bar stays visible while the pointer is on it (so the ⋯ is clickable). */
    .message.assistant:hover .turn-actions,
    .message.user:hover + .turn-actions,
    .turn-actions:hover {
      opacity: 1;
    }
    .turn-act-btn {
      all: unset;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      padding: 0.2rem;
      border-radius: 0.25rem;
      color: var(--text-muted);
      line-height: 0;
    }
    .turn-act-btn:hover {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    /* Tempdoc 610 §D.2 — edit morphs the bubble IN PLACE: keep the user turn's
       right-aligned position + bubble max-width (no full-width stretch); only
       drop the bubble fill so the textarea reads as an edit field. */
    .message.user.editing {
      background: transparent;
      color: var(--text-primary);
      padding: 0.25rem 0;
    }
    .msg-edit {
      width: 100%;
      min-height: 3rem;
      box-sizing: border-box;
      font: inherit;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0.4rem 0.5rem;
      resize: vertical;
    }
    .msg-edit:focus {
      outline: none;
      border-color: var(--accent-tint);
    }
    .msg-edit-actions {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.4rem;
      justify-content: flex-end;
    }
    .msg-edit-save,
    .msg-edit-cancel {
      all: unset;
      font-size: var(--font-size-xs);
      cursor: pointer;
      padding: 0.2rem 0.6rem;
      border-radius: 0.25rem;
    }
    .msg-edit-save {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    .msg-edit-cancel {
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
    }
    /* Tempdoc 610 Phase B — inline version pager (‹ n/m ›). */
    .version-pager {
      display: inline-flex;
      align-items: center;
      gap: 0.2rem;
      margin-left: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      vertical-align: middle;
    }
    .message.user .version-pager {
      color: var(--accent-on-tint);
    }
    .ver-nav {
      all: unset;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      border-radius: 0.2rem;
      padding: 0.05rem;
      color: inherit;
    }
    .ver-nav:hover:not(:disabled) {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .ver-nav:disabled {
      opacity: 0.35;
      cursor: default;
    }
    .ver-count {
      font-variant-numeric: tabular-nums;
    }
    /* Tempdoc 610 Phase C — context-floor divider + out-of-context band.
       Distinct from the ↪ inherited-prefix language: this means "the assistant
       no longer sees this", not "this came from a parent". */
    .context-floor-divider {
      align-self: stretch;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0.6rem 0;
      padding: 0.2rem 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      border-top: 1px solid var(--border-strong);
    }
    .context-floor-divider .cfd-label {
      flex: 1;
    }
    .cfd-restore {
      all: unset;
      cursor: pointer;
      color: var(--text-primary);
      text-decoration: underline;
      padding: 0.1rem 0.4rem;
      border-radius: 0.2rem;
    }
    .cfd-restore:hover {
      background: var(--surface-2);
    }
    .message.out-of-context {
      opacity: 0.45;
      filter: grayscale(0.4);
    }
    /* Tempdoc 610 §E.3 — a per-message exclusion: dimmed like the out-of-context band but marked
       with a dashed rail so "I hid this turn" reads distinct from "this is below the floor". */
    .message.excluded {
      opacity: 0.5;
      filter: grayscale(0.5);
      border-left: 2px dashed var(--border-strong);
      padding-left: 0.4rem;
    }
    .cfd-summary {
      align-self: stretch;
      margin: 0 0 0.6rem 0;
      padding: 0.4rem 0.6rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      background: var(--surface-2);
      border-left: 2px solid var(--border-strong);
      border-radius: 0.25rem;
      white-space: pre-wrap;
    }
    /* Tempdoc 610 §E.2 — in-place edit of the compaction summary. */
    .cfd-summary-editing {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .cfd-summary-input {
      width: 100%;
      min-height: 4rem;
      box-sizing: border-box;
      resize: vertical;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-primary);
      background: var(--surface-1);
      border: 1px solid var(--border-strong);
      border-radius: 0.25rem;
      padding: 0.4rem 0.6rem;
    }
    .cfd-summary-actions {
      display: flex;
      gap: 0.5rem;
    }
    /* Slice 513 — branch indicator above inherited prefix and per-message dim */
    .branch-indicator {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      padding: 0.25rem 0.5rem;
      margin-bottom: 0.5rem;
      border-left: 2px solid var(--accent-tint);
      background: var(--surface-2);
      font-style: italic;
    }
    .message.inherited {
      opacity: 0.7;
    }
    .message.inherited::before {
      content: '↪';
      color: var(--text-tint);
      margin-right: 0.4rem;
      font-style: normal;
    }
    .thinking-timer {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding: 0.5rem 0;
      font-variant-numeric: tabular-nums;
    }

    /* Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier: the ephemeral hit-list. */
    .retrieve-tier {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      padding: 0.25rem 0;
    }
    .retrieve-empty {
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      padding: 1rem 0.25rem;
    }
    .retrieve-empty strong {
      color: var(--text-primary);
    }
    .retrieve-meta {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      padding: 0 0.25rem;
    }
    .retrieve-results {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .retrieve-row {
      display: flex;
      flex-direction: column;
      padding: 0.5rem 0.625rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: var(--surface-1);
    }
    .retrieve-row:hover {
      background: var(--surface-hover);
    }
    .retrieve-row-open {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      width: 100%;
      text-align: left;
      padding: 0;
      border: none;
      background: none;
      color: var(--text-primary);
      cursor: pointer;
      font: inherit;
    }
    .retrieve-row-open:focus-visible {
      outline: 2px solid var(--focus-ring-color);
      outline-offset: 1px;
    }
    .retrieve-row-title {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      font-size: var(--font-size-sm);
    }
    .retrieve-row-title .kind-icon {
      display: inline-flex;
      color: var(--text-tertiary);
    }
    .retrieve-row-title .title-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .retrieve-row-title .line-anchor {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      font-variant-numeric: tabular-nums;
    }
    .retrieve-row-path {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .retrieve-row-snippet {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      line-height: 1.4;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
`;
