# 866 — Should the delegate agent be able to read a file?

```
status:  SUPERSEDED by 868 (2026-08-26, PR #566) — the owner decided in 868's frame; the read
         tool shipped as `core.read-document` (ranged, over FetchDocumentSlice) with the
         acquisition axis; §4's questions are settled in 868 §B/§C. Kept as dated history.
         Was: OWNER-DECISION-GATED (2026-08-25) — charter sketch only. NO design and NO
         implementation this wave. Adding a tool to the agent's vocabulary is a
         product-capability call the owner makes, not an agent-initiated one. This
         document exists to state the problem precisely and to park the questions
         so the decision can be made on evidence rather than re-derived later.
created: 2026-08-25
owner:   unassigned — awaiting owner decision
split-from: 865 §4.9 (the evidence charter, which found this gap while surveying
         the tool surface and deliberately declined to fold it in)
related: 859 §5 C-deep + §7 (the motivating incident), 865 (the acquisition axis
         this would plug into), 770 (the MCP-facing tool surface — a DIFFERENT
         surface; see §5)
```

## 1. The problem, in one sentence

The delegate agent has no way to read a file, so a reader who asks it to
*"read these three files and summarise them"* gets a refusal or a guess, and there is
no evidence trail either way.

## 2. The motivating incident (owner's own task, recorded in 859)

859 was chartered from a live session in which the owner asked the delegate to read
three named files and summarise them. Two things went wrong, and only one of them
was understood at the time:

- **What was noticed** (859 §7): the initial 3840-token budget was exhausted after
  two tool calls, and the decline path produced a confidently-formatted, content-free
  answer — *"I don't have access…"*. This was logged as a budget-honesty defect and
  routed to 859 D.
- **What was not noticed until 865's survey**: *"I don't have access"* was very
  likely **literally true**. The agent's entire core tool surface is six operations
  (`AgentToolsOperationCatalog.java:100-107`) — `search-index`, `browse-folders`,
  `ingest-files`, `file-operations`, `remember`, `navigate-to-surface` — and none of
  them returns the contents of a named file. `modules/app-agent/src/main/java/io/justsearch/agent/tools/`
  contains `SearchTool`, `BrowseTool`, `IngestTool`, `FileOperationsTool` and no
  read tool.

That is worth dwelling on, because it is the reason this document exists as its own
charter rather than a line item. A capability gap presented as a budget defect, and
was triaged as one for five days. The model's refusal was accurate and the system
around it treated the refusal as a formatting problem.

**Related honesty question, and it may be the cheaper half of this charter:** if the
agent genuinely cannot do a thing, the refusal should say *which* thing and why —
"I have no tool that reads a file; I can search inside it" — rather than a bare
"I don't have access", which reads as a permissions problem and sends the reader
looking for a setting that does not exist. That fix needs no new tool at all.

## 3. What the reader can get today, and how far short it falls

Not nothing, which is why this is a judgement call rather than an obvious yes:

- `core.search-index` returns **excerpts** with `path`, `title`, `startLine`/`endLine`
  and (usually) `parentDocId`+`chunkIndex`. It accepts a scoping argument, so
  "search within these files" is expressible.
- `core.browse-folders` returns listings — names, sizes, paths. No content.

So *"what do these three files say about X?"* is partly servable today through
scoped search over excerpts. *"Summarise this file"* is not: excerpts are selected
by relevance to a query, and a summary has no query. The gap is real, but it is
narrower than "the agent cannot see files at all," and its true size is an empirical
question (§6, Q1) that has not been measured.

## 4. Capability-surface questions (the actual decision)

1. **Scope of readable paths.** Anything on disk? Only indexed documents? Only
   inside a watched root? `AgentToolPaths` already exists as a path-policy seam —
   whether it is the right authority for a read tool is open.
2. **Confirmation.** Where does a read sit in the `RiskTier` × `ConfirmStrategy`
   lattice? A silent read of any local file is a materially different product than
   a confirmed one, and the answer likely differs for indexed vs. arbitrary paths.
3. **Size discipline.** Whole files are far larger than search excerpts.
   `AgentContextCompressor.truncate` caps a tool result at `MAX_TOOL_RESULT_CHARS`
   (`AgentContextCompressor.java:32,51-57`), so a naive read tool would mostly
   deliver file *prefixes*. Options — a byte/line range parameter, a summarise-on-read
   step, chunked paging — are all product decisions with different failure modes.
