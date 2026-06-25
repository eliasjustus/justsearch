---
title: Search UI Architecture & Behavioral Reference
type: tempdoc
status: open
created: 2026-02-21
updated: 2026-03-16
---

> **Scope**: This document constitutes the canonical architectural decision record and behavioral reference for JustSearch's UX and visual design. It defines the formal design constraints, interaction schemas, and empirical research grounding that dictate all frontend implementations.
>
> **Implementation status**: Sections 1–19 are the design theory. Section 20 defines the duration-adaptive model including the RAG answer streaming sub-arc and four-quadrant analysis. Section 21 tracks implementation phases (9 phases, all unchecked). Revised 2026-03-16: dual-experience framing, results-without-RAG, local RAG citation reframing, auto+override mode detection, override mechanism design, document preview, session model with hybrid query-rewriting, first-use anchoring bias, answer streaming sub-arc. Research grounding from 8 parallel investigation tracks (production AI search UX, desktop search, RAG citation, adaptive response format, loading states, first-use, session model, visual design).

# Search UI Architecture & Behavioral Reference

---

## Contents

1. [Vision](#1-vision)
2. [The Core UX Problem: Long-Running Search](#2-the-core-ux-problem-long-running-search)
3. [Q1 — Pipeline Stages vs. Continuous Narration](#3-q1--pipeline-stages-vs-continuous-narration)
4. [Q2 — Default Verbosity: Compact vs. Expanded](#4-q2--default-verbosity-compact-vs-expanded)
5. [Q3 — Streaming Partial Results vs. Holding Until Complete](#5-q3--streaming-partial-results-vs-holding-until-complete)
6. [Q4 — Emotional Tone: Clinical vs. Humanized Language](#6-q4--emotional-tone-clinical-vs-humanized-language)
7. [Synthesis: Recommended Design Position](#7-synthesis-recommended-design-position)
8. [The Interaction Arc](#8-the-interaction-arc)
9. [The Single-Input Paradigm](#9-the-single-input-paradigm)
10. [Results Presentation](#10-results-presentation)
11. [Unified Design Position](#11-unified-design-position)
12. [Visual Language](#12-visual-language)
13. [Motion Design Principles](#13-motion-design-principles)
14. [Error and Degraded States](#14-error-and-degraded-states)
15. [First-Use Experience](#15-first-use-experience)
16. [Accessibility as Design Constraint](#16-accessibility-as-design-constraint)
17. [Spatial Layout](#17-spatial-layout)
18. [Multi-Turn Interaction and Session Model](#18-multi-turn-interaction-and-session-model)
19. [Background Activity and Attention Recapture](#19-background-activity-and-attention-recapture)
20. [Duration-Adaptive Model](#20-duration-adaptive-model)
21. [Implementation Phases](#21-implementation-phases)

---

## 1. Vision

JustSearch dissolves into a single window with a single input field. The user types a query and gets results. Sometimes those results arrive in 200 milliseconds. Sometimes they take a minute. Both experiences must be excellent — and the UI theory in this document must serve both equally.

### Two co-equal experiences

**Fast search** is the baseline experience. For most queries on small-to-medium corpora, the pipeline completes in under two seconds. The user types, results appear, the interaction is immediate. This is the experience that must feel effortless and familiar — as good as the best traditional search interface, because most users will encounter it most of the time.

**Deep search** is the differentiating experience. When the corpus is large, RAG synthesis is active, or multiple expansion and reranking stages run, the pipeline may take 10–60 seconds. This is where JustSearch distinguishes itself: the minute-long wait is not a limitation to apologize for, it is a deliberate consequence of doing thorough work. The UX challenge is to make the work legible — the user should understand what is happening at each moment, feel confident the system is working correctly, and trust the result more because they watched it being produced.

The central design constraint is: **a minute of transparent, legible work is fundamentally different from a minute of black-box silence.** But a corollary constraint is equally important: **a 200-millisecond search should not be burdened with UI chrome designed for a 60-second pipeline.** The UI adapts to the duration of the actual search, not to a fixed assumption about how long search takes. Section 20 formalizes this as the duration-adaptive model; the rest of the theory should be read with both experiences in mind.

### The experience spectrum

The distinction between fast and deep search is not a mode the user selects — it is a consequence of corpus size, query characteristics, and available hardware. The same input field, the same submission gesture, the same results zone. What changes is which elements of the interaction arc (section 8) manifest visually and how much of the pipeline's work becomes visible. The UI adapts; the user just searches.

---

## 2. The Core UX Problem: Long-Running Search

### The labor illusion

Empirical validation by Buell & Norton (Harvard Business School, 2011, *Management Science*) definitively establishes the **labor illusion**: operational transparency—the systemic exposure of the labor involved in service delivery—increases perceived value and wait-time tolerance. Users value results more, and rate services higher, when they can observe the work being done, even when the underlying result is identical. Displaying the search system's active state is not merely anxiety management; it structurally alters how the result is perceived and valued.

This invalidates the foundational heuristic that "wait time must be minimized at all costs." For JustSearch, the formal architectural stance holds: **pipeline duration is a critical trust signal when communicated through operational transparency, and a trust-deficit when obscured.**

**Applicability note:** The labor illusion applies specifically to the deep search experience (section 1) — searches lasting more than ~2 seconds where the user is consciously waiting. For fast searches (<300ms), no transparency mechanism is needed because the results are effectively instant. For the intermediate range (300ms–2s), a brief acknowledgment is sufficient. The duration-adaptive model (section 20) formalizes these thresholds; the four questions below should be read as applying primarily to the deep search case, with section 20 governing how they scale down for faster searches.

### The four open design questions

With the vision established, four concrete design questions require resolution before any specific UI can be designed:

1. **Stages vs. narration** — how to structure the display of pipeline progress
2. **Default verbosity** — how much to show by default vs. on demand
3. **Streaming vs. holding** — whether to surface partial results as they arrive
4. **Language tone** — clinical/technical vs. humanized phrasing of status messages

Each is addressed below with reference to the available research.

---

## 3. Q1 — Pipeline Stages vs. Continuous Narration

### The question

Should the UI show discrete labeled stages ("Stage 1: Keyword search... Stage 2: Semantic expansion...") or a continuous narrative stream ("Scanning 847 documents... found 23 candidates...") during the pipeline?

### What the research says

**Evidence strength: Moderate-to-strong. Research clearly favors labeled stages.**

**Maister (1985), "Psychology of Waiting Lines"** is the foundational work. His propositions — validated repeatedly over 40 years, including a 2025 SSRN review — establish that uncertain and unexplained waits feel significantly longer than explained and bounded ones. A continuous narration stream fails Maister's criteria: it signals busyness but does not give the user a structural model of where they are in the process or when it will end.

**Nielsen Norman Group** recommends that for waits exceeding 10 seconds where percent-done estimates are not available, designers should "indicate relative progress by providing a list of completed and remaining steps, and indicate current step when appropriate." This is the explicit staged-steps pattern.

**A 2022 time-perception study** (*Scientific Reports*, "Malleability of time through progress bars and throbbers") found that more discrete steps produce an *underestimation* of elapsed time. Fewer, coarser steps produce a perceived dilation. For a 60-second pipeline, more named stages is better than fewer.

**The peak-end rule** (Kahneman et al., 1993) argues that users remember the peak moment and the end moment of an experience, not the integrated whole. If the final stage visibly completes quickly — giving the impression of acceleration toward the end — users will remember the wait as shorter. This is achievable with discrete stages (where the last stage can visually snap to done) and is very difficult to engineer with pure freeform narration.

**Continuous narration** has one genuine advantage: it signals that the system is active, which reduces abandonment. But without the structural anchor of stage completion, it does not give users a mental model of where they are or when they will finish. Freeform counters (e.g., "scanning 847 documents") also carry a specific risk: if they stall, jump, or reverse, users have no reference frame to interpret the anomaly.

### Conclusion

**Labeled stages are the primary pattern.** A brief sub-label within each stage ("Stage 2: Semantic expansion — scanning 847 documents...") combines structural clarity with the activity-signaling benefit of narration. Pure continuous narration without stage anchors is not appropriate for a 60-second operation.

---

## 4. Q2 — Default Verbosity: Compact vs. Expanded

### The question

Should detailed pipeline progress be opt-in (collapsed by default, expandable on demand) or shown by default (verbose, collapsible)?

### What the research says

**Evidence strength: Moderate (practitioner consensus; limited controlled-study evidence). Mixed with an important AI-specific nuance.**

**Nielsen Norman Group on progressive disclosure**: The canonical guidance is to show what most users need most of the time and defer secondary information to a secondary interaction. If most users expand the content, the design should reconsider whether it should be visible by default. This is explicitly empirical — NN/G recommends measuring, not assuming.

**The AI-specific nuance** is not well-addressed by general progressive disclosure literature: pipeline status is not merely optional detail — it is trust-building information. Penn State (2021) found that users who observe an AI system working transparently report higher trust. Hiding all progress behind a spinner and a "Details" link may satisfy progressive disclosure principles while increasing wait anxiety and reducing result trust — the opposite of the goal.

**Algolia's UX research** noted that comfort with information density correlates with domain expertise. Technical users who understand pipelines will want to expand the detail and will do so readily. General users may be distracted or confused by verbose stage output if it is shown by default.

### Conclusion

**A compound pattern:** Always show a compact, high-level stage indicator (e.g., a single line: "Stage 2 of 4: Semantic expansion") — this is never hidden, never needs to be expanded, and satisfies the trust-transparency need. Behind it, a "Show details" control expands the full sub-narration for users who want it. Hiding all progress indicators behind a single collapsed control is too aggressive for a 60-second AI operation.

---

## 5. Q3 — Streaming Partial Results vs. Holding Until Complete

### The question

Should partial results be surfaced as each pipeline stage produces them, or should everything be held until the pipeline completes?

### What the research says

**Evidence strength: Strong. Research supports streaming for perceived responsiveness but documents a specific anchoring risk that depends on result quality distribution.**

**LLM QoE research** (2024, "Defining and Enhancing QoE in LLM-Based Text Streaming Services"): Streaming output improves *perceived* responsiveness even when total generation time is unchanged. Time-to-first-token matters disproportionately. Users who see the system generating output immediately feel the system is fast, regardless of total latency.

**"Streaming, Fast and Slow"** (arxiv 2025): Adaptive pacing — slowing the stream during complex segments — maintains satisfaction better than constant-rate streaming. Users exposed to information faster than they can process it are worse off, not better. This matters for search: flooding users with low-quality early results at maximum speed is not equivalent to streaming high-quality results progressively.

**The anchoring risk** is the most significant caveat. A 2013 *Information Systems Research* study found that early-presented information anchors subsequent user judgments. A 2022 *Information Processing and Management* study confirmed that results at the primacy position (first seen) have disproportionate influence on overall satisfaction, independent of their objective quality. Studies of health information search (PMC, 2007) found measurable anchoring and order effects in real search tasks.

**The direct implication for JustSearch**: if early pipeline stages (e.g., keyword matching) return lower-quality results than later stages (semantic reranking, RAG synthesis), streaming early results will anchor users to the lower-quality set. Users may stop scanning or form judgments before better results arrive. This is not a hypothetical risk — it is documented.

### Conclusion

**Stream if early results are not demonstrably lower quality than later results. Hold if early stages produce clearly inferior output.** A skeleton-screen approach — showing result slots before they fill in — can provide the perceived-speed benefit of streaming while managing primacy bias by presenting slots as "incoming" rather than "settled." This is the most defensible general approach when result quality distribution is uncertain.

---

## 6. Q4 — Emotional Tone: Clinical vs. Humanized Language

### The question

Should pipeline status messages use humanized language ("Looking for documents that match your intent...") or technical/clinical language ("Executing semantic retrieval pass...")?

### What the research says

**Evidence strength: Moderate. Research modestly favors humanized language but effect is inconsistent and carries a specific overconfidence risk.**

**Meta-analysis on human-like social cues** (*Nature Humanities and Social Sciences*, 2025): Human-like appearance and conversational style increase feelings of social presence, trust, and satisfaction, but "positive effects are inconsistent across studies and outcomes, and under certain circumstances human-like characteristics can harm user responses."

**Anthropomorphism and perceived intelligence** (*Frontiers in Computer Science*, 2025): Both anthropomorphic design and perceived intelligence significantly influence trusting beliefs and satisfaction. However, users who expect a technical tool and encounter humanized language may respond negatively — a linguistic version of the uncanny valley.

**"Benefits and Dangers of Anthropomorphic Conversational Agents"** (PNAS, 2022): Explicitly identifies "anthropomorphic seduction" — users become more trusting and therefore more vulnerable to errors and overconfidence. For a search system, humanized intent-language ("Looking for documents that match your intent...") may cause users to over-trust a poor result set they would have critically evaluated under technical framing.

**Penn State AI transparency research (2021)**: "When a system overdelivers, a simple explanation bolsters user trust, but when it underdelivers, users require more detailed explanations." Humanized language works well when results are good. It makes failures worse by implying confident intentionality that was not realized.

### Conclusion

**Plain action language** is the most defensible position: it avoids the clinical distance of "Executing semantic retrieval pass..." and the overconfidence risk of "Looking for documents that match your intent...". Examples of the target register: "Searching for related documents", "Ranking results by relevance", "Synthesizing answer from top matches." The language describes the action without anthropomorphically attributing intent or desire to the system. Reserve explicit intent-attribution language for moments of high confidence in results.

---

## 7. Synthesis: Recommended Design Position

Pulling the four conclusions together, a coherent design position emerges:

| Dimension          | Position                                                                              | Confidence         |
| ------------------ | ------------------------------------------------------------------------------------- | ------------------ |
| Progress structure | Labeled stages (4–8 discrete stages) with sub-narration within each stage             | Moderate-to-strong |
| Default verbosity  | Compact stage indicator always visible; detailed sub-narration behind "Show details"  | Moderate           |
| Result surfacing   | Skeleton-load result slots; fill progressively only if early results are high quality | Strong             |
| Language           | Plain action language; no clinical jargon, no anthropomorphic intent-attribution      | Moderate           |

**The overarching principle**: the pipeline duration is a feature, not a bug. The UI's job is to make the work legible, not to minimize or apologize for it. A user who watches a system complete 6 clearly-labeled stages over 60 seconds is primed to trust the result. A user who watched a spinner for 60 seconds is not. These four conclusions apply primarily to the deep search experience (>2s); the duration-adaptive model (section 20) governs how they scale down for faster searches.

---

## 8. The Interaction Arc

The four research conclusions in sections 3–6 are necessary but insufficient. They answer isolated questions; they do not describe what the user actually experiences from the moment they submit a query to the moment they finish reading results. This section traces that full sequence — seven discrete beats — and resolves the tensions that arise when the conclusions are applied simultaneously.

### The seven beats

**Beat 1 — Dormancy**

The app is at rest. The single input field is the only affordance. Nothing else competes. The placeholder text is the only communicative element, and its register matters: it frames the mental model the user brings to the interaction — whether they are about to issue a command, type a search query, or start a conversation. The character of this text is addressed in section 9 (Single-Input Paradigm). For now: the idle state should communicate availability without solicitation.

**Beat 2 — Activation**

The user focuses the field and types. Nothing should change except what is required to signal readiness. No premature pipeline indicators, no predictive suggestions that crowd the single-input surface. The field is active; the app waits. The typing experience is the whole experience at this moment.

**Beat 3 — Commitment**

The user submits. This is the highest-tension moment in the arc. Nielsen's response-time research establishes that a system response within 100ms preserves the user's sense that the system is reacting to *them specifically*. A submission event followed by any perceptible lag before visible response severs that connection — the user wonders whether the submission registered.

What must happen at commitment, effectively instantaneously:
- The input field locks. The query text remains visible but the field is non-editable. The field does not disappear — the single-input paradigm holds.
- The first stage indicator appears. Even if the pipeline has not yet produced any results, the stage track is visible from this moment: "Stage 1 of N: ..."
- A cancel affordance appears — subtle, not prominent. The user has committed to a potentially 60-second operation; they need a visible escape.

**Beat 4 — Labor** *(~60 seconds)*

The pipeline runs. This is the dominant phase. Two zones now exist simultaneously in the UI:

- **Pipeline status zone**: the compact stage indicator (always visible, per Q2's conclusion). One line: "Stage 2 of 5: Ranking by relevance." Expandable to sub-narration. This zone tells the user *how far along* the work is.
- **Results zone**: skeleton result slots, below the pipeline status zone. This zone tells the user *what is forming*.

The timing of when skeleton slots appear is a deliberate design decision. They should not appear at submission — that would fill the results zone with empty shapes for the duration of the entire pipeline, which reads as desolate rather than promising. They should appear when the first candidate-producing stage *begins*: the skeleton state signals "results are forming now," not "results will eventually form." This distinction matters for perceived momentum.

The skeleton slots do not fill with content until a stage produces definitive, high-quality results. Earlier stages may produce raw keyword candidates that are lower quality than the final reranked set; per Q3's anchoring risk, those candidates should not appear in the slots before better results arrive. The skeleton holds the space; it does not race to fill it with inferior content.

The two zones are spatially hierarchical and non-competing: pipeline status band above, results zone below. One communicates process; the other communicates emerging output. They serve different informational functions and must never appear to be the same kind of thing.

**Beat 5 — Resolution**

The pipeline completes. This is the "end moment" the peak-end rule references — the moment users will weight most heavily in their memory of the experience.

Three things must happen in sequence:
1. All stage indicators reach their completed state visibly. The entire stage track should be seen as *done* before the transition begins.
2. A brief completion beat — a perceptible pause or visual pulse — at the moment of full completion. This gives the user time to register that the work is finished before the layout changes.
3. The pipeline status zone collapses smoothly; the results zone expands to fill the space. The stages do not disappear — they *complete and yield*. The directionality matters: the pipeline finishes its job and hands off to the results, rather than being replaced by them.

The peak-end rule also argues that the final stage should appear to complete *faster* than earlier stages, giving the experience a sense of acceleration toward the end. Whether this is achieved through computational sequencing (putting the fastest-resolving step last), pipeline design, or animation timing is an implementation question outside this document's scope — but the theoretical principle is clear: the experience should feel like it builds momentum toward its conclusion.

**Beat 6 — Consumption**

Results are present. The user reads, scans, navigates. The input field remains visible — above or in a fixed position — but is visually subordinated to the results. The query text is still shown in the field (frozen). The user's attention is on the results, not the input.

The structure and hierarchy of the results themselves is the subject of section 10 (Results Presentation). From the arc perspective: the results zone must allow the input to remain accessible at any moment without it competing for attention while the user is reading.

**Beat 7 — Return**

The user wants to search again. They re-engage the input field.

Two distinct cases must be treated differently:

- **Refinement** — the user edits the existing query (modifies, extends, or narrows it). Old results persist, visually dimmed to indicate they are stale. The user can reference them during the new pipeline run. The new query inherits the context of the previous one.
- **New search** — the user clears the field entirely and types something unrelated. Old results clear. The context break is intentional, and preserving old results would anchor the user to an irrelevant prior frame — the reverse application of the Q3 anchoring risk.

The UI should infer the distinction from the user's action: editing in place signals refinement; clearing and retyping signals a new search. This allows the return behavior to be contextually appropriate without requiring the user to make an explicit mode choice.

### Tension map and resolutions

| Tension                                                | Resolution                                                                                                                                                                                                     |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stage progress and skeleton slots coexist visually     | Spatial separation into two hierarchically distinct zones: pipeline band above, results zone below. They communicate different things and must never look like the same kind of element.                       |
| The completion transition must be decisive, not abrupt | Completion pulse (all stages reach done) → pause → pipeline zone collapses, results zone expands. The stages complete and yield; they are not replaced.                                                        |
| Input field during pipeline — accessible or locked?    | Locked (non-editable) but always visible. Cancel affordance present. Single-input paradigm holds: the field never disappears.                                                                                  |
| Skeleton slots — when to appear, when to fill          | Appear when first candidate-producing stage begins. Fill only when high-quality results are ready, not as raw lower-quality candidates arrive. Skeleton is a quality holding pattern, not a loading indicator. |
| Follow-up query — preserve or clear old results?       | Context-dependent: refinement (edit in place) preserves old results dimmed; new search (clear and retype) clears them. The behavior is inferred from the user's action, not a manual mode switch.              |
| Peak-end rule — acceleration toward the end            | Final stage should appear to complete faster than earlier stages, creating a sense of momentum toward resolution. Method of achieving this is outside theoretical scope.                                       |

---

## 9. The Single-Input Paradigm

The vision of an app that "dissolves into a single input field" draws on three distinct design traditions: calm technology, the command palette paradigm, and the theory of conversational vs. search UI mental models. The research on each reveals that the single input field is not a design solution in itself — it is a constraint that must be backed by strong adaptive behavior, calibrated affordances, and thoughtful state communication.

### Calm technology: minimal is not invisible

Weiser and Brown's foundational calm technology framework (1995, 1996) makes a specific claim that is frequently misread: it does not say that interfaces should be invisible. It says that interfaces should deliver information through the periphery of attention and escalate to the center only when the information demands action. The peripheral is not nothing — it is "what we are attuned to without attending to explicitly."

The direct implication for JustSearch: a single input field that provides no ambient signal of system state fails calm technology's own test. An interface is not calm when it hides state; it is calm when it communicates state with the minimum required attention. The right amount of system feedback is not zero — it is the minimum that allows the user to remain *located*: aware of what the system is doing, whether it is ready, and whether something has gone wrong.

The Weiser/Brown center-periphery model maps cleanly onto the arc established in section 8. Idle and ready state belongs at the periphery — a quiet, receding treatment. Active pipeline processing belongs at the peripheral-but-visible level — a compact stage indicator that doesn't demand focal attention but is readable without interaction. Error state must escalate to the center — it demands a response. Designing all three states as if they are the same (all invisible, all equally quiet) violates the framework.

**Amber Case's derived principles** (O'Reilly, 2015) that are most directly applicable:
- Technology should require the smallest possible amount of attention.
- A calm technology will move easily from the periphery of our attention to the center, and back.
- Technology can communicate, but doesn't need to speak — status should be conveyed through design (motion, position, color), not verbal notification.
- The right amount of technology is the minimum needed to solve the problem.

### Command palette paradigm: the discoverability paradox

The command palette pattern (Spotlight, VS Code, Linear, Raycast) demonstrates that a single input field can be the entire interaction surface for complex applications. It works by trading spatial memory (where is the feature in the menu?) for recall-based search (what is the feature called?). The pattern demonstrably improves throughput for expert users: reduced mouse-keyboard switching, faster command chaining, reduced UI clutter.

The well-documented failure mode is the discoverability paradox: command palettes help users find features they already know exist. They provide substantially less assistance to users who do not know a feature exists, because the trigger for searching is absent. Visible GUI elements signal their own existence; a command palette's contents are invisible until searched. Nielsen's sixth heuristic — "Recognition rather than recall" — directly names this as the tradeoff: recall-based interfaces are acceptable for expert users as accelerators, but should not replace recognition-based navigation as the *only* path.

For JustSearch, the discoverability paradox manifests as the empty-state problem. A blank input field gives users no indication of what kinds of queries the system can handle, how it differs from a standard search engine, or what its capabilities are. This is not fixed by making the field look distinctive — it is fixed by making the empty state communicative. Example queries, recent query history, and contextual suggestions in the empty state are the primary mechanisms for bridging the recall gap. Appleton (2023) and Destiner (2024) both identify the empty state as the highest-leverage surface for orienting new users.

### Conversational vs. search mental models: the unresolvable tension

A single text input field is semantically ambiguous. Users arrive having internalized one of two interaction schemas:

- **The search schema**: compact keyword query, expects ranked list of candidates, evaluates multiple results, reformulates on failure, tolerates zero results as a meaningful signal.
- **The conversational schema**: natural language request, expects a single synthesized answer, iterates through follow-up rather than reformulation, expects the system to handle ambiguity through clarification.

These schemas produce different query behaviors, different result format expectations, and different responses to failure. Research consistently shows that users import whichever schema they already hold — and when the system's actual behavior does not match the imported schema, they attribute the mismatch to their own query formulation rather than the interface design.

**The tension cannot be dissolved at the input surface.** There is no documented approach that successfully neutralizes this distinction through input field design alone. What can be designed is:

1. **Schema-setting signals** before the user submits, so they arrive with an accurate expectation.
2. **Adaptive response strategy** after submission, so the system handles both schemas effectively regardless of how the user framed their query.

**The five primary schema-setting signals** identified in the research:

| Signal                  | What it communicates                                                                                                                                                                  | Strength |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| Placeholder text        | Most direct schema-setter — "Search..." vs. "Ask anything" vs. "Search or ask" activates different prior models                                                                       | High     |
| Response format         | The dominant cue — a prose answer signals conversation; a ranked list signals retrieval; a hybrid trains users to a hybrid model within a few sessions                                | High     |
| Interaction persistence | Thread history + follow-up affordances → conversational model; blank reset after each query → search model                                                                            | Medium   |
| Empty state examples    | Concrete example queries that span both modes calibrate expectations without requiring the user to theorize about system capabilities                                                 | High     |
| Query length detection  | Adaptive response strategy based on detected query intent (short/elliptical → search-style handling; long/contextual → conversational handling) is preferable to a manual mode toggle | Medium   |

**The transparency paradox** is a specific risk worth documenting. Research on generative conversational search mental models (arxiv, 2025) found that increasing interface transparency — showing source attribution, query transformations, and system reasoning — *reduced* mean user satisfaction in users who lacked the mental model to interpret what they were seeing. Satisfaction dropped from 5.00 to 3.38 across conditions when transparency was increased for unprepared users. Only users with accurate mental models of LLM behavior benefited from transparency features. This directly qualifies section 8's pipeline transparency rationale: the pipeline visibility is trust-building for users who understand what they are seeing, and potentially anxiety-inducing for users who do not. The compact-by-default position (Q2) and the plain action language position (Q4) are partially justified by this finding — the level of transparency should be calibrated to what the user can usefully process, not maximized as a default.

### Synthesis: what the single-input paradigm requires

The three traditions converge on a set of concrete design requirements:

1. **The field itself is not enough.** It is a design constraint, not a design solution. Adaptive system behavior behind the field is more important than the field's visual treatment.
2. **The empty state is the highest-leverage design surface.** Example queries, recent history, or contextual suggestions are not optional enhancements — they are the primary mechanism for model-setting and discoverability.
3. **Peripheral state communication is non-negotiable.** Calm technology does not mean silent. The field must encode system state (idle, processing, ready, error) through ambient visual cues even when no active operation is in progress.
4. **Placeholder text is a schema-activation mechanism.** Choose it deliberately. It frames every user interaction from first session onward.
5. **Auto-default with accessible override, not a pure no-toggle design.** The original position in this document was that a "Search / Chat" toggle is unnecessary and adaptive detection should handle everything. Production evidence has decisively refuted pure no-toggle approaches. OpenAI attempted exactly this with GPT-5's launch (Sam Altman publicly stated he "hates the model picker") — the automatic router was "largely broken" on launch day, users revolted, and OpenAI restored manual controls within days (TechCrunch, August 2025). Every successful AI search product has converged on the same pattern: **automatic detection as the default, with escape hatches for manual override.** Perplexity uses auto-routing with Focus Modes for override. Google uses automatic AI Overviews with AI Mode as an opt-in tab. Kagi auto-triggers on "?" queries but lets users manually trigger with 'q' or disable entirely. ChatGPT auto-decides when to web-search but offers a manual trigger icon. The revised position for JustSearch: adaptive response format is the **default** — the query classifier (tempdoc 306) routes queries and the response format adapts accordingly. But the user must have a visible, low-friction way to override the detected intent. This is not a prominent "Search / Chat" toggle (which creates false dichotomy), but a lightweight mechanism — e.g., a post-classification indicator showing the detected mode that the user can tap to change, or a re-run affordance ("Search instead" / "Ask instead"). The NNGroup diary study (February 2026) found that 6 of 9 participants went back and forth between AI and search for the same topic, treating them as complementary — the override must be accessible enough to support this behavior.
6. **Response format is the dominant mental model setter.** The output side of the interaction is where users form their durable understanding of what the system is — not the input side. A hybrid output format (synthesized answer + visible source documents) trains users to a hybrid model more reliably than any input-field treatment.

### The override mechanism

Section 9's synthesis point 5 establishes that the user must have a way to override adaptive detection. This subsection specifies what that override looks like.

#### What misclassification means in practice

The query classifier (tempdoc 306) assigns one of four types: NAVIGATIONAL, EXACT_MATCH, INFORMATIONAL, EXPLORATORY. Misclassification produces a mismatch between the user's intent and the response format:

- `report.pdf` classified NAVIGATIONAL → fast file results. But the user wanted a semantic search about reporting → they needed an answer, not a file match.
- `how does the budget process work` classified INFORMATIONAL → RAG synthesis. But the user wanted to find a specific document titled "Budget Process" → they needed a file, not a prose answer.
- `optimization` classified EXPLORATORY → broad diverse results. But the user wanted a specific file about optimization → the results are too scattered.

The user does not think in terms of query classifications. They think in terms of what they wanted: "I wanted the file, not an answer" or "I wanted an answer, not a file list." The override must be expressed in the user's language, not the system's.

#### Result-format override, not classification override

The override exposes two response formats, not four query types:

1. **Answer mode** — synthesized RAG answer with source cards (INFORMATIONAL pipeline).
2. **File mode** — ranked document list without synthesis (NAVIGATIONAL-style pipeline: CE and expansion may be skipped for speed).

This maps to what the user actually observes. An override labeled "Switch to navigational query" is meaningless. An override labeled "Find matching files instead" or "Ask about this instead" is immediately clear. The query classifier remains internal; the override operates on the response format that the classification produced.

**Why only two modes, not four?** EXACT_MATCH (quoted phrases) and EXPLORATORY (single broad terms) produce the same visible result format as NAVIGATIONAL — a ranked document list. The difference is in which pipeline stages run and how results are ranked, not in the layout the user sees. The user-visible distinction is binary: "answer + sources" vs. "document list."

#### When the override appears: duration-adaptive

The override should not be a permanent fixture of the interface. It adds cognitive overhead to every search when most searches are correctly classified. Instead, it follows the duration-adaptive model (section 20):

**Fast search (<2s):** No override is shown. Results arrive before the user could process an override UI, read it, decide whether to use it, and click it. If the results are wrong-shaped, the user can reformulate — for a 200ms search, reformulating costs less than reading an override label. This respects the principle from section 1: "a 200-millisecond search should not be burdened with UI chrome designed for a 60-second pipeline."

**Working search (2s–10s):** A subtle post-result override appears at the top of the results zone after results arrive: "Find matching files" (if answer mode was used) or "Ask about this" (if file mode was used). This is a correction mechanism: the user sees the wrong-shaped results and can tap once to re-run with the other format. The cost — having waited 2–10 seconds for the wrong results — is real but bounded.

**Deep search (>10s):** The override appears during the pipeline (beat 4, Labor phase), alongside the stage indicator. When the pipeline has been running for >2s and the detected mode is visible in the stage indicator area, a secondary affordance appears: "[Find files instead]" or "[Get an answer instead]." This lets the user abort and re-route during the wait rather than after results arrive. For a 30-second pipeline, discovering the results are wrong-shaped after they arrive wastes 30 seconds; discovering during the pipeline and re-routing wastes only the time elapsed so far.

**The duration-adaptive override thresholds:**

| Pipeline duration | Override behavior |
|---|---|
| <2s | No override shown. Reformulate if needed. |
| 2s–10s | Post-result override appears at top of results zone. |
| >10s (during pipeline) | Override appears alongside pipeline stage indicator. Click aborts current pipeline and re-submits with opposite format. |
| >10s (after results) | Post-result override also shown, same as 2s–10s case. |

#### The override as a learning signal

When the user clicks an override, the system learns that the classification was wrong for this kind of query. Over time (when tempdoc 270's Stage 8 Thompson Sampling is active), override events are the strongest signal for recalibrating the classifier — stronger than implicit feedback like click-through or dwell time, because the user explicitly said "this was wrong." In the short term (rule-based classifier), override events should be logged for manual classifier tuning.

#### Interaction with the single-input paradigm

The override does not add a pre-submission mode selector to the input field. The dormant state (beat 1) remains clean: one field, one action. The override exists only in the context of an active or completed search, never during dormancy or typing. This preserves calm technology's principle that the idle state communicates availability without solicitation.

The override is visually secondary to the results themselves — styled at text-secondary weight, consistent with the degradation notices in section 14. It is an affordance, not a navigation element. Users who never need it should barely notice it.

---

## 10. Results Presentation

### Answer-first vs. sources-first

**Verdict: Answer-first is well-supported, but sources must remain visually present — not collapsed.**

Every major production AI search system (Perplexity, Google AI Overviews, Bing Copilot) has converged on answer-first presentation. Information Foraging Theory (Pirolli & Card, PARC) provides the theoretical basis: users follow information scent and will invest effort in sources only if the synthesized answer signals uncertainty or incompleteness. If the answer appears authoritative and complete, most users treat it as a terminal node.

The convergence is not just theoretical. Perplexity's model attracted 170 million monthly users and a 524% usage surge in 2024. The pattern is empirically validated at scale.

The critical caveat is the automation bias problem. Research from arXiv (2024, "Search Engines in an AI Era: The False Promise...") quantified the damage directly: users with an answer-first AI engine hovered over an average of 2 sources vs. 12 with traditional search, and clicked 1 source vs. 4. The answer-first framing anchored users before they could form independent assessments. Separate automation bias research (Springer, 2025) confirms that placing AI output first reduces independent judgment and increases acceptance of incorrect answers.

**The resolution**: answer-first is the correct default *because* sources must remain immediately and visibly present beneath it — not behind a tab, not collapsed, not requiring any interaction to reveal. The answer-first/sources-below layout works when both elements are equally visible. The failure mode is not answer-first; it is answer-only or answer-with-hidden-sources.

### Citation and source treatment

**Constraint: Inline citations with interactive hover-preview that deep-link to the source passage. Every factual claim requires an explicit citation.**

**The local RAG citation function is fundamentally different from cloud RAG.** Cloud AI search engines (Perplexity, Google AI Overviews, ChatGPT) cite web URLs that may be fabricated — the "False Promise" study (arXiv:2410.22349) found 60–97% hallucinated citations. JustSearch's local RAG architecture eliminates this failure mode entirely: citations point to real files in the user's own corpus. Source existence is guaranteed; the user already trusts their own files; there is no credibility question about the source itself.

This shifts the primary function of citations from **credibility establishment** ("Is this source real?") to **navigation and context recovery** ("Which of my files says this? Show me where."). Li & Aral's large-scale experiment (4,927 participants, 2025) found that only 27% of users click citations in cloud AI search, and 48.8% don't verify if the AI "sounds right." Pew Research (July 2025, 900 adults) found a 1% click-through rate on AI-cited sources. In local RAG, the click motivation is different — clicking opens your own file and navigates to the relevant passage, which has utility beyond trust verification.

**The convergent pattern for local RAG citation** (observed in NotebookLM, Notion AI, Obsidian Copilot, GitHub Copilot) is: inline citations that deep-link to the source passage in the local document. NotebookLM is the strongest model — inline numbered citations that, when clicked, navigate directly to the relevant passage. Local RAG products generally cite at document/file level; JustSearch should aim for passage-level to provide more useful navigation.

**The buffing effect and trust spillover remain critical.** Li & Aral's most concerning finding: citations increase trust even when incorrect or hallucinated, because users respond to the *presence* of citations rather than their accuracy. The "False Promise" study's "buffing effect" — where static citation markers inflate perceived authority without encouraging verification — applies regardless of whether sources are local or remote. Uncited claims within a partially cited answer receive trust spillover from cited ones. Citation coverage must therefore be comprehensive: every factual claim requires a citation, because uncited claims inherit unearned trust from their cited neighbors.

**The hover-preview serves navigation, not just verification.** In the local RAG context, the hover-preview should show the source passage in context with the file name, path, and surrounding text. Clicking should open the file and scroll to the relevant section. This transforms the citation from a trust mechanism into a **workflow affordance** — the user can seamlessly move from the synthesized answer into their source documents.

### Confidence and uncertainty signaling

**Constraint: Explicit enforcement of first-person epistemic hedges for specific uncertain claims. Ban the use of raw confidence scores and static blanket disclaimers.**

Recent analysis of LLM behavior (e.g., FAccT 2025 context on reward model steerability) indicates that foundational reward models intrinsically suppress epistemic hedges, predisposing the system toward false confidence. To counteract this, explicit first-person uncertainty markers ("I found limited sources on this specific point") must be structurally enforced during inference. A 2024 FAccT experiment confirmed that first-person hedges significantly reduce over-reliance on inaccurate answers without proportionately deteriorating trust in accurate outputs, producing calibrated skepticism.

Conversely, exposing raw probability scores ("72% confident") induces uncalibrated under-reliance (Li et al., arXiv:2402.07632) and masks systemic miscalibration. Probability thresholds must remain internal pipeline heuristics, never exposed as user-facing metrics.

Additionally, static blanket disclaimers are deprecated. Repeated exposure initiates banner blindness, training the user to discard the warning. Targeted, claim-specific epistemic hedging ("fewer sources addressed this directly") is the canonical mechanism for conveying uncertainty. Such hedging is optimally anchored to factual retrieval metrics (e.g., source count limitations) to ensure it remains a verifiable systemic reflection rather than a hallucinated sentiment.

### Result density

**Verdict: Synthesized answer plus 3–5 visible source cards. Neither answer-only nor a 10-result list.**

Eye-tracking research on web search (Granka et al., Cornell; MDPI 2020 systematic review) consistently shows that for knowledge retrieval tasks, user attention concentrates on the top 2–3 results — users are reading, not scanning a full ranked list. Ten results is appropriate for navigational or exploratory tasks, not for directed knowledge retrieval.

The "False Promise" study (arXiv:2410.22349) established the failure mode of the answer-only presentation directly: lower source engagement, no accuracy advantage, higher risk of accepting hallucinated content.

The hybrid — synthesized answer + 3–5 source cards immediately beneath — is convergently supported by production evidence (Perplexity's model), information foraging theory, and knowledge-gain research (ACM CHIIR 2018: knowledge change was highest when users engaged with well-targeted sources rather than many sources).

The 3–5 range is not from a single clean study, but from converging evidence: fewer than 3 reads as sparse and increases distrust; more than 7 induces scanning fatigue on knowledge tasks. A "show more sources" affordance preserves access without cluttering the default view.

**Sources must never be placed behind a separate tab or expand-only interaction.** The 2024 research directly shows this suppresses verification behavior. Visible source cards in the default view are not supplementary — they are a trust and accuracy mechanism.

### Summary: Results presentation design position

| Element              | Design position                                                                            | Evidence                                                     |
| -------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| Answer placement     | Synthesized answer at top                                                                  | Strong (production convergence, information foraging theory) |
| Sources visibility   | 3–5 source cards immediately beneath answer, always visible by default                     | Strong (automation bias research, False Promise study)       |
| Citation format      | Inline sentence-level, hover-preview to source snippet, click to full document             | Strong (RAG trust study, CHI 2025)                           |
| Citation coverage    | Every factual claim must be cited; uncited claims receive trust spillover                  | Strong (False Promise study)                                 |
| Uncertainty signals  | First-person verbal hedges on specific uncertain claims; retrieval-level signals preferred | Moderate-strong (FAccT 2024)                                 |
| Confidence scores    | Do not use raw probability scores unless model is demonstrably well-calibrated             | Moderate-strong (Li et al. 2024)                             |
| Blanket disclaimers  | Do not use; they train users to ignore them                                                | Moderate (practitioner consensus, FAccT implications)        |
| Default result count | 3–5 source cards; "show more" below                                                        | Moderate (converging evidence, no single definitive study)   |

### Results without a synthesized answer

The design positions above assume RAG synthesis is active and produces a prose answer. In practice, many searches produce no synthesized answer:

- **Inference offline** (section 14, category 2) — the most common degraded state. Search works in text-only mode.
- **Models not installed** — the early adoption experience before the user has configured AI features.
- **Navigational and exact-match queries** — the query classifier (tempdoc 306) identifies known-item queries (filenames, path fragments, quoted phrases) where RAG synthesis would be counterproductive. The user is looking for a specific file, not a prose explanation.
- **Exploratory queries** — broad single-term queries where the pipeline returns ranked documents without sufficient confidence to synthesize an answer.

In these cases, the results zone contains ranked document cards without an answer block above them. This is not a degraded version of the answer-first layout — it is a distinct, first-class presentation mode that must be designed with equal care.

**Design position for document-list results:**

| Element | Design position | Rationale |
| ------- | --------------- | --------- |
| Default result count | 7–10 visible results | Without an answer block consuming viewport space, more results are visible. The 3–5 limit from the answer-first layout was justified by the answer occupying the primary attention zone; without it, the F-pattern research (section 17) suggests users will scan further. 10 is the practical upper bound before scanning fatigue. |
| Card content | Title (or filename), path, matched snippet with highlighted terms, file type icon, modification date | These are the information scent signals that drive foraging behavior (Pirolli & Card). Title and snippet carry the most signal; path and date provide context for disambiguation. |
| Snippet highlighting | Matched query terms bolded within the snippet text | Standard convention. Bolding is universally understood as "this is why this result matched." No additional explanation needed. |
| Relevance signaling | No numeric scores, no progress bars, no confidence indicators | Consistent with section 10's position against raw confidence scores. The result's rank position is the primary relevance signal. Users interpret list order as relevance order; making this explicit is unnecessary and exposes score calibration problems. |
| File type signaling | Icon prefix on each card (document, code, image, PDF) | Provides at-a-glance filtering without requiring a separate filter UI. Consistent with existing `ResultList` component patterns. |
| "Show more" | Pagination or infinite scroll after the initial 7–10 | Same principle as the answer-first layout's "show more sources" — preserve access without cluttering the default view. |

**The transition between layouts.** When RAG synthesis is available, the results zone shows the answer-first layout (answer + 3–5 source cards). When it is not, the results zone shows the document-list layout (7–10 result cards). The layouts share the same spatial zone (section 17) and the same card component for individual results — the difference is whether an answer block precedes them.

This transition should not feel like a mode switch. The same query can produce different layouts depending on whether inference is available, and the user should not need to understand why the layout changed. The document-list layout is the natural state; the answer-first layout is an enrichment that appears when the system has enough capability to synthesize an answer. Section 14's category 2 degradation notice ("Searched as text only — AI features are offline") provides context when the user might expect an answer and doesn't get one.

**Navigational query results deserve special treatment.** When the query classifier identifies a NAVIGATIONAL query (file extension, path fragment, CamelCase identifier), the result cards should emphasize path and filename over snippet text. The user is looking for a specific file, and the most useful information is where that file is — not a content excerpt. This is a card-level variation within the document-list layout, not a separate layout.

### Document preview and source navigation

The interaction arc (section 8) traces the user journey from dormancy through results consumption (beats 1–7) but does not address what happens when the user clicks a result. The "last mile" of the search experience — transitioning from search results into source documents — requires its own design position.

#### The preview function differs by result layout

**In the answer-first layout** (RAG active), the synthesized answer IS the primary content. Citations link to source passages. The user's click motivation is: "show me the passage in my file that supports this claim." This is a **verification and navigation** action — the user wants to see the cited passage in context, confirm the citation's accuracy, and potentially continue reading the source document.

**In the document-list layout** (no RAG), the result list is a set of candidates. The user's click motivation is: "is this the right document?" This is a **scanning and selection** action — the user previews a result, decides whether it's what they want, and either opens it fully or returns to the list to try another.

These are different interaction patterns that call for different preview treatments.

#### Answer-first: overlay preview for citation verification

When the user clicks an inline citation in a synthesized answer, an overlay or slide-out panel shows the source document with the cited passage highlighted and scrolled into view. The panel displays:
- File name and path at the top
- The document content rendered appropriately for the file type (PDF, markdown, plain text, code)
- The cited passage highlighted or underlined, scrolled to the viewport center
- An "Open in app" affordance to open the file in its native application

The overlay preserves the answer beneath it — dismissing the overlay returns the user to exactly where they were in the answer. This is a modal interruption of the single-column layout, not a violation of it: the overlay sits above the column, the column persists underneath.

This pattern matches NotebookLM's citation behavior (the strongest local RAG citation model identified in Track 3 research) and is consistent with section 17's acknowledged exception for "deep-reading preview."

#### Document-list: preview panel for result scanning

When the user selects a result in the document-list layout (no RAG), a preview panel shows the document content alongside the result list. This is the established convention across every desktop search tool studied (Raycast, DEVONthink, Everything, macOS Spotlight Tahoe — Track 2 research). Users scanning multiple candidates need to preview one result while keeping the result list visible to try the next.

**This is a formal exception to the single-column constraint.** The single-column constraint (section 17) was established for the answer-first layout, where the synthesized answer is the primary content and a single reading column is optimal. In the document-list layout, there is no long-form answer to read — the results are a list of candidates, and the primary reading surface is the previewed document. A two-panel layout (result list on left or top, preview on right or bottom) is the correct spatial model for this case.

The exception is bounded:
- It applies only to the document-list layout, not the answer-first layout.
- The preview panel appears when a result is selected, not by default. The initial document-list view is single-column.
- The split direction (horizontal or vertical) should respect the viewport: wide viewports favor left-right split; narrow viewports favor top-bottom split or overlay.
- The preview panel should be dismissible, returning to full-width result list.

#### Preview panel content

The preview panel renders the document content appropriate to file type:

| File type | Preview treatment |
|---|---|
| Plain text, markdown | Rendered text with matched query terms highlighted |
| PDF | Page-level rendering with the most relevant page shown first |
| Code files | Syntax-highlighted text with matched sections visible |
| Images | Thumbnail with metadata |
| Office documents | Extracted text rendering (Tika-extracted content) |

**Matched query terms should be highlighted in the preview.** This connects the search query to the document content and helps the user confirm "this is why this result matched." The highlighting is the same bolding treatment used in result card snippets.

**"Open in app" is always available.** Regardless of preview quality, the user must be able to open the file in its native application with one click. The preview is a scanning aid, not a replacement for the actual application.

#### Keyboard navigation

- **Arrow keys** navigate between results in the list (up/down) without changing the preview — the preview updates only when the user actively selects a result (Enter or click). This prevents the preview from frantically changing as the user arrows through the list.
- **Escape** dismisses the preview panel and returns to full-width result list.
- **Tab** moves focus from the result list to the preview panel, allowing keyboard-driven reading.
- These patterns are consistent with section 16's keyboard navigation requirements.

---

## 11. Unified Design Position

Sections 3–10 each establish positions on their own terms, but they were developed independently. This section asks whether those positions cohere — and where they don't, it forces a resolution. Two unresolved tensions exist in the current material. After resolving them, this section consolidates everything into a single authoritative design position.

### Tension A: Transparency commitment vs. the transparency paradox

**The conflict.** The interaction arc (section 8) is built on the principle that the pipeline should be maximally legible — every stage named, every transition visible, the full sequence from commitment to resolution exposed to the user. This is grounded in Maister's waiting-line psychology, the labor illusion, and the peak-end rule.

But the single-input paradigm (section 9) documents the transparency paradox: research on generative conversational search mental models (arxiv, 2025) found that increasing interface transparency — showing source attribution, query transformations, system reasoning — *reduced* user satisfaction from 5.00 to 3.38 for users who lacked the mental model to interpret what they were seeing. Only users with accurate prior understanding of LLM behavior benefited.

If these findings are both correct, then "show everything" and "showing too much hurts" are simultaneously true. The tempdoc cannot hold both without stating where the boundary is.

**The resolution: transparent about process, opaque about mechanism.**

The transparency paradox study measured a specific kind of transparency: exposure of *system internals* — how queries are transformed, which retrieval strategies are selected, why certain sources were chosen. This is mechanism transparency. It requires domain knowledge to interpret, and it is the kind that hurt uninformed users.

The pipeline stage indicators are a categorically different kind of transparency: *process progress*. "Stage 2 of 5: Ranking results by relevance" communicates where the user's request is in the pipeline. It does not explain how ranking works, which algorithm is used, or why any particular document was promoted. Process progress is universally legible — every user understands "step 2 of 5" regardless of their understanding of search technology.

The boundary is therefore:

- **Always show process progress.** The compact stage indicator is process transparency. It tells the user where they are, how far along the work is, and that the system is active. This is never harmful and is always shown.
- **Gate mechanism detail behind opt-in interaction.** The expanded sub-narration ("Show details") is mechanism transparency. It may include document counts, retrieval strategy names, timing information, and other signals that are meaningful to technical users and potentially confusing to non-technical users. This is available but not forced.
- **The compact-by-default position (Q2) is reinforced.** It is not merely a progressive disclosure preference — it is the specific interface manifestation of the process/mechanism boundary.

This reconciles the arc's transparency commitment with the paradox finding. The arc is right that legibility builds trust. The paradox research is right that *the wrong kind* of legibility can harm. They address different layers, and the design must maintain the distinction.

### Tension B: Plain action language vs. first-person uncertainty hedges

**The conflict.** Section 6 concludes that pipeline status messages should use plain action language — "Searching for related documents," not "Looking for documents that match your intent" — to avoid the overconfidence risk of anthropomorphic intent-attribution. Section 10 concludes that uncertainty in the synthesized answer should be communicated through first-person verbal hedges — "I found limited sources on this specific point" — because the FAccT 2024 study showed first-person hedges reduce over-reliance more effectively than third-person hedges.

First-person hedging *is* anthropomorphic intent-attribution. "I found limited sources" attributes agency, effort, and self-awareness to the system. Section 6 says don't do that; section 10 says do it for a specific purpose. Both are citing research. The positions cannot both be the global voice of the system.

**The resolution: two registers, one voice.**

The system communicates in two distinct contexts that serve different functions:

1. **Pipeline status** (beats 3–5 of the arc): the system is reporting work in progress. It is a tool. The user is watching a process execute. The appropriate register is plain action language — descriptive, non-anthropomorphic, reportorial. "Ranking results by relevance." "Synthesizing answer from top matches." This register is governed by section 6's conclusion.

2. **Answer presentation** (beat 6): the system is presenting findings and making claims. Some of those claims are well-grounded; others are not. When a claim is poorly grounded, the system must signal that — and the research is specific that first-person hedging is more effective than third-person hedging for reducing over-reliance. The appropriate register here is first-person but restrained: hedges appear only on specific uncertain claims, not pervasively. "I found limited sources on this" is acceptable. "I think the answer is..." as a framing for every response is not.

The transition between registers must not feel jarring. The pipeline register and the answer register are two modes of the same voice — factual, direct, non-performative. The pipeline register is a tool reporting. The answer register is a tool presenting. Neither is chatty, effusive, or self-deprecating. The difference is narrow: the answer register permits first-person attribution when — and only when — it serves the specific purpose of uncertainty signaling.

**The constraint that binds both registers:** the system never attributes intent, desire, or effort to itself. It may attribute *epistemic state* — what it found, what it didn't find, where its evidence is thin. "I found limited sources" is an epistemic claim (I know how much evidence I have). "I'm trying to find documents that match your intent" is an agency claim (I am an agent with goals). The former is permitted; the latter is not. This distinction preserves section 6's anti-anthropomorphism position while allowing section 10's evidence-backed uncertainty mechanism.

### Consolidated design position

| Dimension                   | Position                                                                                      | Source        | Confidence      |
| --------------------------- | --------------------------------------------------------------------------------------------- | ------------- | --------------- |
| **Progress structure**      | Labeled stages (4–8 discrete) with sub-narration within each stage                            | §3, §8        | Moderate-strong |
| **Default verbosity**       | Compact stage indicator always visible; mechanism detail behind "Show details"                | §4, §9, §11-A | Moderate        |
| **Result surfacing**        | Skeleton-load slots; fill when high-quality results are ready, not when raw candidates arrive | §5, §8        | Strong          |
| **Pipeline language**       | Plain action language — descriptive, non-anthropomorphic                                      | §6, §11-B     | Moderate        |
| **Answer language**         | Same plain voice; first-person epistemic hedges on specific uncertain claims only             | §10, §11-B    | Moderate-strong |
| **Transparency boundary**   | Process progress always visible; mechanism detail opt-in                                      | §8, §9, §11-A | Moderate-strong |
| **Answer placement**        | Synthesized answer at top; 3–5 source cards immediately beneath, always visible               | §10           | Strong          |
| **Citation format**         | Inline passage-level; hover-preview to source snippet in context; click to open file and navigate to passage. Navigation affordance first, trust signal second. | §10 | Strong |
| **Citation coverage**       | Every factual claim must be cited; no uncited claims in otherwise-cited answers               | §10           | Strong          |
| **Confidence scores**       | Do not use raw probability scores unless model is demonstrably well-calibrated                | §10           | Moderate-strong |
| **Blanket disclaimers**     | Do not use; they train users to ignore them                                                   | §10           | Moderate        |
| **Empty state**             | Communicative: example queries, recent history, or contextual suggestions — never blank       | §9            | Strong          |
| **Schema-setting**          | Placeholder text deliberately chosen; response format is the dominant mental model setter     | §9            | Strong          |
| **Mode detection**          | Auto-default with accessible override. Adaptive detection routes queries automatically; lightweight override mechanism (not a prominent toggle) lets users correct misclassification. Every successful AI search product has converged on this pattern. | §9 | Strong (industry-validated) |
| **Peripheral state**        | System state (idle, processing, error) always encoded through ambient visual cues             | §9            | Strong          |
| **Peak-end acceleration**   | Final pipeline stage should appear to complete faster than earlier stages                     | §8            | Moderate        |
| **Follow-up query**         | Refinement (edit in place) preserves old results dimmed; new search (clear field) clears them | §8            | Moderate        |
| **Color palette**           | Blue-teal primary; saturated color for functional signals only                                | §12           | Moderate        |
| **Dark/light mode**         | Light mode is research-optimal default; dark as first-class option                            | §12           | Moderate        |
| **Surface hierarchy**       | Elevation through subtle differentiation, not decoration                                      | §12           | Moderate        |
| **Typography**              | Single sans-serif; Minor Third scale; 1.4–1.6 line-height for answers                         | §12           | Moderate        |
| **Information density**     | Configurable (compact/default/spacious); default favors readability                           | §12           | Moderate        |
| **Motion purpose**          | Every animation must communicate state, prevent disorientation, or strengthen signifiers      | §13           | Strong          |
| **Motion duration**         | Max 500ms; ease-out default; spring physics for zone transitions                              | §13           | Moderate-strong |
| **Reduced motion**          | Non-negotiable OS-level; removes interpolation, not information                               | §13, §16      | Strong          |
| **Error language**          | System-accountable; every state offers an action; technical detail opt-in; no humor           | §14           | Moderate-strong |
| **Onboarding**              | No tutorials; brief capability framing sentence; rotating example queries curated for user's content; progressive disclosure after first search. First result must be strong (anchoring bias). | §15 | Strong |
| **Color independence**      | All functional color also encoded through shape, icon, or text (WCAG 1.4.1)                   | §16           | Strong          |
| **Layout**                  | Single vertical column; centered; max ~60 char line width                                     | §17           | Moderate-strong |
| **Dormant-to-active shift** | Input field center-to-top at commitment; one-time per query cycle                             | §17           | Moderate        |
| **Sticky input**            | Input zone fixed at top during scroll; thin viewport footprint (<20%)                         | §17           | Moderate-strong |
| **Session model**           | Independent pipeline execution per query. Session context may inform query understanding (extractive expansion from history) but retrieval is always stateless. No conversational threading. | §18 | Strong (architecture + research) |
| **Query history**           | 5–10 recent queries as chips; task-organized; re-execute on click                             | §18           | Moderate        |
| **Stale results**           | Reset to dormancy after inactivity; no persistent stale results                               | §18           | Moderate        |
| **Pipeline notification**   | Single OS notification at completion with query text; no stacking                             | §19           | Strong          |
| **Return-to-app**           | Show completed state directly; no replay; no animation                                        | §19           | Strong          |
| **Background indexing**     | Peripheral ambient indicator; first-time indexing gets prominent treatment                    | §19           | Moderate        |
| **Results without RAG**     | Document-list layout: 7–10 result cards with title, path, snippet, file type, date. First-class presentation, not a degraded answer-first layout | §10 | Moderate |
| **Navigational results**    | Navigational query results emphasize path/filename over snippet text                         | §10           | Moderate        |
| **Layout transition**       | Answer-first when RAG active; document-list when not. Same zone, same card component. Not a mode switch — an enrichment | §10 | Moderate |
| **Experience spectrum**     | Fast search (<2s) and deep search (>10s) are co-equal experiences; UI adapts to actual duration, not fixed assumption | §1, §20 | Strong |
| **Skeleton animation**      | Subtle slow pulse preferred over static; no shimmer/wave. Static is safer if in doubt        | §12           | Moderate        |
| **Override mechanism**      | Duration-adaptive: invisible for <2s, post-result for 2–10s, during-pipeline for >10s. Result-format-oriented ("Find files" / "Ask about this"), not classification-oriented. | §9 | Moderate-strong |
| **Answer-first preview**    | Overlay/slide-out panel showing source document with cited passage highlighted. Single-column preserved underneath. | §10 | Moderate |
| **Document-list preview**   | Two-panel split (results + preview). Formal exception to single-column constraint. Appears on result selection. | §10 | Strong (desktop convention) |
| **RAG answer streaming**    | Sequential: pipeline completes → answer streams. Token-by-token at reading speed. Citations appear inline as generated. | §20 | Moderate |
| **Four quadrants**          | Fast+RAG, Fast+noRAG, Deep+RAG, Deep+noRAG all produce coherent experiences from the same interaction arc. | §20 | Strong |

### What this position does not cover

This design position establishes the interaction architecture, information hierarchy, language register, result presentation modes (with and without RAG), override mechanism, and document preview for JustSearch's long-term UX. The remaining design layers are addressed in subsequent sections:

- **Visual language** (§12), **motion design** (§13), **error/degraded states** (§14), **first-use experience** (§15), **accessibility** (§16) — the visual and behavioral layer beneath the interaction architecture.
- **Spatial layout** (§17) — how the interaction zones are arranged on the single-window canvas, including the single-column constraint and its exceptions for document preview.
- **Multi-turn interaction** (§18) — how sessions of multiple queries feel, the independent query model, and the hybrid query-rewriting pattern.
- **Background activity** (§19) — attention management for pipeline completion when the user is away, and ambient communication of background indexing.
- **Duration-adaptive model** (§20) — how the interaction arc scales across the full duration spectrum, including the RAG answer streaming sub-arc and the four quadrants (fast/deep × RAG/noRAG).

---

## 12. Visual Language

The current frontend has a documented "Glass Engine" aesthetic (translucent surfaces, blur effects, OKLCH color tokens), but this is an implementation artifact that has not been validated through real-world use — glassmorphic effects are particularly difficult to verify in automated testing environments. This section treats the visual language as an open theoretical question, establishing principles from research rather than deferring to the current codebase.

### Color palette principles

**Anchor the palette in the blue-teal family.** Research on color and trust (Alberts & van der Geest, 2011; Su & Cui, 2019) consistently identifies blue-family hues as the most trust-associated in Western digital interfaces. For a search tool where the primary user need is trust in results, a blue-teal primary accent is well-grounded. Red and warm tones increase perceived warmth but decrease perceived trustworthiness — the wrong signal for a knowledge tool.

**Constrain the palette to functional signals only.** Cognitive load theory (Sweller, 1988) and multiple practitioner studies converge: monotone palettes with a small number of saturated accent colors reduce cognitive load during sustained use. Saturated color should be reserved for interactive elements, status indicators, and error states — not for decoration or branding. The interface should remain fully interpretable in grayscale; color should enhance information architecture, not carry it.

**Semantic color assignments should be conventional.** Green for success, red/amber for errors and warnings, blue-teal for interactive elements and primary actions. Deviating from these conventions for aesthetic reasons imposes a learning cost that does not serve the user.

### Dark mode vs. light mode

**Research favors light mode as the default for reading-heavy interfaces.** NN/Group's synthesis of multiple studies finds that light mode (dark text on light background) produces better performance on visual-acuity and reading-comprehension tasks, regardless of user age. A 2018 ergonomics study found sustained light-mode exposure may be associated with myopia, but visual fatigue differences between modes were not significant.

**The evidence-based default is light mode with dark mode as a first-class option.** The readability advantage of light mode is the stronger finding. Dark mode preference correlates with low-ambient-light environments and developer-adjacent audiences — this is a valid product positioning choice if made deliberately, but it is not the research-optimal default for a tool whose primary interaction involves reading synthesized answers and source documents.

**Both modes must be designed with equal care.** Whichever is the default, the other must not feel like an afterthought. Typography contrast ratios, accent color visibility, and status indicator legibility must be verified in both modes independently.

### Surface and depth

**Visual hierarchy through elevation, not decoration.** The interaction arc (section 8) requires the UI to communicate spatial hierarchy: the pipeline status band sits above the results zone, which sits above the dormant input state. This hierarchy can be achieved through surface differentiation — subtle background shifts, border weight, shadow depth — without requiring elaborate visual treatments like glassmorphic blur. The principle is: each visual layer should be distinguishable from its neighbors at a glance, but the means of distinction should be as quiet as possible.

**Skeleton slots should feel like absence, not loading.** The skeleton state for result placeholders (section 8, beat 4) should communicate "something is forming here" without demanding attention. A barely-visible shape — low contrast against the background — is more consistent with calm technology's periphery principle than a prominently shimmering skeleton. Content appears in the slot when it is ready; the slot itself is ambient presence, not focal content.

**On animation in skeleton slots:** Empirical research on skeleton screens (e.g., Shopify's A/B tests, Luke Wroblewski's case studies) generally finds that subtly animated skeletons outperform static ones for perceived loading speed. The calm technology argument for static skeletons is philosophically coherent but is an aesthetic choice, not a research-derived conclusion. A reasonable middle ground: a very slow, subtle pulse (not a shimmer or wave animation) that signals activity without demanding focal attention. This preserves the calm technology principle while acknowledging the empirical evidence. The key constraint is that the animation must not be distracting during the 10–60 second deep search case — it will be visible for a long time, and any animation that draws attention will become irritating. If in doubt, static is safer than animated.

### Typography

**Use a single sans-serif family with weight variation for all UI text.** Research across design system literature (Material Design, Apple HIG, IBM Carbon) recommends against mixing multiple typeface families in information-dense interfaces — it introduces visual noise without proportional benefit. A single family with sufficient weight range (light through bold) handles UI labels, result snippets, and long-form synthesized answers effectively.

**Reserve monospace for code and technical identifiers only.** Proportional fonts are clearly superior for general reading (MIT HCI research). Monospace should appear only where character alignment matters: file paths, code snippets, query syntax examples.

**Minor Third (1.200) scale ratio.** For an information-dense single-window application mixing short labels, medium snippets, and long-form answers, the Minor Third ratio provides enough hierarchy across 4–5 levels without excessive size jumps. This is the standard recommendation for dense, productivity-oriented interfaces.

**Generous line-height for long-form text.** The synthesized answer (section 10) will be the primary reading surface. Line-height of 1.4–1.6 for answer text reduces scanning fatigue. Tighter line-height (1.2–1.3) is appropriate for compact UI elements like source cards and pipeline stage labels.

### Information density

**Offer configurable density, defaulting to a moderate level.** Research on information density and expertise (Algolia) shows that technical users prefer higher density while general users prefer more space. A configurable density system (compact, default, spacious) accommodates both without forcing either. The default should favor readability over density — the single-input paradigm already implies a preference for space over chrome.

### Visual specification

The token values below are the canonical light-mode specification for the design system this document defines (distinct from the existing Glass Engine tokens). A companion static-HTML prototype at `docs/tempdocs/231-prototype/` was retired on 2026-05-13 — it was the only consumer of these tokens at the time and had gone dormant without being adopted into `modules/ui-web/`. The token table below remains the canonical source if the design system is ever revived.

**Surface palette:**

| Token | Value | Purpose |
|-------|-------|---------|
| `--bg-page` | `#f0f1f5` | Page background (outside the app frame) |
| `--bg-card` | `#ffffff` | Card/panel surface, input field background |
| `--bg-elevated` | `#f4f5f9` | Elevated surface (chips, locked inputs, stage detail) |
| `--bg-hover` | `#eaecf2` | Hover state |
| `--bg-skeleton` | `#e8eaf0` | Skeleton slot fill |

**Text hierarchy:**

| Token | Value | Purpose |
|-------|-------|---------|
| `--text-primary` | `#1a1d26` | Primary text, titles, answer body |
| `--text-secondary` | `#5a6070` | Secondary text, snippets, stage labels |
| `--text-tertiary` | `#8a90a0` | Tertiary text, paths, dates, metadata |
| `--text-placeholder` | `#b0b5c2` | Placeholder text, elapsed time |

**Accents:**

| Token | Value | Purpose |
|-------|-------|---------|
| `--accent-teal` | `#0d9488` | Primary accent (active stages, selected cards, override buttons, teal highlights) |
| `--accent-blue` | `#2563eb` | Citation numbers, links |

**Semantic colors (always paired with non-color signal per §16):**

| Token | Value | Signal | Non-color signal |
|-------|-------|--------|-----------------|
| `--color-success` | `#059669` | Completed stage | Checkmark icon |
| `--color-warning` | `#d97706` | Degradation notice | Warning icon + text |
| `--color-error` | `#dc2626` | Error state | X icon + text |

**Category colors (file type icons):**

| Token | Value | File type |
|-------|-------|-----------|
| `--cat-doc` | `#3b82f6` | Documents (Word, Excel) |
| `--cat-code` | `#059669` | Code files |
| `--cat-pdf` | `#dc2626` | PDFs |
| `--cat-note` | `#d97706` | Notes, markdown |
| `--cat-image` | `#a855f7` | Images |

**Type scale (Minor Third 1.200):** `0.694rem` / `0.833rem` / `1rem` / `1.2rem` / `1.44rem` / `1.728rem`

**Font stack:** `'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif` (body) / `'JetBrains Mono', 'SF Mono', 'Cascadia Code', monospace` (code/paths)

**Dark mode values are not yet specified.** The prototype is light-mode only. When dark mode is designed, the surface palette inverts (dark backgrounds, light text), accent and semantic colors adjust for contrast on dark surfaces, and the category colors may need luminance adjustment per the OKLCH perceptual uniformity principle.

---

## 13. Motion Design Principles

### Purpose filter

Every animation in JustSearch must serve one of three purposes (NN/Group):

1. **Communicate state change** — an element transforming to reflect new status or data.
2. **Prevent disorientation** — spatial transitions that help the user track where content moved.
3. **Strengthen signifiers** — making interactive elements visible or confirming interaction receipt.

Animation that serves none of these purposes should not exist. A restrained visual language (section 12) already provides richness through surface hierarchy and typography; decorative motion on top of that risks feeling overwrought.

### Duration and easing

| Animation type        | Duration        | Easing                             | Example                                           |
| --------------------- | --------------- | ---------------------------------- | ------------------------------------------------- |
| Micro-feedback        | 100ms           | ease-out                           | Button press, checkbox toggle                     |
| Stage transition      | 150-200ms       | ease-out                           | Pipeline stage advancing to next                  |
| Panel/zone transition | 250-300ms       | spring (stiffness 300, damping 30) | Pipeline band collapsing, results zone expanding  |
| Complex multi-element | 300-500ms total | staggered ease-out                 | Skeleton slots filling, stage completion sequence |

**Nothing above 500ms.** NN/Group explicitly warns that animations exceeding this threshold feel sluggish.

**No linear easing.** Linear animation feels mechanical and unnatural. Ease-out (deceleration) is the default for elements entering the viewport — they arrive and settle, mimicking physical world behavior. Ease-in-out is appropriate for elements that move within the viewport (the pipeline band collapsing as results expand). Spring physics (e.g., stiffness ~300, damping ~30) produces natural deceleration and is well-suited for the pipeline-to-results transition.

### Specific arc motions

**Commitment moment (beat 3).** The input field locks and the first stage indicator appears. The *state flip* must feel instantaneous — no animation on the lock itself, and the stage indicator should fade-in at 100ms ease-out. The cancel affordance appears alongside at the same timing. The input field's spatial repositioning from center to top (§17) is a concurrent animation permitted at this moment: the user sees the field moving (250–300ms spring) while the stage indicator fades in. "Instantaneous" describes the state change (locked input, pipeline started), not the spatial transition.

**Stage transitions (beat 4).** When one stage completes and the next begins, use a sequential reveal: the completed stage visually marks done (a check or filled indicator, 100ms), then the next stage's label fades in (100ms, staggered by 50ms). The stagger communicates order — it is not simultaneous. Total per-transition: ~150ms.

**Skeleton slot appearance.** Slots fade in at 150ms each, staggered 50ms between slots. No slide, no scale — content appears in place. Filling a slot transitions from skeleton to content via opacity crossfade at 200ms. This is not dramatic; it is matter-of-fact. Content arrives.

**The completion pulse (beat 5).** All stages reach done state in a rapid stagger (50ms between stages, ~300ms total). A brief pause (200ms hold) allows the user to register completion. Then the pipeline band collapses using spring physics (300ms, stiffness 300, damping 30) as the results zone expands proportionally. The directionality is vertical: the band contracts upward, results expand upward to fill.

**Follow-up transition (beat 7).** Old results dim via opacity reduction (0.4 opacity, 200ms ease-out) when a new pipeline begins. They are not removed — they fade to background while new pipeline stages appear above them.

**Override affordance appearance (§9).** When the override appears (duration-adaptive: at 2s during pipeline, or with results), it fades in at 150ms ease-out. No slide or scale — it appears in place at text-secondary weight. Clicking the override should have instant visual feedback (100ms) before the pipeline re-routes.

**Preview panel transition (§10).** Document-list preview panel slides in from the right at 250ms ease-out. Overlay citation preview fades in at 200ms with a subtle backdrop dim (0.3 opacity on content beneath). Both dismiss at the same rate. Preview content within the panel appears without transition — the panel animation is sufficient; animating the content would add delay.

**Answer streaming (§20).** No animation on individual tokens — text simply appears as it is generated. The answer block itself fades in at 150ms when the first token arrives. Source cards fade in at 150ms each as their first citation appears in the answer text.

### Reduced motion

`prefers-reduced-motion` is a non-negotiable constraint, not an optional enhancement.

**Reduced-motion alternatives for all arc motions:**
- Stage transitions: instant state change (no fade, no stagger). The stage indicator updates immediately.
- Skeleton slots: appear instantly. Content fills with no transition.
- Completion pulse: all stages mark done simultaneously. Pipeline band disappears (no collapse animation); results zone appears.
- Follow-up transition: old results dim instantly.
- Override affordance: appears instantly, no fade.
- Preview panel: appears instantly (no slide). Overlay appears instantly with backdrop.
- Answer streaming: text appears as generated (no change — streaming is inherently non-animated).

**The critical principle:** reduced motion removes the *motion*, not the *information*. Stage state changes are still visible; the pipeline band still yields to results; skeleton slots still fill with content. Only the temporal interpolation between states is removed.

---

## 14. Error and Degraded States

### The four categories

The system's real failure taxonomy maps to four distinct UX categories, each requiring different treatment. These are not variations of "something went wrong" — they are categorically different experiences.

### Category 1: Total failure (Head process down)

The Tauri shell remains running, but the backend API is unreachable. No search, no status, no data.

**What the user sees:** A full-screen message in the stage zone. No spinner — the user cannot meaningfully wait because there is no recovery ETA. The message is system-accountable and factual:

> "JustSearch is restarting. Your files and index are safe."

If the Head process recovers (Tauri can detect this via health polling), the UI transitions back to dormancy automatically. If it does not recover within a reasonable window, the message updates:

> "JustSearch could not restart. Close and reopen the app to try again."

**Language register:** Plain, calm, system-accountable. No humor, no apology theater. Consistent with section 11's plain action language. Technical detail (process state, exit code) is not shown — there is no "Show details" here because the user cannot act on the information.

### Category 2: Partial degradation (inference offline, worker healthy)

This is the most common degraded state. Search still works, but in text-only mode. The `hybridFallback` and `effectiveMode` response fields already signal this from the backend.

**What the user sees:** Search results appear normally, but a compact inline notice sits at the top of the results zone:

> "Searched as text only — AI features are offline"

This is not a modal, not a toast, not a banner that pushes content down. It is a single line, styled at text-secondary weight, within the results zone. It informs without alarming. The user's results are present and usable; the notice explains why they might be less comprehensive than expected.

**Consistency with section 11:** This is process transparency ("what happened") not mechanism transparency ("why"). The reason the inference is offline (VRAM insufficient, model load failed, process crashed) is mechanism detail, available behind "Show details" if the user wants it but never forced.

**The during-pipeline variant:** If the user submits a query and inference goes offline mid-pipeline, the pipeline stage indicator should reflect the degradation. The relevant stage (e.g., "Semantic expansion") marks as skipped rather than failed — visually distinct from both completed and errored. The pipeline continues with remaining stages. The result-zone notice then explains the partial result.

### Category 3: No results found

The pipeline completed successfully, but zero results matched.

**What the user sees:** The results zone does not go blank. The query is shown back to the user (it remains in the input field per the arc's beat 6), and a message appears where results would be:

> "No results found for [query]. Try different terms or a broader search."

If the search was text-only due to degradation, the notice includes that context:

> "No results found (searched as text only). Try different terms, or wait for AI features to come online for semantic search."

**The UI returns to near-dormancy:** the input field is re-editable (the lock from beat 3 is released), the pipeline band has completed and collapsed, and the empty results zone shows the message. The user is naturally positioned to refine and resubmit.

**Recovery actions:** Suggested alternative queries, if the system can generate them, appear as clickable chips below the message — the same treatment as the dormant-state example queries from section 15. If the system cannot generate suggestions, the message alone with the re-editable input is sufficient.

### Category 4: Pipeline stall or timeout

The pipeline is running but a stage has exceeded its expected duration. The system has not failed — it is slow.

**The critical UX distinction:** "slow" and "broken" look identical to the user unless the UI explicitly differentiates them. The pipeline stage indicator (section 8) already communicates "still working" through its active state. The stall treatment adds a temporal signal: after a stage exceeds a threshold duration, the indicator transitions from active to stalled.

**What the user sees:** The active stage's animation changes — it stops its normal progress animation and shifts to a slower, muted pulse (or in reduced motion, the text updates). The stage label appends a temporal signal:

> "Stage 3 of 5: Ranking results — taking longer than expected"

The cancel affordance (present since beat 3) is visually promoted — it becomes more prominent (e.g., text-secondary → text-primary, or gains a subtle border). The user can cancel and get whatever partial results are available, or continue waiting.

**If a hard timeout occurs:** the stage marks as timed-out (visually distinct from completed, failed, or skipped), the pipeline completes with whatever it has, and the results zone shows partial results with a notice:

> "Some search stages timed out. Results may be incomplete."

**Language register:** Factual, not alarmist. "Taking longer than expected" is honest without implying failure. "Results may be incomplete" is an epistemic hedge (consistent with section 11's first-person epistemic register for the answer context). This is a permitted exception to section 11's register boundary: result-zone notices that describe the quality of delivered results sit at the boundary between pipeline reporting and answer presentation. Mild epistemic hedges ("may be incomplete") are appropriate here because they describe the output the user is about to read, not the pipeline process. Technical detail (which stage timed out, duration) is behind "Show details."

### Cross-category principles

| Principle                    | Application                                                                                                                                                  |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| System-accountable language  | The system takes responsibility. Never "You searched for an invalid query." Always "No results found for [query]."                                           |
| Every state offers an action | Total failure: close and reopen. Degradation: continue with reduced results. No results: refine query. Stall: cancel or wait.                                |
| Technical detail is opt-in   | Reason codes, component states, and timing data are behind "Show details" — consistent with section 11's process/mechanism boundary                          |
| No humor                     | Error messages are seen repeatedly. Humor becomes irritating. Plain language ages well.                                                                      |
| No blanket disclaimers       | "AI may make mistakes" on every result is trained-away within sessions (section 10). Disclaimers appear only when they carry specific, relevant information. |

---

## 15. First-Use Experience

### The input field is the onboarding surface

Research confirms that up to 50% of users navigate directly to the search input on a new application (Econsultancy/GfK). For JustSearch's single-input paradigm, this percentage approaches 100% — the input field is the only primary affordance. The entire first-use experience happens at the input field and in the response to the first query.

NN/Group (2020) is definitive on the tutorial question: tutorials interrupt users, do not reliably improve task performance, and are quickly forgotten. Contextual help triggered at the moment of need is superior across every measured dimension. JustSearch should never show a tutorial, walkthrough, or onboarding wizard. Industry evidence from 2025–2026 reinforces this: an analysis of onboarding flows across 7 leading AI products (UserGuiding, 2026) found that only 4 of 10 AI tools still use traditional tutorials, and the best-performing flows get users to a real output within 3 minutes.

**However, the "what does this do?" gap must be addressed.** NN/G's September 2025 research on generative AI tools found that first-time users "struggled to understand the tools' functionality" and "felt confused when these tools assumed users knew how they worked." Google PAIR's mental models guidebook recommends being "up-front about what your product can and can't do the first time the user interacts with it." For JustSearch — a novel product category where users have no prior mental model for "local neural search" — rotating example queries alone may not communicate what distinguishes this from filename search or web search. A brief capability framing (one sentence or tagline near the input field, not a tutorial) should establish the product category: something like "Search your files by meaning, not just keywords." This is a schema-setting device (section 9), not an onboarding step.

### The anchoring bias imperative

Nourani et al. (IUI 2021) found that users who observed system strengths early formed better mental models of system capabilities, while users who encountered errors early formed "negative first impressions that ultimately lead to a limited mental model and underestimation of system capabilities." Critically, "the addition of explanations was not enough to counteract the strong effects of early impressions."

**The direct implication for JustSearch**: the first search result a user sees will disproportionately shape their mental model of the product. If the example queries lead to strong, relevant results, users will form accurate and positive mental models. If the first search fails or returns irrelevant results, no amount of explanation will recover the impression.

This means example queries must not be generic. They should be **curated for the user's actual indexed content** when possible — showing queries that will produce strong results against the user's corpus. For the very first session (before meaningful indexing has completed), generic examples are acceptable but should be chosen to demonstrate the *range* of query types rather than the depth of any single type, so that the user discovers a capability that works rather than encountering one that doesn't.

### Rotating example queries

The primary onboarding mechanism is rotating example queries displayed as placeholder text or as subtle prompt text near the input field. Research favors these over static placeholder text because they communicate breadth of capability — a single static placeholder can only suggest one kind of query. Algolia's documentation validates animated placeholders that "mimic the effect of typing queries" as increasing search box usage and discovery.

**Design principles for the rotation:**
- **3–5 diverse examples** that span both search-style and conversational-style queries (schema-setting from section 9). Example range: "quarterly earnings report Q3" (keyword search), "What documents discuss machine learning?" (conversational), "find all PDFs from last month" (structured command). **Categorized examples** (spanning different capability types) communicate breadth more effectively than random rotation (Algolia, Pencil & Paper UX research).
- **4–5 seconds per example** — slow enough to read fully, fast enough to demonstrate variety within a reasonable attention window.
- **Subtle crossfade transition** between examples — 200ms opacity, consistent with section 13's motion principles. The rotation is ambient (peripheral), not attention-grabbing. Netflix A/B testing found that simple fade-ins outperformed complex animations for conversion.
- **Stops rotating on input focus.** Once the user engages the field, the rotation ceases and the placeholder clears. The user's intent takes over.

### The empty state below the input

Section 9 established that the empty state should never be truly empty. In the dormant state (beat 1), below the input field:

- **For returning users:** Recent queries, rendered as clickable chips. These provide quick-access and signal that the system remembers context.
- **For new users (no history):** Example query chips that duplicate the rotating placeholder content in a clickable, persistent form. These allow the user to try the system with zero typing — reducing the cold-start friction identified in the command palette discoverability research.

Both variants use the same visual treatment: muted background, subdued text color, small type. They do not compete with the input field for focal attention — they are peripheral affordances, consistent with calm technology's attention model.

**The trend in desktop search** (observed in macOS Tahoe Spotlight, Windows Search, Raycast) is toward making the empty state a browsable surface — recent files, categorized tabs, suggested content. Everything (voidtools) shows the entire corpus before any query. JustSearch's empty state should at minimum show recent queries; for new users, the example query chips plus the capability framing sentence provide the minimum viable empty state. Whether to show recent files or other browsable content is an implementation question outside theoretical scope but consistent with the trend.

### Progressive disclosure after first search

The first successful query is the real onboarding moment. It reveals:
- The pipeline status band (section 8, beat 4) — the user learns that search takes time and shows progress.
- The results zone with answer and source cards (section 10) — the user learns the response format.
- The interaction arc in full — commitment, labor, resolution, consumption.

Power features are not revealed at this point:
- Query syntax (Lucene operators, field qualifiers) — discovered through documentation or contextual hints triggered when the user types an operator character.
- Search mode controls (text/hybrid/vector) — surfaced only if the user seeks them out.
- Density settings, keyboard shortcuts, advanced configuration — available through the existing settings and command palette patterns, not pushed during onboarding.

This progressive disclosure sequence is consistent with the single-input paradigm: the field is simple on arrival, and the system's depth reveals itself through use. The user's first impression is "this is a search box." Their second impression, after the first query, is "this is a search box that does a lot of work." NN/G's progressive disclosure research and Notion's implementation both recommend limiting disclosure to 2–3 layers to avoid user frustration.

---

## 16. Accessibility as Design Constraint

Accessibility requirements are established here at the theory level so they constrain implementation choices from the start, rather than being retrofitted after visual and interaction design are locked.

### Reduced motion (WCAG 2.1 SC 2.3.3, AAA)

The implementation must detect `prefers-reduced-motion` at the OS level. All arc animations defined in section 13 have reduced-motion alternatives specified. The governing principle: reduced motion removes *temporal interpolation between states*, not *the information that motion conveys*. Stage transitions still happen; they just happen instantly.

This is not optional and should not require user configuration within the app — the OS-level preference is authoritative.

### Pipeline stage announcements (ARIA live regions)

Pipeline stage transitions (section 8, beat 4) must be announced to screen readers. When stage 2 begins, the screen reader should announce "Stage 2 of 5: Ranking results by relevance" without requiring the user to navigate to the pipeline status zone.

**Implementation principle:** Use `aria-live="polite"` on the pipeline status element. Polite, not assertive — stage transitions should not interrupt the user's current reading or navigation. The compact stage label (section 4's always-visible indicator) is the announced content.

The expanded sub-narration ("Show details") is *not* in a live region. It is discoverable content, not pushed content — consistent with the process/mechanism boundary from section 11.

### Keyboard navigation for results and citations

- **Source cards:** Tabbable. Enter to expand or navigate to full document. Arrow keys for sequential navigation within the source card list.
- **Inline citations:** Focusable (not just hoverable). The hover-preview described in section 10 must also activate on keyboard focus. This is a direct consequence of section 10's citation design — hover-only interactions exclude keyboard and screen reader users.
- **Pipeline cancel affordance:** Tabbable and reachable via keyboard from the moment it appears (beat 3). It should be in the natural tab order after the input field.

### High contrast mode

The existing high contrast mode (pure black/white, disabled backdrop-filter, no transparency animations) must extend to all new elements:

- Pipeline stage indicators must use border or text differentiation, not opacity or subtle color shifts alone, to communicate state (active, completed, skipped, stalled, failed).
- Skeleton slots must have visible borders in high contrast — any low-opacity or translucent treatment used in normal mode will be invisible without transparency support.
- Error and degradation notices must be legible without background color — use text weight and icon differentiation, not background tinting.

### Color independence (WCAG 1.4.1, Level A)

All functional color usage must also encode information through a non-color channel:

| Element                   | Color signal                        | Non-color signal                                        |
| ------------------------- | ----------------------------------- | ------------------------------------------------------- |
| Pipeline stage: completed | Green accent                        | Checkmark icon                                          |
| Pipeline stage: active    | Teal accent                         | Animated indicator (or "active" text in reduced motion) |
| Pipeline stage: skipped   | Amber/muted                         | Dash icon or "skipped" label                            |
| Pipeline stage: failed    | Red accent                          | X icon                                                  |
| Error notice              | Red/amber background tint           | Icon prefix + explicit text                             |
| Degradation notice        | Amber tint                          | Icon prefix + explicit text                             |
| Source card category      | Category color (blue/green/magenta) | Category label text                                     |

### Focus management during the arc

When the pipeline completes (beat 5 → beat 6 transition), keyboard focus should move programmatically to the results zone — specifically to the first focusable element in the results (the synthesized answer region or the first source card). This prevents keyboard users from being stranded at the (now-collapsed) pipeline status zone and having to manually Tab through to reach the results.

This focus move should be announced: `aria-live="polite"` on the results zone container, or a brief `role="status"` element that says "Search complete. Showing results." The announcement confirms to screen reader users that the pipeline has finished and results are available without requiring them to visually observe the completion pulse.

### Accessibility for new interactive elements

**Override mechanism (§9):** The override affordance ("Find files instead" / "Ask about this instead") must be tabbable and announced. When it appears (duration-adaptive: after 2s during pipeline, or after results), its presence should be announced via `aria-live="polite"` so screen reader users know the option exists. It should appear in the tab order after the cancel affordance and before the result list.

**Document preview panel (§10):** When the preview panel opens (document-list layout), focus should move to the preview content. Escape should close the panel and return focus to the selected result in the list. Arrow keys in the result list should not automatically update the preview — this prevents screen readers from losing their reading position on every list navigation. The preview panel needs a descriptive `aria-label` ("Document preview: [filename]").

**RAG answer streaming (§20):** The streaming answer should be in an `aria-live="polite"` region so screen reader users receive the content as it arrives, without interrupting their current reading. However, token-by-token announcements would be overwhelming — the live region should batch updates at sentence boundaries or on a 2–3 second timer, whichever comes first. When answer generation completes, a brief announcement ("Answer complete") confirms the streaming has finished.

---

## 17. Spatial Layout

The interaction arc (section 8) defines temporal flow — seven beats from dormancy to return. Sections 12–16 define visual zones, motion, and state treatments. What is missing is the spatial theory: how the zones relate to each other on the single-window canvas, how the layout transforms across beats, and what spatial principles constrain these choices.

### The single-column constraint

The spatial translation of the Single-Input Paradigm mandates a strict single-vertical-column architecture. The rationale is firmly grounded in spatial consistency and reading comprehension literature:

- **Single-column layouts optimize first-time reading comprehension.** An eye-tracking study by Namatame & Kitajima (HCII 2024) validated that single-column layouts reduce cognitive load and enhance comprehension boundaries for first-time reading, contrasting with multi-column formats which augment reference reading. Because JustSearch's primary output is novel synthesized text, the single-column paradigm is neurologically optimal. Earlier validations (e.g., Dyson & Haselgrove, 2001) confirm that bounded line lengths (approx. 55-60 characters) in single columns specifically benefit varied reading velocities.
- **Spatial Anchoring and Temporal Flow.** The input field persists across all states (dormancy through consumption) as the spatial anchor. A single column ensures that dynamically injected zones (status pipeline, results) flow sequentially downward through time without triggering horizontal visual-search displacement.
- **Multi-column layouts falsely signal parallel equivalency.** A bifurcated layout implies simultaneous task vectors. The JustSearch arc is strictly serial (submit → process → consume).

**Constraint Exceptions (section 10):** The base hierarchy is exclusively single-column. Two exceptions exist, both specified in section 10's document preview subsection:

1. **Answer-first citation preview:** When the user clicks a citation in a synthesized answer, an overlay or slide-out panel shows the source document with the cited passage highlighted. The single-column layout is preserved underneath — this is a modal interruption, not a layout change.
2. **Document-list preview panel:** When no RAG answer is present and the user selects a result in the document-list layout, a two-panel split (results + preview) replaces the single-column results zone. This is a formal layout change, justified by the shift in user task: the user is scanning candidates (reference reading), not reading synthesized text (first-time reading). The Namatame & Kitajima finding that multi-column formats "augment reference reading" directly supports this exception.

### Vertical zone stacking

The canvas has three zones, stacked vertically:

| Zone                      | Content                                                    | Lifecycle                                                                                                                                                                                                                                            |
| ------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Input zone** (top)      | Input field, cancel affordance during pipeline             | Always present. Fixed position.                                                                                                                                                                                                                      |
| **Status zone** (middle)  | Pipeline stage indicator, empty-state chips/recent queries | Present during beats 1, 3–5, 7. Content varies: empty-state chips in beat 1; pipeline stages in beats 3–5 and 7. Collapses at beat 5→6 transition. In dormancy (beat 1), this zone holds the example query chips and recent queries from section 15. |
| **Results zone** (bottom) | Skeleton slots → filled results. Content varies: answer + source cards (RAG active) or document-list with optional preview panel (no RAG). | Appears during beat 4 (skeleton), fills during beat 5, dominates during beat 6. In document-list mode, may split into two-panel layout on result selection (see §10 document preview). |

The status zone and results zone are vertically adjacent and share the space below the input zone. During beat 4 (Labor), both are visible: status band above, skeleton results below. At beat 5→6 (Resolution → Consumption), the status zone collapses upward and the results zone expands to claim its space. This is the "complete and yield" transition described in section 8.

### The dormant canvas

In beat 1 (Dormancy), the canvas is mostly empty. The input field sits at a vertically centered or upper-third position — not pinned to the very top of the window. This centering communicates "start here" more effectively than a top-pinned field, which reads as a toolbar element rather than a focal point.

Below the input field, the empty-state content (section 15: rotating example queries, recent search chips) occupies the status zone area at reduced visual weight. The results zone does not exist yet.

**The vertical shift at commitment:** When the user submits (beat 3), the input field transitions from its centered dormant position to its pinned top position. This is a spatial expression of the state change: the field moves from "the thing you interact with" to "the context for what follows." The results zone needs the vertical space; the centered position would waste it.

**The spatial consistency tradeoff.** Research on spatial consistency (Scarr, Cockburn, & Gutwin, CHI 2012/2013) establishes that keeping UI elements in stable, fixed positions allows users to develop implicit location memory, reducing visual search time. Moving the input field violates this principle. However, the tradeoff is acceptable for three reasons: (1) the move is a one-time transition per query cycle, not a repeated within-page repositioning; (2) the destination position (top) is the conventional expected location for search fields — NN/Group's spatial memory research confirms users anticipate search bars at the top of an interface; (3) the pattern is deeply established convention (Google's homepage-to-results transition performs this exact move for billions of users). The cost is a brief moment of disorientation at commitment; the benefit is a dramatically more usable dormant state.

This transition should be smooth (section 13: 250–300ms, spring physics) and must not feel like the input is being taken away — it is being repositioned, and the query text remains visible. In reduced motion, the repositioning is instant.

### Width constraint

The single column should have a maximum width, even in a wide window. The canonical guideline is Bringhurst's 45–75 characters per line (*Elements of Typographic Style*, 1992/2004), with 66 as the ideal — derived from print typography but widely adopted by digital design systems. On-screen evidence complicates this: Shaikh & Chaparro (2005, *Usability News*) found that passages at 95 characters per line produced the fastest reading speed on screen, with no significant comprehension difference. The practical consensus in major design systems (Material Design 3: ~60 characters; U.S. Web Design System: ~75 characters) settles around 60–75 characters as the maximum, splitting the difference between comprehension and speed. For JustSearch's synthesized answer text, which users will read carefully rather than skim, the lower end of this range (~60 characters) is more appropriate.

A centered column with generous horizontal margins also reinforces the single-focus paradigm — the app does not expand to fill available space, because it has one thing to show at a time. NN/Group's eye-tracking research ("Horizontal Attention Leans Left") found that 80% of fixations fall on the left half of the page — users do not expand their scanning area rightward on wider screens. A constrained, centered column keeps all content within the natural fixation zone. Peripheral space is intentionally empty, consistent with calm technology's principle that absence communicates as clearly as presence.

### Scroll behavior

During beat 6 (Consumption), the results may exceed the viewport height. The input zone should remain fixed (sticky) at the top of the viewport as the user scrolls through results. A usability study by Denney (2012, reported in Smashing Magazine) found that sticky navigation enabled 22% faster navigation compared to non-sticky equivalents, with participants overwhelmingly preferring the sticky version. This directly applies to the "I want to refine my search" scenario — the input field must be immediately accessible without scrolling.

**Viewport budget.** USAGov research (2023) found that user satisfaction drops when sticky elements occupy more than 20–30% of the viewport — particularly problematic at high zoom levels for accessibility. A single sticky input zone is far less costly than a full navigation bar, but it must remain thin: the input field plus minimal chrome, not an expanding panel.

The pipeline status zone, having collapsed at beat 5→6, is no longer visible during scrolling. If the user wants to review pipeline details post-completion (e.g., which stages ran, timing), this information could be accessible through the input zone's context (a small expandable element near the query) rather than requiring the user to scroll past results.

### Reading patterns

NN/Group's foundational F-pattern research (Nielsen, 2006; 232 users) and its 2020 update (Pernice & Nielsen, 500+ participants) established that users scan text-heavy pages in an F-shape: a horizontal sweep across the top, a shorter sweep partway down, then a vertical scan down the left side. The F-pattern emerges most strongly when content lacks visual hierarchy — it is a symptom of poor structure, not an aspirational target.

For JustSearch's single-column results: the synthesized answer at the top receives the first horizontal sweep (the most attention). Source cards below receive progressively less attention unless their visual hierarchy is strong (distinct titles, bold query terms, clear card boundaries). This reinforces section 10's position that the answer-first layout is correct — it places the most important content where the F-pattern guarantees the most attention. It also reinforces the 3–5 source card limit: cards beyond the third are in the vertical-scan-only zone and will be skimmed rather than read unless the user has a specific reason to look further.

---

## 18. Multi-Turn Interaction and Session Model

Section 8's beat 7 (Return) defines the mechanics of a single follow-up query: refinement preserves old results dimmed; new search clears them. But the theory of a *session* — a sequence of multiple queries over an extended period — is not addressed. This section establishes principles for how multi-turn interaction should feel.

Empirical data on session length is relevant: large-scale query log analysis consistently finds that the mean number of queries per session is **2–3** (Silverstein et al. 1999, ~1 billion AltaVista queries: mean 2.02; Jansen & Spink 2006, cross-engine comparison: mean 2.84; Jansen et al. 2007, JASIST: mean <3 queries, <30 minutes). Most sessions are short. But the distribution has a long tail — sessions involving exploratory research or multitasking can be much longer (Spink et al. 2002: 11.4% of sessions involved multitasking across topics and were twice as long). JustSearch's minute-long pipeline makes even a 2-query session a meaningful multi-turn experience.

### The independent query model vs. conversational threading

The conversational AI paradigm establishes an expectation of indefinite thread persistence and cross-turn contextual accumulation. JustSearch rejects this model in favor of the **Independent Query Model**. Each submission triggers an isolated pipeline execution; the system does not aggregate conversational dialogue state.

This is a deliberate product choice, supported by both the current pipeline architecture and the research on conversational search risks:

- **Erosion of Source Verification:** 2025 analyses on conversational search versus traditional frameworks (e.g., Hoffer et al., CHI 2025; broader 2025 AI search market trends) confirm that while conversational threading accelerates path-to-answer, it significantly degrades user engagement with primary sources. Conversational platforms induce a "one-stop solution" behavior, conflicting with the core directive that source verification must be encouraged.
- **Compounding Hallucination Risk:** Extended conversational context windows increase the probability of hallucination decay (the drift of verified facts across concatenated turns). Independent querying isolates the context scope to a single execution environment.
- **Pipeline architecture alignment:** The search pipeline (tempdocs 270, 306, 309) is stateless by design — query classification, retrieval, fusion, and reranking all operate on the current query with no reference to previous queries. Adding cross-query context would require storing previous query/result state, detecting anaphoric references, and silently rewriting queries before they enter the pipeline — non-trivial infrastructure that introduces exactly the kind of hidden context accumulation the research warns against.

**Architectural Constraint:** The UI must not feign dialogue accumulation. Sessions are discrete sequences of independent resolutions.

**Recognized Frictional Cost:** By not supporting cross-query anaphora resolution (e.g., parsing "those from last year" in relation to a prior query), the system requires users to articulate self-contained follow-ups. This is a real UX cost. A user who has just searched for "quarterly earnings reports" and wants to narrow to last year's must type "quarterly earnings reports 2025" rather than "those from last year." The query history chips (below) and the refinement chain (section 8, beat 7 — editing the existing query in place) partially mitigate this friction, but they do not eliminate it.

The tradeoff is defensible: the hallucination isolation and source verification benefits outweigh the friction cost for a tool whose primary value proposition is trustworthy retrieval from a personal corpus. Microsoft Research (May 2025, 200K+ conversations across 15 models) found a 39% performance drop in multi-turn vs. single-turn LLM interactions, with "instruction drift, intent confusion, and contextual overwriting" as recurring failure modes. Citation drift research (ACL 2025 Workshop) found that citations "mutate, disappear, or get fabricated" across turns, with LLaMA-4 fabricating up to 85.6% of citations by the third turn. These findings strongly justify independent pipeline execution.

**The emerging hybrid pattern.** However, recent production evidence reveals a middle ground that every major search product has converged on: **context-aware query understanding + independent retrieval.** Perplexity, ChatGPT, and Google AI Mode all perform fresh retrieval on every query, but use conversation context to understand *what* to search for. Amazon's context-aware query rewriting (ACL 2023) achieved 11.6% MRR improvement and 20.1% HIT@16 improvement by using a session graph of previous queries to rewrite the current query before feeding it into standard (stateless) retrieval. Google AI Mode decomposes each query into 8–12 independent sub-queries (query fan-out), each retrieved independently.

The pattern that preserves JustSearch's independent pipeline invariant while reducing friction is: **extractive query expansion from session history** — pulling terms from the user's recent queries to expand an ambiguous current query, rather than generating new terms (which risks hallucination). This keeps the retrieval pipeline fully stateless while allowing the query formulation layer to benefit from session context. Whether to implement this is a product decision that should be informed by observing actual user reformulation behavior; the theoretical position is that the architecture allows it without violating the independent execution invariant.

### Query history as navigation, not context

The distinction: conversational history is *context* (the system uses it to interpret the next message). Query history is *navigation* (the user can revisit previous searches, but each is independent). Hearst (*Search User Interfaces*, 2009) draws this distinction explicitly, identifying search history as serving two roles: **process support** (maintaining context during an ongoing investigation) and **re-access** (returning to previously found information). For JustSearch's independent-query model, the re-access function dominates.

The re-access function is quantitatively important. Teevan et al. (SIGIR 2007, analysis of 114 users over one year) found that **as many as 40% of all queries are re-finding queries** — users seeking information they have previously retrieved. This is not a minor convenience feature; it addresses a behavior that accounts for nearly half of all search activity. Changes to search engine results between sessions actually **hinder** re-finding, reinforcing the value of re-executing queries (fresh pipeline run) rather than caching stale results.

**Visible query history** should exist as a lightweight, non-intrusive element. Principles:

- **Location:** Accessible from the input zone — a small affordance (icon or subtle text) that expands to show recent queries. Not a persistent sidebar; not a conversation thread. Consistent with the single-column layout (section 17).
- **Interaction:** Clicking a previous query re-executes it (a new pipeline run, not a cached result). The previous query replaces the current field content. This is explicitly *not* "scroll up to see old results" — it is "run this search again." This aligns with Teevan et al.'s finding that re-finding queries should produce current results, not cached snapshots.
- **Visual treatment:** Recent queries appear as the same clickable chips used in the dormant empty state (section 15). The design language is consistent: chips always mean "click to search this." Research on search history visualization (Schlögl, Kern, & Granitzer — "QueryCrumbs," IEEE VIS 2017; *Visual Informatics* 2020) found that compact visual representations of recent queries were understood without instruction and showed sustained uptake in a long-term out-of-lab study with Google Scholar users.
- **Depth:** Show the 5–10 most recent queries from the current session. Not a full search history — that is a settings/management feature, not an interaction surface.
- **Task-organized, not chronological.** Morris, Morris, & Venolia (CHI 2008, Microsoft Research) found that organizing search history around tasks — grouping queries with their results — was preferred over flat chronological browser history for task resumption. For JustSearch's lightweight history affordance, the practical expression is: group refinement chains together (a query and its refinements are one cluster), and separate topically unrelated queries visually.

### The refinement chain

Beat 7 defines refinement as editing the existing query in place. A refinement chain — query → refined query → further refined query — is a natural multi-turn pattern. Each refinement triggers a new pipeline run, and per beat 7, old results dim while new results form.

**The chain should not create visual debt.** After 3+ refinements, the dimmed results from previous iterations are no longer useful — they are visual clutter. The principle: only the *immediately previous* result set persists in dimmed form during a refinement. Earlier results are cleared. The user retains the ability to compare "what I just had" with "what I'm getting now," but not a full archaeological record of every refinement step.

If the user wants to return to a much earlier query, they use the query history chips — which re-execute the search, producing fresh results rather than resurrecting stale ones.

### Session boundaries

What constitutes "a session"? For the purpose of query history and the dormant empty state:

- **A session begins** when the user opens the app (or returns to it after a period of inactivity).
- **A session ends** when the app is closed.
- **Session history persists across sessions** at the storage level (recent queries are remembered between app launches), but the *visible* history in the interaction surface only shows the current session's queries by default. Cross-session history is accessible through a deeper navigation path (settings, search history view), not the lightweight input-zone affordance.

This keeps the interaction surface clean while preserving the utility of long-term query recall.

**Content-based boundaries over temporal ones.** Jones & Klinkner (CIKM 2008) found that session boundaries were better identified by query content (a single word in common between queries signals continuity) than by temporal cutoffs (the traditional 30-minute timeout). This implies that JustSearch's inactivity-based session reset (see below) should be conservative — a user who returns after 45 minutes to search for something topically related to their last query is still in the same task, even if the temporal window has elapsed. The session reset is a visual presentation choice (which queries appear as chips), not a hard semantic boundary.

### The "what was I doing?" problem

If the user leaves the app mid-pipeline (switches to another window) and returns after the pipeline completes (section 19 covers the attention recapture), they land on beat 6 — results are showing. The query is visible in the input field. The context is self-evident: "I searched for X, here are the results."

If the user leaves during beat 4 (Labor) and returns while the pipeline is still running, they land back in beat 4 — the pipeline is visibly progressing. No reorientation is needed; the stage indicator communicates the current state.

If the user returns to the app after a long absence (hours, next day), the app should be in beat 1 (Dormancy) with the query history chips showing their recent searches. Not beat 6 with stale results — results from hours ago are not useful as a landing state. The principle: **stale results have a shelf life.** After a configurable inactivity period, the app resets to dormancy. The user can re-execute any previous query from the history chips if they want fresh results.

Research on cross-session search resumption (Wu et al. 2020, *JASIST*) found that users returning to interrupted search tasks go through two phases: **task preparation** (reorienting, with a blurred information need) and **task resumption** (need becomes clear). The dormant state with query history chips directly supports this: the chips serve as external memory aids during the preparation phase, helping the user recall what they were investigating. Hoeber et al. (2026, *International Journal on Digital Libraries*) found that users with visible search history performed better at resuming after 7–8 day breaks, reporting higher engagement and perceived knowledge gain.

---

## 19. Background Activity and Attention Recapture

JustSearch has two categories of background activity: pipeline runs that the user initiated but may not be watching, and background indexing that the system performs autonomously. Each requires different attention management.

### Pipeline completion: OS notification

When the user submits a query and switches to another application, the pipeline continues running. On completion, the system sends an OS notification. This is already implemented and is the correct approach — it is the minimum escalation that crosses application boundaries.

The research basis is strong. Bailey & Konstan (2006, *Computers in Human Behavior*) found that notifications interrupting mid-task require 3–27% more time to recover from, cause twice the number of errors, and produce 31–106% more annoyance compared to notifications delivered at task boundaries. Iqbal & Horvitz (CHI 2007) found that notification-driven diversions consumed an average of 10 minutes, with an additional 10–15 minutes before the user returned to their original task. The implication: a pipeline completion notification is a genuine interruption cost. It must be worth the cost — and for a minute-long search, it is, because the user explicitly initiated the task and is waiting for the result. But the notification should be minimal: one notification, at completion, with enough context to be useful.

**Principles for the notification:**

- **Content:** The notification should include the query text, so the user remembers what they searched for. "Search complete: [query]" — not "JustSearch has finished processing" (which is mechanism detail, not process context). NN/Group's guidance on long waits (Kaplan, 2021) recommends including the task description in the completion notification so the user can orient without switching back to the app. Apple HIG states: "A notification gives people timely, high-value information they can understand at a glance."
- **No results in the notification.** The notification's job is attention recapture, not content delivery. Results require the full interaction surface (answer, sources, citations) to be useful. A truncated answer in a notification toast is worse than no answer — it delivers partial information without sources, violating section 10's principle that answer and sources must be co-present.
- **Single notification per pipeline.** If the user runs multiple queries in sequence without returning, only the most recent completion should notify. Notification stacking for a single-purpose app is hostile. Research confirms this: Pielot, Church, & de Oliveira (MobileHCI 2014) found that users receive an average of 63.5 notifications per day, and increasing notification volume correlates with increased negative emotions. Alert fatigue research (Ancker et al. 2017, *BMC Medical Informatics*) found that repeated same-type alerts from a single system directly increase dismissal rates. GNOME HIG recommends summarizing rather than sending one notification per event; Microsoft's guidelines warn that "too many interruptions leads to users turning off this critical communication channel for your app."
- **Click navigates to result context.** Microsoft's toast guidelines specify that clicking a notification should launch the app "in the notification's context" — showing the completed result, not a generic landing page. For JustSearch, this means clicking the notification should bring the app to beat 6 with the results visible.

### The return-to-app moment

When the user clicks the notification or alt-tabs back to JustSearch, they arrive at beat 6 (Consumption). The results are already rendered. The pipeline band has completed and collapsed. The query is visible in the input field.

**No replay is needed.** The user does not need to see the pipeline stages animate through their completion sequence — that moment happened while they were away, and replaying it serves no informational purpose. The labor illusion (section 2) operates in real-time; it does not benefit from replay. NN/Group's guidance on return-to-app after long waits (Kaplan, 2021) is explicit: show the **completed state directly** with contextual detail, not a replay or animation of the completion. The user was absent; replaying a progress animation wastes their time.

**The results should be immediately scannable.** The full result set (answer + source cards) should be rendered and stable when the user arrives. No "loading" state, no delayed rendering, no animation on return — the work is done and the results are waiting. This is consistent with the common pattern across desktop applications with long-running tasks (Visual Studio build, Blender render, CI/CD tools): show the result state directly on return, with a single notification on completion. Czerwinski, Horvitz, & Wilhite (CHI 2004) found that knowledge workers returning to interrupted tasks face a **resumption lag** — the time needed to reconstruct working memory about the task context. The query visible in the input field and the results immediately below it minimize this lag by providing the full context at a glance.

### Background indexing status

JustSearch indexes documents in the background. This is a system-initiated activity that the user did not explicitly request at the moment it occurs (though they configured it). The attention management challenge is different from pipeline completion: the user should be aware that indexing is happening (it affects search quality and may consume resources) without being interrupted by it.

**Peripheral communication, not notification.** Background indexing status belongs at the calm technology periphery (section 9). It should not generate OS notifications, toasts, or any attention-demanding signal during normal operation. The appropriate treatment is an ambient indicator in the input zone — a subtle visual element (small icon, dot, or text) that communicates "indexing in progress" without competing for focal attention. This is a direct application of Nielsen's first usability heuristic (visibility of system status, 1994): feedback must exist, but its modality and intensity should match the urgency. McCrickard et al. (2003, *ACM TOCHI*) classify background status indicators as low-interruption, low-reaction events — the user needs to be able to notice them but should not be forced into a context switch.

**The indicator should be:**
- **Visible but peripheral.** Near the input field, at reduced visual weight (section 12: secondary text color, small size). Noticeable if the user looks for it; not demanding if they don't.
- **Informative on hover/click.** Expanding the indicator reveals: what is being indexed, how many documents, approximate progress. This is opt-in mechanism detail — consistent with section 11's process/mechanism boundary.
- **Self-dismissing.** When indexing completes, the indicator fades away. No "Indexing complete!" notification — the absence of the indicator *is* the completion signal. This is calm technology's periphery principle in practice: state change is communicated through presence and absence, not through announcement.

**Exception: first-time indexing.** When the user has just configured their document sources and the initial indexing run begins, this is a qualitatively different event — it is the transition from "the system has nothing" to "the system has knowledge." This warrants more prominent communication: a status message in the results zone area ("Indexing your documents... Search will be available when indexing completes") that persists until the first index is ready. After the initial run, subsequent background indexing updates are peripheral.

This first-time/recurring distinction is well-grounded in the progressive disclosure principle: on first occurrence, show more context (what is happening, why, how long, whether it will recur); on subsequent occurrences, reduce to status-only because the user has built the mental model. NN/Group's onboarding research (Budiu & Whitenton, 2020) recommends contextual explanation at the moment of need rather than upfront tutorials — the first-time indexing message is exactly this: contextual help shown at the moment the user encounters the wait, not a walkthrough presented at app launch.

### Error during background activity

If the pipeline fails while the user is away: the OS notification should indicate failure, not completion. "Search could not complete: [query]" — following section 14's system-accountable language. On return, the user sees the error state from section 14 (Category 1 or Category 4, depending on the failure type).

If background indexing fails: the ambient indicator should shift to an error state (section 12: semantic color for errors, plus a non-color signal per section 16). No OS notification for indexing errors — they are not urgent and the user may not be aware indexing was happening. The error is visible on next interaction with the indicator.

---

## 20. Duration-Adaptive Model

Section 1 establishes that fast search and deep search are co-equal experiences. This section formalizes how the UI adapts to the full duration spectrum. Pipeline duration depends on the user's corpus size, available hardware, and which retrieval stages are enabled — the UI adapts to the actual duration, not a fixed assumption.

### The duration spectrum

| Corpus profile | Expected latency | Pipeline behavior |
|---------------|-----------------|-------------------|
| Small (<1K docs) | <500ms | BM25 returns near-instantly; vector retrieval fast; no reranking needed |
| Medium (1K–50K) | 500ms–5s | Hybrid retrieval measurable; reranking adds latency; expansion overlapped |
| Large (50K–500K) | 5s–30s | Multiple retrieval stages visible; reranking significant; expansion valuable |
| Very large (500K+) | 30s–60s+ | Full pipeline with all stages; query expansion and reranking both justified |

The user does not choose a "mode." The same search input triggers the same pipeline; the pipeline's duration is a function of corpus characteristics, not user configuration.

### Adaptive UI thresholds

The interaction arc from section 8 is the complete model. The duration-adaptive model determines **which arc beats manifest visually**, based on elapsed time:

| Threshold | UI behavior | Arc beats visible |
|-----------|------------|-------------------|
| <300ms | Results appear directly. No pipeline UI. | Beat 1 → Beat 6 (dormancy → consumption, skipping 3–5) |
| 300ms–2s | Compact stage indicator fades in. No skeleton slots. | Beats 3–6 (commitment through consumption) |
| >2s | Skeleton slots appear in results zone. Cancel affordance becomes visible. | Full arc: beats 3–7 |
| >10s | "Show details" sub-narration expands automatically for mechanism transparency. | Full arc with expanded detail |

**The 300ms threshold** is not arbitrary. Nielsen's response-time research establishes 100ms as the limit of "instantaneous" and 1000ms as the limit of "uninterrupted flow." At 300ms the system is perceptibly not-instant but still fast — the compact indicator provides a flash of confirmation ("search happened") without delaying content delivery.

**The skeleton slot threshold at 2s** follows from the same research: below 2s, skeleton slots would appear and disappear before the user registers them, creating visual noise rather than trust. Above 2s, the user is waiting long enough that the skeleton provides genuine "results are forming" information.

**The cancel threshold** matches the skeleton threshold because a sub-2-second operation does not warrant an escape hatch — by the time the user processes the cancel affordance, results will have arrived.

**The auto-expand at 10s** violates section 4's compact-by-default position for a specific reason: a 10+ second wait with only "Stage 2 of 5: Ranking results" visible underserves the user's need to understand what is happening. The expanded sub-narration ("scanning 47,000 documents... 2,340 candidates...") provides the activity-signaling that Maister's psychology requires for long waits. The user can collapse it back if they prefer.

### Backend prerequisite: stage-streaming search

The current search API (`POST /api/knowledge/search`) is synchronous — it returns the full response after all stages complete. To support the arc, the backend must provide a streaming search endpoint that reports stage transitions as they occur.

**What exists today:**
- Worker-side pipeline DAG definition (`SSOT/pipelines/search.v1.json`) with 11 named stages
- Head-side adapter (`KnowledgeHttpApiAdapter`) adds query expansion, reranking, expansion merge
- SSE infrastructure (`SseWriter`) is battle-tested for RAG answer streaming
- RAG answer generation already reports 3-stage progress via SSE events
- Degradation signals exist in the response (`effectiveMode`, `vectorBlocked`, `hybridFallback`, `correctionApplied`)

**What must be built:**
- A streaming search endpoint (`POST /api/knowledge/search/stream`) using SSE
- Head-level stage synthesis: collapse the Worker's internal 11-stage DAG into 4–6 user-facing stages. The user does not need to see "normalize" and "parse" as separate stages — they need "Searching," "Ranking," "Expanding," "Finalizing."
- Abort/cancel mechanism: client-side `EventSource` close triggers pipeline cancellation via `AbortController` propagation through gRPC
- Partial result delivery: when early stages produce results (BM25 hits before reranking), stream them as provisional results that the frontend can display in skeleton slots

**User-facing stage mapping** (Head-synthesized from Worker + Head stages):

| User-facing stage | What runs | Typical duration |
|-------------------|----------|-----------------|
| 1. Searching | normalize, parse, retrieve_bm25, retrieve_ann (parallel) | Dominant for large corpora |
| 2. Ranking | merge, corrections, rerank (cross-encoder) | 200ms–5s depending on candidate count |
| 3. Expanding *(conditional)* | LLM query expansion + re-search | 0–1500ms (overlapped; only visible if still pending after stage 2) |
| 4. Finalizing | filter, highlight, respond, expansion merge | <200ms |

The stage count is adaptive: if expansion is disabled or completes before stage 2 finishes (which is the common case for small corpora), stage 3 is skipped and the user sees "Stage 2 of 3" instead of "Stage 3 of 4."

### The RAG answer streaming sub-arc

When RAG synthesis is active, the interaction arc has a sub-arc within beats 4–6 that the seven-beat model does not explicitly address. The search pipeline and the answer generation pipeline are sequential but produce different kinds of visual output — one is stage-based progress, the other is streaming prose. This sub-arc specifies how they interact.

**The five-step RAG sub-arc:**

1. **Search stages run** (beat 4, Labor) — pipeline stage indicators visible, skeleton slots may appear. This is the standard interaction arc.
2. **Search stages complete** (beat 5, Resolution) — search results are ready. The pipeline stage indicator shows completion. But the answer is not ready yet — generation has not begun or has just started.
3. **Answer generation begins** — a new visual zone appears: the answer block in the results zone starts receiving streamed tokens. The pipeline band may collapse at this point (search is done, the stage progress is no longer relevant) or transition to a single "Generating answer..." indicator.
4. **Answer streams** — text appears token-by-token or sentence-by-sentence in the answer block. The user begins reading before generation is complete. Source cards may appear progressively as citations are generated. This is simultaneously "consumption" (the user is reading) and "waiting" (the answer is not complete). This dual state has no clean mapping to a single beat in the seven-beat model.
5. **Answer completes** — the full answer with all citations is visible. Source cards are populated. The user is in beat 6 (Consumption) unambiguously.

**The critical transition is step 2→3:** when search completes and answer generation begins. Two options:

**Option A: Sequential transition.** The pipeline band completes its resolution sequence (beat 5's completion pulse), collapses, and the answer zone appears fresh below the input. Answer text begins streaming into an empty answer block. The user sees a clear boundary: "search is done, now the answer is being written."

**Option B: Overlapped transition.** The pipeline band's final stage transitions from "Finalizing search" to "Generating answer..." and the answer text begins streaming below while the stage indicator remains visible. The pipeline never fully "completes" in the beat 5 sense — it transitions from search progress to generation progress.

**Position: Option A (sequential) is correct.** The beat 5 completion transition is the peak-end moment for the search experience — the pipeline should be seen to complete before the answer begins. Merging search completion with answer generation (Option B) would rob the user of the "all stages done" satisfaction that the peak-end rule predicts they will remember. The answer zone appearing fresh after the completion pulse also provides a clear visual break: "the search found things; now the system is synthesizing."

**Answer streaming rhythm.** The answer should stream at a rate that allows reading without buffering anxiety. The "Streaming, Fast and Slow" research (ACM UIST 2025, already cited in section 5) recommends adaptive pacing based on content cognitive load — complex passages stream slower, simple passages faster. For JustSearch, a minimum pace guideline: text should appear at least as fast as the user can read (~250 words/minute, ~4 words/second). If generation is faster than reading speed, buffer and pace the output. If generation is slower, stream immediately (every token matters for perceived responsiveness). Time-to-first-token should be minimized — the first few words appearing quickly is disproportionately important for perceived speed (TokenFlow, 2025).

**Citations during streaming.** Inline citation markers should appear as the text that references them is generated, not all at once after the answer is complete. The source cards below the answer can populate progressively — each source card appears when its first citation is generated in the answer text. This gives the user a growing sense of the evidence base as they read, rather than dumping all sources at the end.

**Duration-adaptive answer streaming:**

| Answer generation time | UI behavior |
|---|---|
| <1s (cached or very fast) | Answer appears complete. No streaming visible. |
| 1s–5s | Answer streams visibly. No cancel needed. |
| >5s | Answer streams with a subtle progress indicator (e.g., a thin progress line at the bottom of the answer block, or a "generating..." label). Cancel affordance active — canceling stops generation and shows the partial answer with whatever citations have been produced so far. |

**The four quadrants.** The interaction arc must work for all combinations of duration and RAG availability:

| | Fast (<2s) | Deep (>10s) |
|---|---|---|
| **RAG active** | Results + answer appear near-instantly. No pipeline chrome, no streaming visible. Beat 1 → Beat 6. | Full pipeline → completion transition → answer streams. Full arc including RAG sub-arc. |
| **No RAG** | Document-list appears instantly. No pipeline chrome. Beat 1 → Beat 6. | Full pipeline → completion transition → document-list results. Standard arc, no RAG sub-arc. |

The fast+RAG quadrant deserves a note: if both search and answer generation complete in <2s (small corpus, fast inference), the entire experience collapses to "type → answer appears." No pipeline, no streaming, no intermediate state. This is the ideal experience for small corpora and should feel as instant as a web search.

### Relationship to tempdoc 229

Tempdoc 229 (Adaptive Corpus-Aware Search) defines the policy layer that selects which retrieval features are combined for a given corpus. The duration-adaptive UI is downstream of policy selection: the policy determines which stages run, and the UI reports whatever stages the policy selected. The UI does not need to know why a stage was chosen — only that it is running, has completed, was skipped, or has failed.

---

## 21. Implementation Phases

### Visual prototype reference (retired 2026-05-13)

The companion static-HTML prototype previously at `docs/tempdocs/231-prototype/` was retired on 2026-05-13. It had been dormant ~2 months without being adopted into `modules/ui-web/`, and no other tempdoc consumed it as canonical reference. The 12 frames documented below describe the original prototype contents for historical context — the frame names + section mappings remain a useful index into this tempdoc even without the live HTML.

**Stable-state frames (the UI at rest in each beat):**

| Frame | Sections | What it shows |
|-------|----------|---------------|
| 1 — Dormancy | §8 beat 1, §15, §17 | Centered input, capability sentence, example + recent chips |
| 2 — Pipeline | §8 beats 3-4, §20 | Locked input at top, stage indicator, skeleton slots, cancel, override |
| 3 — Answer-first | §8 beat 6, §10 | Answer with inline citations, 3 source cards, override |
| 4 — Document list | §10, §14 cat 2 | 7 document cards, degradation notice, override |
| 5 — Preview panel | §10, §17 exception | Two-panel: result list + preview with highlighted passage |
| 6 — Citation overlay | §10 preview | Overlay with source document, cited passage highlighted |
| 7 — Navigational | §10, §306 | Path/filename prominent, snippet de-emphasized |
| 8 — No results | §14 cat 3 | No results message, suggestion chips |

**Intermediate-state frames (the UI during transitions):**

| Frame | Sections | What it shows |
|-------|----------|---------------|
| 9 — Mid-stream | §20 RAG sub-arc step 4 | Partial answer streaming, 2/3 source cards, generating indicator |
| 10 — Completion | §8 beat 5 | All stages completed, results emerging at reduced opacity |
| 11 — Refinement | §8 beat 7, §18 | New pipeline running, previous results dimmed at 0.4 opacity |
| 12 — Override deep | §9 override, §20 | Stage 3/4 at 12s elapsed, auto-expanded detail, prominent override button |

### Implementation phases

Implementation is organized into nine phases. Phase 0 (backend) is a prerequisite for phases 1–3. Phases 4–9 can proceed in parallel with phases 1–3 where noted.

### Phase 0: Backend — streaming search endpoint

Prerequisite for all pipeline visualization work. Can be developed independently of frontend phases.

- [ ] **0.1** Define SSE event schema for streaming search (event types: `stage`, `provisional_results`, `final_results`, `degradation`, `error`, `done`)
- [ ] **0.2** Implement `POST /api/knowledge/search/stream` endpoint using `SseWriter`
- [ ] **0.3** Synthesize user-facing stages from Worker pipeline stages + Head-side stages (query expansion, reranking)
- [ ] **0.4** Emit `stage` events at each transition with stage name, index, total count, and timestamp
- [ ] **0.5** Emit `provisional_results` after BM25/vector retrieval completes (before reranking), so frontend can fill skeleton slots with early candidates
- [ ] **0.6** Implement cancel propagation: `EventSource` close → abort in-flight gRPC call + LLM expansion future
- [ ] **0.7** Emit `degradation` events for mid-pipeline fallbacks (vector blocked, hybrid fallback, expansion timeout) with reason codes matching existing `search-and-rag-reason-codes.md`
- [ ] **0.8** Maintain backward compatibility: existing synchronous `POST /api/knowledge/search` remains unchanged

**Key files:**
- `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/SseWriter.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/routes/KnowledgeRoutes.java`
- `SSOT/pipelines/search.v1.json`

### Phase 1: Core pipeline visualization (frontend)

The compact stage indicator and duration-adaptive thresholds. This is the minimum viable implementation of sections 3, 4, 8 (beats 3–5), and 20.

**Depends on:** Phase 0 (streaming endpoint)

- [ ] **1.1** Create `PipelineStageIndicator` component: compact single-line display ("Stage 2 of 4: Ranking results") in Zone C, above the result list
- [ ] **1.2** Implement duration-adaptive threshold logic: indicator hidden for <300ms responses, fades in (100ms ease-out per §13) after 300ms elapsed
- [ ] **1.3** Stage state icons: active (animated dot or spinner), completed (checkmark), skipped (dash), failed (×) — with non-color encoding per §16
- [ ] **1.4** "Show details" expandable sub-narration within each stage (document counts, timing, retrieval strategy) — collapsed by default per §4, auto-expands after 10s per §20
- [ ] **1.5** Wire to SSE `stage` events from Phase 0; fall back gracefully to non-streaming search if streaming endpoint unavailable
- [ ] **1.6** Add `aria-live="polite"` on stage indicator container for screen reader announcements (§16)

### Phase 2: Result zone evolution (frontend)

Skeleton slots, provisional results, and the completion transition. Implements sections 5, 8 (beats 4–5), and 13.

**Depends on:** Phase 0 (provisional results), Phase 1 (stage indicator exists)

- [ ] **2.1** Create `SkeletonResultSlot` component: low-contrast placeholder shapes matching `ResultRow` dimensions. Subtle animation (slow shimmer or pulse) acceptable per §12 revised position; static fallback if in doubt.
- [ ] **2.2** Show skeleton slots when first candidate-producing stage begins AND elapsed time >2s (§20 threshold)
- [ ] **2.3** Fill skeleton slots with provisional results from `provisional_results` SSE event; crossfade at 200ms opacity per §13
- [ ] **2.4** Replace provisional results with final reranked results on `final_results` event; visual transition indicates refinement, not replacement
- [ ] **2.5** Implement completion transition (§8 beat 5): all stages mark done (50ms stagger, ~300ms total) → 200ms hold → pipeline band collapses (300ms spring, stiffness 300, damping 30) → results zone expands
- [ ] **2.6** Reduced-motion alternative for completion: instant state change, no collapse animation, results appear immediately (§13)

### Phase 3: Input zone and query lifecycle (frontend)

Cancel affordance, sticky input, and refinement chain. Implements sections 8 (beats 3, 7), 17, and 18.

**Independent of Phases 1–2 except for 3.1 (cancel requires streaming endpoint)**

- [ ] **3.1** Cancel affordance: subtle button appears alongside input during pipeline execution; clicking closes the `EventSource` and shows partial results if available (§8 beat 3)
- [ ] **3.2** Sticky input: make Zone A fixed-position during result scroll; keep viewport footprint <20% per §17 USAGov research
- [ ] **3.3** Duration-adaptive cancel visibility: cancel button hidden for <2s pipelines, fades in at 2s threshold (§20)
- [ ] **3.4** Refinement chain: when user edits query in-place and resubmits, dim previous results to 0.4 opacity (200ms ease-out) while new pipeline runs (§8 beat 7, §13)
- [ ] **3.5** Refinement chain cleanup: only the immediately previous result set persists dimmed; earlier results cleared (§18)
- [ ] **3.6** New search detection: clearing the input entirely clears all previous results (§8 beat 7 — "new search" vs "refinement" distinction)

### Phase 4: Accessibility (frontend)

ARIA live regions, focus management, and keyboard navigation for pipeline elements. Implements section 16. Can proceed in parallel with phases 1–3.

- [ ] **4.1** `aria-live="polite"` on pipeline stage indicator (stage transitions announced without interrupting current reading)
- [ ] **4.2** Search completion announcement: `role="status"` element that reads "Search complete. Showing N results." when pipeline finishes
- [ ] **4.3** Focus management at beat 5→6 transition: programmatically move focus to first result item when pipeline completes (keyboard users don't get stranded at collapsed pipeline zone)
- [ ] **4.4** Cancel affordance in tab order: tabbable and reachable immediately after input field from the moment it appears
- [ ] **4.5** Pipeline stage states distinguishable without color: verify checkmark/dash/× icons are present alongside green/amber/red coloring (§16 color independence table)
- [ ] **4.6** Skeleton slots have visible borders in high-contrast mode (low-opacity treatment invisible without transparency)

### Phase 5: Polish and edge cases (frontend)

Peak-end acceleration, stale result timeout, degradation notices. Implements sections 8, 13, 14, 18, 19.

**Depends on:** Phases 1–3 complete

- [ ] **5.1** Peak-end acceleration: final stage visual completion faster than earlier stages — either through animation timing (shorter duration on last stage transition) or by placing the fastest-resolving step last in the user-facing stage sequence (§8)
- [ ] **5.2** Stale result timeout: after configurable inactivity period, reset UI to dormancy (beat 1) with query history chips rather than showing hours-old results (§18)
- [ ] **5.3** Degradation notice for partial results: compact inline notice in results zone — "Searched as text only — AI features are offline" — styled at text-secondary weight (§14 category 2)
- [ ] **5.4** Pipeline stall treatment: after stage exceeds expected duration, indicator shifts to slower pulse + "taking longer than expected" label; cancel affordance visually promoted (§14 category 4)
- [ ] **5.5** Background pipeline completion notification: single OS notification with query text on completion when app is not focused; click returns to beat 6 with results visible (§19)
- [ ] **5.6** Degradation events during pipeline: if inference goes offline mid-pipeline, mark relevant stage as "skipped" rather than "failed" and continue with remaining stages (§14 category 2)

### Phase 6: Override mechanism (frontend)

Duration-adaptive override for misclassified queries. Implements section 9 (override mechanism subsection).

**Depends on:** Phase 1 (pipeline indicator exists for during-pipeline override placement)

- [ ] **6.1** Create `OverrideAffordance` component: text-secondary-weight link ("Find matching files" / "Ask about this instead") that re-submits the query with the opposite response format
- [ ] **6.2** Duration-adaptive visibility: hidden for <2s pipelines; appears at top of results zone for 2–10s results; appears alongside pipeline indicator for >10s during-pipeline
- [ ] **6.3** Override click behavior: for post-result override, re-submits with flipped format (answer→file-list or file-list→answer); for during-pipeline override, aborts current pipeline and re-submits
- [ ] **6.4** Override event logging: log override events with query text, original classification, and user-selected format for classifier calibration (§9)
- [ ] **6.5** `aria-live="polite"` announcement when override appears; tabbable in tab order after cancel affordance (§16)
- [ ] **6.6** Reduced-motion: override appears instantly, no fade (§13)

### Phase 7: Document preview (frontend)

Document preview for both answer-first citations and document-list scanning. Implements section 10 (document preview subsection).

**Independent of Phases 0–6.** Can begin as soon as the document-list layout exists.

- [ ] **7.1** Answer-first citation preview: overlay/slide-out panel showing source document with cited passage highlighted, triggered by citation click. Backdrop dim at 0.3 opacity. Dismiss returns focus to answer.
- [ ] **7.2** Document-list preview panel: two-panel split (result list + preview) when a result is selected. Slide-in from right at 250ms ease-out (§13). Escape dismisses.
- [ ] **7.3** Preview content rendering by file type: plain text/markdown (rendered with highlights), PDF (page-level), code (syntax-highlighted), images (thumbnail + metadata), Office docs (Tika-extracted text)
- [ ] **7.4** Matched query terms highlighted in preview content
- [ ] **7.5** "Open in app" affordance in preview panel — opens file in native application
- [ ] **7.6** Keyboard navigation: arrow keys navigate result list without updating preview; Enter/click selects and updates preview; Tab moves focus to preview panel; Escape closes preview (§10, §16)
- [ ] **7.7** Responsive split direction: left-right for wide viewports, top-bottom or overlay for narrow viewports
- [ ] **7.8** Preview panel `aria-label` with filename; focus management on open/close (§16)

### Phase 8: RAG answer streaming (frontend)

Token-by-token answer streaming with progressive citation display. Implements section 20 (RAG answer streaming sub-arc).

**Depends on:** Phase 0 (SSE infrastructure), Phase 2 (completion transition exists)

- [ ] **8.1** Answer block component: receives streamed tokens via SSE, renders incrementally. Fades in at 150ms when first token arrives (§13).
- [ ] **8.2** Sequential transition: pipeline completion pulse (Phase 2's beat 5 implementation) finishes before answer block appears. Pipeline band collapses, then answer zone begins streaming.
- [ ] **8.3** Progressive source cards: each source card fades in (150ms, §13) when its first citation appears in the streaming answer text
- [ ] **8.4** Streaming pace: if generation is faster than reading speed (~4 words/second), buffer and pace output. If slower, stream immediately.
- [ ] **8.5** Cancel during streaming: cancel affordance remains active during answer generation. Canceling stops generation and shows partial answer with whatever citations exist.
- [ ] **8.6** Duration-adaptive answer streaming: if answer generation completes in <1s (cached/fast), show complete answer with no visible streaming. If >5s, show subtle progress indicator.
- [ ] **8.7** `aria-live="polite"` on answer block; batch announcements at sentence boundaries or 2–3s intervals (§16)

### Phase 9: First-use enhancements (frontend)

Capability framing and curated example queries. Implements section 15 revisions.

**Independent of other phases.**

- [ ] **9.1** Capability framing sentence near input field in dormant state — brief tagline communicating "search by meaning, not just keywords" (§15)
- [ ] **9.2** Curated example queries: when user has indexed content, select example queries from the user's corpus that will produce strong results (anchoring bias mitigation, §15)
- [ ] **9.3** Categorized example query chips: span different capability types (keyword, natural language, file search) rather than random rotation (§15)

### Cross-cutting constraints

These apply to all phases and are not separate work items — they are acceptance criteria:

- **Reduced motion (§13, §16):** Every animation added in phases 1–9 must have a `prefers-reduced-motion` alternative that removes interpolation but preserves information.
- **Language register (§6, §11):** All pipeline stage labels use plain action language ("Ranking results by relevance"), not clinical jargon ("Executing cross-encoder reranking pass") and not anthropomorphic intent ("Looking for the most relevant documents for you").
- **Glass Engine compatibility (§12):** New components use existing design tokens from `tokens.css`. No new color primitives unless the semantic token system lacks coverage.
- **Simple/Advanced mode:** Pipeline detail level should respect the existing mode toggle — Simple mode shows only the compact indicator; Advanced mode may default to expanded sub-narration.
- **i18n:** All user-facing strings use lingui macros, consistent with existing frontend patterns.
- **Override strings (§9):** Override labels ("Find matching files" / "Ask about this instead") use plain action language consistent with §6. No technical jargon.
- **Preview panel (§10, §17):** Document-list preview panel respects the formal single-column exception — only appears in document-list mode, never in answer-first mode (where overlay is used instead).

### Dependencies

| Dependency | Phase affected | Notes |
|-----------|---------------|-------|
| Tempdoc 229 (adaptive corpus-aware search) | Phase 0 | Policy layer determines which stages run; stage-streaming must report whatever the policy selects |
| Tempdoc 200 (accessibility audit) | Phases 4, 6–8 | ARIA and focus management work overlaps; coordinate to avoid duplication |
| Tempdoc 204 (configuration UX) | Phase 5 | Stale result timeout is a configurable preference |
| Tempdoc 306 (query classification) | Phase 6 | Override flips the query classification; must integrate with existing classifier |
| `docs/reference/contracts/search-and-rag-reason-codes.md` | Phase 0 | Degradation reason codes must be consistent |
| `docs/explanation/10-ui-ux-design.md` | All phases | Canonical zone layout; must be updated if Phase 3 changes Zone A behavior or Phase 7 adds preview panel |

---

## References

- Maister, D. (1985). *The Psychology of Waiting Lines*. Columbia.
- Buell, R. & Norton, M. (2011). *The Labor Illusion: How Operational Transparency Increases Perceived Value*. Harvard Business School.
- Kahneman, D. et al. (1993). *When More Pain Is Preferred to Less: Adding a Better End*. (Peak-end rule.)
- Weiser, M. & Brown, J.S. (1995). [Designing Calm Technology.](https://people.csail.mit.edu/rudolph/Teaching/weiser.pdf) Xerox PARC.
- Case, A. (2015). *Calm Technology: Principles and Patterns for Non-Intrusive Design*. O'Reilly.
- Nielsen Norman Group. [Progress Indicators Make a Slow System Less Insufferable.](https://www.nngroup.com/articles/progress-indicators/)
- Nielsen Norman Group. [Designing for Long Waits and Interruptions.](https://www.nngroup.com/articles/designing-for-waits-and-interruptions/)
- Nielsen Norman Group. [Progressive Disclosure.](https://www.nngroup.com/articles/progressive-disclosure/)
- Nielsen Norman Group. [Ten Usability Heuristics.](https://www.nngroup.com/articles/ten-usability-heuristics/)
- Nielsen Norman Group. [Six Types of Conversations with Generative AI.](https://www.nngroup.com/articles/AI-conversation-types/)
- Nielsen Norman Group. [How AI Is Changing Search Behaviors.](https://www.nngroup.com/articles/ai-changing-search-behaviors/)
- *Scientific Reports* (2022). [Malleability of time through progress bars and throbbers.](https://www.nature.com/articles/s41598-022-14649-1)
- Laws of UX. [Peak-End Rule.](https://lawsofux.com/peak-end-rule/)
- Penn State (2021). [Need for AI Transparency Depends on User Expectation.](https://www.psu.edu/news/research/story/explain-or-not-need-ai-transparency-depends-user-expectation)
- LLM QoE (2024). [Defining and Enhancing QoE in LLM-Based Text Streaming Services.](https://llm-qoe.github.io/)
- arxiv (2025). [Streaming, Fast and Slow.](https://arxiv.org/html/2504.17999v1)
- *Information Systems Research* (2013). [Anchoring Effects in Recommender Systems.](https://pubsonline.informs.org/doi/10.1287/isre.2013.0497)
- *Information Processing and Management* (2022). [Primacy-Peak-Recency Effect-Based Satisfaction Prediction.](https://www.sciencedirect.com/science/article/pii/S0306457322002977)
- PMC (2007). [Cognitive Biases in Health Information Search.](https://pmc.ncbi.nlm.nih.gov/articles/PMC2605604/)
- *Nature Humanities and Social Sciences* (2025). [Human-like Social Cues Meta-Analysis.](https://www.nature.com/articles/s41599-025-05618-w)
- PNAS (2022). [Benefits and Dangers of Anthropomorphic Conversational Agents.](https://www.pnas.org/doi/10.1073/pnas.2415898122)
- *Frontiers in Psychology* (2022). [Chatbot Service Recovery With Emotion Words.](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2022.922503/full)
- *Frontiers in Computer Science* (2025). [Effect of Anthropomorphism and Perceived Intelligence in Chatbot Avatars.](https://www.frontiersin.org/journals/computer-science/articles/10.3389/fcomp.2025.1531976/full)
- Appleton, M. (2023). [Command K Bars.](https://maggieappleton.com/command-bar)
- Destiner (2024). [Designing a Command Palette.](https://destiner.io/blog/post/designing-a-command-palette/)
- Tunkelang, D. [Affordances for Conversational Search.](https://dtunkelang.medium.com/affordances-for-conversational-search-2cc543eae83d)
- arxiv (2025). [Understanding Mental Models of Generative Conversational Search.](https://arxiv.org/html/2506.03807v1)
- UXmatters (2024). [Conversational AI Search Engines: Implications for Usability.](https://www.uxmatters.com/mt/archives/2024/03/conversational-ai-search-engines-implications-for-usability-and-the-user-experience.php)
- PAIR Guide. [Mental Models.](https://pair.withgoogle.com/chapter/mental-models/)
- arXiv (2024). [Search Engines in an AI Era: The False Promise of Factual and Verifiable Source-Cited Responses.](https://arxiv.org/html/2410.22349v1)
- arXiv (2024). [Trust Me on This: A User Study of Trustworthiness for RAG Responses.](https://arxiv.org/abs/2601.14460)
- FAccT (2024). ["I'm Not Sure, But...": Examining the Impact of LLMs' Uncertainty Expression on User Reliance and Trust.](https://arxiv.org/abs/2405.00623)
- arXiv (2024). [Understanding the Effects of Miscalibrated AI Confidence.](https://arxiv.org/abs/2402.07632)
- CHI 2025. [Exploring Trust and Transparency in RAG for Domain Experts.](https://dl.acm.org/doi/10.1145/3706599.3719985)
- *Frontiers in Computer Science* (2025). [Trusting AI: Does Uncertainty Visualization Affect Decision-Making?](https://www.frontiersin.org/journals/computer-science/articles/10.3389/fcomp.2025.1464348/full)
- Springer (2025). [Exploring Automation Bias in Human-AI Collaboration.](https://link.springer.com/article/10.1007/s00146-025-02422-7)
- MDPI *Information* (2020). [Eye-Tracking Studies of Web Search Engines: Systematic Literature Review.](https://www.mdpi.com/2078-2489/11/6/300)
- ShapeofAI. [AI UX Patterns: Citations.](https://www.shapeof.ai/patterns/citations)
- Alberts, W. & van der Geest, T. (2011). *Color Matters: Color as Trustworthiness Cue in Web Sites*. ResearchGate.
- Su, L. & Cui, A.P. (2019). *Trustworthy Blue or Untrustworthy Red*. Journal of Marketing Theory and Practice.
- Nielsen Norman Group. [Dark Mode.](https://www.nngroup.com/articles/dark-mode/)
- Nielsen Norman Group. [The Role of Animation and Motion in UX.](https://www.nngroup.com/articles/animation-purpose-ux/)
- Nielsen Norman Group. [Executing UX Animations: Duration and Motion Characteristics.](https://www.nngroup.com/articles/animation-duration/)
- Nielsen Norman Group. [Error-Message Guidelines.](https://www.nngroup.com/articles/error-message-guidelines/)
- Nielsen Norman Group. [Error Messages Scoring Rubric.](https://www.nngroup.com/articles/error-messages-scoring-rubric/)
- Nielsen Norman Group. [Hostile Patterns in Error Messages.](https://www.nngroup.com/articles/hostile-error-messages/)
- Nielsen Norman Group. [Designing Empty States in Complex Applications.](https://www.nngroup.com/articles/empty-state-interface-design/)
- Nielsen Norman Group. [Onboarding Tutorials vs. Contextual Help.](https://www.nngroup.com/articles/onboarding-tutorials/)
- Nielsen Norman Group. [Typography for Glanceable Reading.](https://www.nngroup.com/articles/glanceable-fonts/)
- Apple. [Human Interface Guidelines: Motion.](https://developer.apple.com/design/human-interface-guidelines/motion)
- W3C. [Understanding WCAG 2.1 SC 2.3.3: Animation from Interactions.](https://www.w3.org/WAI/WCAG21/Understanding/animation-from-interactions.html)
- Willenskomer, I. [UX in Motion Manifesto.](https://medium.com/ux-in-motion/creating-usability-with-motion-the-ux-in-motion-manifesto-a87a4584ddc)
- Smashing Magazine (2019). [Better Search UX Through Microcopy.](https://www.smashingmagazine.com/2019/06/better-search-ux-microcopy/)
- UX Bulletin. [UX for Degradation: Fail Gracefully.](https://www.ux-bulletin.com/ux-for-degradation-graceful-failure-design/)
- Namatame, M. & Kitajima, M. (2024). *Optimizing Reading Experience: An Eye Tracking Comparative Analysis of Single-Column, Two-Column, and Three-Column Formats.* HCI International 2024 / HIMI (Springer LNCS).
- Dyson, M.C. & Haselgrove, M. (2001). *The Influence of Reading Speed and Line Length on the Effectiveness of Reading from Screen.* International Journal of Human-Computer Studies, 54(4).
- Bringhurst, R. (1992/2004). *The Elements of Typographic Style.* Hartley & Marks.
- Shaikh, A.D. & Chaparro, B.S. (2005). *The Effects of Line Length on Reading Online News.* Usability News, 7(2).
- Nielsen Norman Group. [Horizontal Attention Leans Left.](https://www.nngroup.com/articles/horizontal-attention-leans-left/)
- Nielsen Norman Group. [Spatial Memory: Why It Matters for UX Design.](https://www.nngroup.com/articles/spatial-memory/)
- Scarr, J., Cockburn, A., Gutwin, C., & Malacria, S. (2013). *Testing the Robustness and Performance of Spatially Consistent Interfaces.* CHI '13, ACM.
- Scarr, J., Cockburn, A., Gutwin, C., & Bunt, A. (2012). *Improving Command Selection with CommandMaps.* CHI '12, ACM.
- Denney, H. (2012). Sticky navigation usability study. Reported in [Smashing Magazine](https://www.smashingmagazine.com/2012/09/sticky-menus-are-quicker-to-navigate/).
- USAGov (2023). [Researching and Implementing Sticky Navigation.](https://blog.usa.gov/researching-and-implementing-sticky-navigation-in-usagov)
- Nielsen, J. (2006). [F-Shaped Pattern for Reading Web Content.](https://www.nngroup.com/articles/f-shaped-pattern-reading-web-content-discovered/) Nielsen Norman Group.
- Pernice, K. & Nielsen, J. (2020). [F-Shaped Pattern of Reading on the Web: Misunderstood, But Still Relevant.](https://www.nngroup.com/articles/f-shaped-pattern-reading-web-content/) Nielsen Norman Group.
- Material Design 3. [Applying Layout.](https://m3.material.io/foundations/layout/applying-layout)
- U.S. Web Design System. [Typography.](https://designsystem.digital.gov/components/typography/)
- Silverstein, C., Henzinger, M., Marais, H., & Moricz, M. (1999). *Analysis of a Very Large Web Search Engine Query Log.* SIGIR Forum, 33(1).
- Jansen, B.J. & Spink, A. (2006). *How Are We Searching the World Wide Web?* Information Processing & Management, 42(1).
- Jansen, B.J., Spink, A., Blakely, C., & Koshman, S. (2007). *Defining a Session on Web Search Engines.* JASIST, 58(6).
- Spink, A., Ozmutlu, H.C., & Ozmutlu, S. (2002). *Multitasking During Web Search Sessions.* Information Processing & Management, 41(2).
- Huang, J. & Efthimiadis, E.N. (2009). *Analyzing and Evaluating Query Reformulation Strategies in Web Search Logs.* CIKM 2009.
- Jones, R. & Klinkner, K.L. (2008). *Beyond the Session Timeout: Automatic Hierarchical Segmentation of Search Topics in Query Logs.* CIKM 2008.
- Hoffer, L. et al. (2025). *A New Era of Online Search? A Large-Scale Study of User Behavior with Generative AI versus Traditional Search Engines.* CHI 2025 Extended Abstracts.
- Teevan, J., Adar, E., Jones, R., & Potts, M. (2007). [Information Re-Retrieval: Repeat Queries in Yahoo's Logs.](https://dl.acm.org/doi/10.1145/1277741.1277770) SIGIR 2007.
- Schlögl, S., Kern, R., & Granitzer, M. (2017/2020). *QueryCrumbs: A Compact Visualization for Navigating the Search Query History.* IEEE VIS 2017; Visual Informatics, 4(1), 2020.
- Morris, D., Morris, M.R., & Venolia, G. (2008). [SearchBar: A Search-Centric Web History for Task Resumption and Information Re-finding.](https://dl.acm.org/doi/10.1145/1357054.1357242) CHI 2008.
- Hearst, M.A. (2009). [*Search User Interfaces.*](https://searchuserinterfaces.com/book/) Cambridge University Press.
- Marchionini, G. (2006). [Exploratory Search: From Finding to Understanding.](https://dl.acm.org/doi/10.1145/1121949.1121979) Communications of the ACM, 49(4).
- Wu, D., Dong, J., Yuan, K., & Cheng, X. (2020). *Understanding Task Preparation and Resumption Behaviors in Cross-Device Search.* JASIST, 71(8).
- Hoeber, O. et al. (2026). *Search Timelines: Visualizing Search History to Enable Cross-Session Exploratory Search.* International Journal on Digital Libraries.
- Bailey, B.P. & Konstan, J.A. (2006). *On the Need for Attention-Aware Systems: Measuring Effects of Interruption on Task Performance, Error Rate, and Affective State.* Computers in Human Behavior, 22(4).
- Iqbal, S.T. & Horvitz, E. (2007). [Disruption and Recovery of Computing Tasks: Field Study, Analysis, and Directions.](https://dl.acm.org/doi/10.1145/1240624.1240730) CHI 2007.
- McCrickard, D.S., Chewar, C.M., Somervell, J.P., & Ndiwalana, A. (2003). *A Model for Notification Systems Evaluation.* ACM Transactions on Computer-Human Interaction, 10(4).
- Czerwinski, M., Horvitz, E., & Wilhite, S. (2004). [A Diary Study of Task Switching and Interruptions.](https://dl.acm.org/doi/10.1145/985692.985715) CHI 2004.
- Pielot, M., Church, K., & de Oliveira, R. (2014). *An In-Situ Study of Mobile Phone Notifications.* MobileHCI 2014.
- Sahami Shirazi, A. et al. (2014). *Large-Scale Assessment of Mobile Notifications.* CHI 2014.
- Ancker, J.S. et al. (2017). *Effects of Workload, Work Complexity, and Repeated Alerts on Alert Fatigue.* BMC Medical Informatics and Decision Making, 17(1).
- Microsoft. [Notifications Design Basics.](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/toast-ux-guidance)
- Apple. [Human Interface Guidelines: Notifications.](https://developer.apple.com/design/human-interface-guidelines/notifications)
- Sweller, J. (1988). *Cognitive Load During Problem Solving: Effects on Learning.* Cognitive Science, 12(2).
- Econsultancy/GfK. *Digital Trends Survey: Search Behavior.* (Cited via NN/Group empty state and search behavior research.)

### References added 2026-03-16 (research tracks 1–8)

- Li, Z. & Aral, S. (2025). [Human Trust in GenAI-Powered Search.](https://arxiv.org/abs/2504.06435) arXiv:2504.06435. (4,927 participants; citations increase trust even when incorrect; 27% citation click rate.)
- Pew Research Center (2025). [Do People Click AI Citations?](https://www.pewresearch.org/short-reads/2025/07/22/google-users-are-less-likely-to-click-on-links-when-an-ai-summary-appears-in-the-results/) (1% click rate on AI-cited sources; 900 adults browsing data.)
- Microsoft Research (2025). [LLMs Get Lost In Multi-Turn Conversation.](https://arxiv.org/abs/2505.06120) arXiv:2505.06120. (39% performance drop multi-turn vs single-turn; 15 models, 200K+ conversations.)
- arXiv (2026). [Quantifying Conversational Reliability.](https://arxiv.org/abs/2603.01423) arXiv:2603.01423. (Instruction drift, intent confusion, contextual overwriting as multi-turn failure modes.)
- Amazon Science (2023). [Context-Aware Query Rewriting for Improving Users' Search Experience.](https://www.amazon.science/publications/context-aware-query-rewriting-for-improving-users-search-experience-on-e-commerce-websites) ACL 2023. (11.6% MRR improvement via session graph query rewriting.)
- Nourani, M. et al. (2021). *The Role of Explanation Timing in Enabling Mental Model Formation.* IUI 2021, ACM. (Anchoring bias: early system strengths → better mental models; early errors → lasting negative impressions.)
- Nielsen Norman Group (2026). [GenAI for Complex Questions, Search for Critical Facts.](https://www.nngroup.com/articles/ai-search-infoseeking/) (Diary study: 6/9 participants went back and forth between AI and search.)
- Nielsen Norman Group (2025). [Google AI Mode — Poor Usability.](https://www.nngroup.com/articles/google-ai-mode/) (4/7 participants had never noticed AI Mode.)
- Nielsen Norman Group (2025). [New Users Need Support with Generative-AI Tools.](https://www.nngroup.com/articles/new-AI-users-onboarding/) (First-time users "struggled to understand the tools' functionality.")
- TechCrunch (2025). [ChatGPT's Model Picker Is Back, and It's Complicated.](https://techcrunch.com/2025/08/12/chatgpts-model-picker-is-back-and-its-complicated/) (OpenAI reversed GPT-5's no-model-picker approach within days.)
- LangChain (2025). [Breakout Agents Case Study: Perplexity Pro Search.](https://www.langchain.com/breakoutagents/perplexity) (Progress visibility correlates with user patience.)
- AllAboutAI (2026). [AI Search Engines Report.](https://www.allaboutai.com/resources/ai-statistics/ai-search-engines/) (65.9% say citations boost trust; only 27% click them.)
- Chung, B. (2020). [Everything You Need to Know About Skeleton Screens.](https://uxdesign.cc/what-you-should-know-about-skeleton-screens-a820c45a571a) UX Collective. (65% perceived shimmer as shorter duration than pulse.)
- Viget (2017). [A Bone to Pick with Skeleton Screens.](https://www.viget.com/articles/a-bone-to-pick-with-skeleton-screens) (136 users: skeletons perceived as slower than spinners.)
- Soudani, H. et al. (2025). *Why Uncertainty Estimation Methods Fall Short in RAG.* ACL Findings 2025. (Existing UE methods "cannot reliably estimate correctness" in RAG.)
- ACL 2025 Workshop. *Citation Drift in Multi-Turn Conversations.* (Citations mutate/fabricate across turns; up to 85.6% fabrication by turn 3.)
- CHI 2025. [A New Era of Online Search?](https://dl.acm.org/doi/10.1145/3706599.3720123) (N=1,526; ChatGPT faster but users preferred Google subjectively.)
- Google PAIR. [Mental Models.](https://pair.withgoogle.com/chapter/mental-models/) ("Be up-front about what your product can and can't do.")
- Shape of AI. [Stream of Thought Pattern.](https://www.shapeof.ai/patterns/stream-of-thought) (Making AI reasoning visible; state per step.)
- Shape of AI. [References Pattern.](https://www.shapeof.ai/patterns/references) (Source display conventions across AI products.)
- Ahrefs (2025). [AI Overview Triggers.](https://ahrefs.com/blog/ai-overview-triggers/) (146M SERPs: 99% of AI Overviews on informational queries.)
- UserGuiding (2026). [How Top AI Tools Onboard New Users.](https://userguiding.com/blog/how-top-ai-tools-onboard-new-users) (Only 4/10 AI tools still use traditional tutorials.)
- Algolia. [Animated Placeholders.](https://www.algolia.com/doc/guides/solutions/ecommerce/search/autocomplete/animated-placeholder) (Typing-effect placeholders increase search box usage.)
- Nielsen (1993). [Response Time Limits: 3 Important Limits.](https://www.nngroup.com/articles/response-times-3-important-limits/) (100ms instant, 1s flow, 10s attention limit. Validates 2s and 10s thresholds.)
