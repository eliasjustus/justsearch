# JustSearch

**A private retrieval backend for your AI agents — and a neural search engine with a cited, on-device AI assistant for your own files. Cloud-grade hybrid search (BM25 + dense vectors + learned-sparse + reranking) plus grounded Q&A, summarization, and extraction over your documents — 100% on your machine, in any language.**


[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
<!-- badges: <<build status>> · <<latest release>> · <<nDCG benchmark badge>> -->

JustSearch indexes your local documents — PDF, email, Office, and hundreds of formats — and answers questions
over them with cited passages, **without anything leaving your machine.** It combines three retrieval paradigms
(keyword, dense-vector, learned-sparse) with a cross-encoder reranker, and exposes that retrieval over the
**Model Context Protocol (MCP)** so any AI agent — local or cloud — can use it as a **private retrieval
backend**: your files stay on your device; only the model's answer leaves your agent.

## Two ways to use it

**As a private MCP retrieval backend for agents** *(the fast path for developers)*
JustSearch exposes its retrieval over MCP at `POST /mcp` — **in-process Streamable HTTP** on the loopback API
(no separate process, no Node.js). The port is shown in the app (or `GET /api/health`). Connect your agent:

- **Claude Code:** `claude mcp add justsearch --transport http http://127.0.0.1:<PORT>/mcp`
- **Cursor / VS Code** (clients that accept an HTTP `url` directly — e.g. `.cursor/mcp.json`):
  ```json
  { "mcpServers": { "justsearch": { "url": "http://127.0.0.1:<PORT>/mcp" } } }
  ```
- **Claude Desktop** — add it as a **Connector** (Settings → Connectors → Add custom connector → the URL above),
  or bridge stdio→HTTP with `mcp-remote` in `claude_desktop_config.json`:
  ```json
  { "mcpServers": { "justsearch": { "command": "npx", "args": ["mcp-remote", "http://127.0.0.1:<PORT>/mcp"] } } }
  ```

Five tools: `justsearch_answer` (RAG, primary), `justsearch_search`, `justsearch_browse`, `justsearch_ingest`,
`justsearch_status`. Your documents never leave the machine — only the agent's answer does.

A reproducible agent-utility benchmark — how much JustSearch's retrieval tool improves an agent's task
performance versus generic file tools — *is in development; reproducible figures will be published with the
project's benchmark methodology* (no bare percentage ships until it clears that bar).

**As a desktop app** *(for non-developers)*
Download the installer, point it at a folder, and search. → **https://github.com/eliasjustus/justsearch-releases**
*(Windows; alpha; currently unsigned — see [Status](#status).)*

## Why JustSearch

Local file search is either **fast-but-literal** (Everything, Recoll — private, but vocabulary-bound) or
**smart-but-cloud** (NotebookLM, Copilot — capable, but your files leave your machine). JustSearch is the
missing cell: **semantic *and* fully offline.** No surveyed alternative occupies
**{true hybrid retrieval × fully offline × multilingual × OCR}** at once.

- **Hybrid retrieval, not single-model RAG** — BM25 + dense vectors + SPLADE learned-sparse, fused and reranked
  by a cross-encoder. Most local RAG tools use one embedding model and basic chunking.
- **Fully offline & provable** — the UI is hard-locked to `127.0.0.1` by CSP; there is no telemetry exporter.
  The only outbound call is a one-time model download. ([How to verify](#privacy).)
- **Multilingual by construction** — one locale-invariant pipeline (ICU + a multilingual model stack), no
  per-language tuning. Competitive nDCG on German and French, not just English.
- **Vision OCR** — extracts text from scanned PDFs and images, so they're searchable too.
- **BYO-LLM** — runs your own local model via llama.cpp; no API keys, no per-token cost.

## Benchmarks

Retrieval quality (nDCG@10) from one reproducible release run (`scripts/jseval/release.v1.json` — commit
`691d5c5a0`, RTX 4070, ~300 queries/corpus). Numbers are the **default `hybrid` config** unless noted:

| Corpus | nDCG@10 | Note |
|---|---|---|
| BEIR / SciFact | **0.751** | in the range of published single-model retrievers (ColBERTv2 0.693, SPLADE++ 0.71) — but read this as *system vs. component*: ours is a full hybrid+rerank pipeline, theirs are single models |
| Enron-QA | 0.740 | |
| MIRACL-de (German) | 0.725 | multilingual — no per-language tuning |
| MIRACL-fr (French) | 0.701 | |
| CourtListener (legal) | **0.97** (`full`) | hybrid default ≈0.62; legal benefits from `full` mode (all retrievers + rerank) |

External-baseline figures are cited from published papers (SIGIR/NAACL; sources + split caveats in
`release.v1.json`) — **not** re-run by us, and not directly apples-to-apples: a hybrid+rerank *system* is
expected to exceed single-model *components*, and MIRACL baselines are a different (dev) split. The honest
reading is "a reproducible offline hybrid system lands in the range of strong published retrievers," not
"we beat them" — full comparison-class notes in
[the methodology](docs/reference/benchmarks/methodology.md#how-to-read-the-comparison-system-vs-component).
Per-corpus nDCG@10 floors are projected from this release and regression-gated in CI.

Reproduce (from `scripts/jseval`): `python -m jseval run --start-backend --dataset beir/scifact --modes hybrid`
then `python -m jseval relevance-gate --dataset beir/scifact`. Slugs: `beir/scifact`, `mixed/enron-qa`,
`mixed/courtlistener-200`, `mixed/miracl-de-2k`, `mixed/miracl-fr-2k`.
Full methodology, comparison-class caveats, and reproduction:
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md). The table above is
projected from `scripts/jseval/release.v1.json` (the canonical 2026-06-21 release), not hand-transcribed.

## Quickstart (build from source)

> Prereqs to **build + test**: **JDK 25** (the Gradle toolchain auto-resolves it) and **Node.js** (for the
> `modules/ui-web` frontend) — nothing else. Building/contributing needs **no GPU, no Rust toolchain, and no model
> download**; the ~9 GB models below are runtime-only (fetched on first *run* of the app).

```bash
git clone https://github.com/eliasjustus/justsearch && cd justsearch
./gradlew.bat build              # build (Windows; use ./gradlew on *nix once cross-platform)
```

To **run** the full desktop app, the easy path is the [installer](#two-ways-to-use-it) above; running the
three-process stack from source is a developer workflow — see [`CONTRIBUTING.md`](CONTRIBUTING.md). First run
downloads the models once (**~9 GB** — the ~5.9 GB local chat model dominates; fetched from GitHub Releases +
HuggingFace), then runs fully offline.

## Architecture

Three local processes, isolated for reliability and so the UI **never touches the index**:

- **Head** — the Tauri desktop shell + a loopback-only API gateway (Lit/web-components frontend).
- **Worker** — owns the Lucene index + the retrieval pipeline (BM25/dense/SPLADE/rerank) + OCR.
- **Inference** — a local `llama-server` for chat/RAG.

They talk over gRPC on `127.0.0.1`. More: [`docs/explanation/`](docs/explanation/).

## Privacy

Nothing leaves your machine, and you can check:
- The webview's Content-Security-Policy pins network access to `127.0.0.1` — it *cannot* reach the public internet.
- No analytics/telemetry exporter exists in the code.
- The only outbound request is the one-time model download (from GitHub Releases + HuggingFace); after that, run
  a network monitor and watch it stay silent. Threat model: [`docs/reference/security/threat-model.md`](docs/reference/security/threat-model.md).

## Status

**Alpha** (`2.0.0-alpha.27`), **Windows-only** (macOS/Linux are not in the current scope). The installer is currently **unsigned**,
so Windows SmartScreen shows an "unknown publisher" warning on first run — signing is in progress. In active
development since 2025; published 2026. Built in the open with heavy AI-agent assistance — the development
tooling, the governance/discipline gates, and the design history (`docs/tempdocs/`) all live in this repo, and
commits are co-authored.

## Contributing

Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) (DCO sign-off; no CLA). Please read
[`NON-GOALS.md`](NON-GOALS.md) first so a change fits the project's scope. Security: [`SECURITY.md`](SECURITY.md).
You **don't** need any of the agent/governance machinery to contribute — it's published as transparency
([`MAINTAINING.md`](MAINTAINING.md)), not a required path.

## License

[Apache-2.0](LICENSE). Bundled model and dependency licenses: [`NOTICE`](NOTICE) / [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).
</content>