4. **Budget interaction.** 859 D's effort-mapped budgets are calibrated against
   today's tool-result sizes. A read tool invalidates that calibration; the gate
   would need re-tuning in the same wave, not after.
5. **Binary and non-text files.** What happens on a PDF, an image, a 2 GB log? The
   index already has extracted text for indexed documents, which may be a better
   source than the file itself — and would make "read" mean *read the indexed
   representation*, not *read the bytes*. That is a genuinely different tool with
   different honesty properties.
6. **Does it return content, or a rendering?** If it returns the indexed extraction,
   the reader may be shown a summary of something subtly unlike the file on disk.
   Whichever is chosen must be **stated on the surface**, not left implicit.

## 5. Not to be confused with the MCP-facing surface (770)

Two different surfaces share the word "tool" and must not be conflated:

- **This charter** is about a tool JustSearch offers to **its own in-app delegate
  agent**, running on the local llama-server.
- **770** is about the tools JustSearch offers **outward over MCP** to external
  agents (e.g. a coding agent using JustSearch as a search backend). 770 withdrew a
  `fetch` affordance from that surface after measurement.

A decision on one does not settle the other. 770's withdrawal of `fetch` is *not*
precedent for refusing a read tool here — different consumer, different economics,
different failure modes — but its central measured lesson (that a surface's text
tier was never delivered to the model at all) is a standing warning that
tool-surface intuitions should be measured before they are built.

Separately: an external MCP server the user connects **can already** give the
delegate a read tool. `McpToolProjection.toOperation`
(`modules/app-services/src/main/java/io/justsearch/app/services/mcphost/McpToolProjection.java:66-97`)
projects any advertised tool into an `Audience.AGENT` operation with the server's own
schema verbatim. So the question is not *whether the agent can ever read a file* —
it is whether JustSearch ships that capability itself, with an identity contract it
controls.

## 6. What to measure before deciding (all cheap, none done)

1. **How far does scoped search actually get?** Run the owner's real task against
   scoped `search-index` and judge the summary. If excerpts suffice for the common
   case, this charter shrinks to a refusal-honesty fix (§2) and a budget note.
2. **How often does the pattern occur?** "Read/summarise this named file" vs.
   "find something about X" in real delegate usage.
3. **What does a whole file cost?** Median indexed-document size against the
   effort-mapped budgets — the answer may make §4.3 the deciding constraint rather
   than a detail.

## 7. Relation to 865 (the evidence charter)

865 does **not** depend on this decision landing, and deliberately so.

What 865 builds that this would plug into:

- **A declared evidence contribution on the `Operation`** (865 §4.7) instead of the
  mint site hardcoding one tool's `searchResults` key
  (`AgentSession.java:265-267`). A read tool would declare `document-level` identity
  and mint sources without touching the mint site.
- **The acquisition axis** (865 §4.8) — the vocabulary that lets a source say it was
  *opened by name* rather than *retrieved by a scorer*. Today every label opens with
  the word "Retrieved" (`evidenceProjection.ts:698-704`), which would be a false
  provenance claim on a read-source.

And the constraint 865 imposes on this charter, which is the reason for the ordering:

> **An opened-by-name document has LESS relevance evidence than a retrieved one, not
> more.** Nothing scored it. That the agent chose to read it is evidence the document
> was *available*, not that any sentence came from it. A read tool that ships before
> the acquisition axis will be tempted to display its sources in the retrieval idiom
> — which is the cosine-panel error (847) in a new costume: a verification-sounding
> label over a fact no scorer produced.

Recommended ordering if this is chartered at all: **865 first**, then this. Building
it the other way round manufactures the exact honesty defect 865 exists to remove,
and 865's §4.6 work (retrieved-vs-received under per-iteration compression) is what
keeps a truncated file-prefix from being presented as the whole document.

## 8. What this document is not

Not a design, not a recommendation to build, and not a recommendation to decline.
865's survey found the gap while looking at something else and had a duty to record
it precisely rather than let it be re-discovered as a budget defect a second time.
The decision is the owner's.
