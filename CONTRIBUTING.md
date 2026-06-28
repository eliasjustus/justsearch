# Contributing to JustSearch

Thank you for your interest in contributing to JustSearch! This document provides guidelines and instructions for contributing.

> **The short version.** Clone the repo, run `./gradlew.bat build`, run the tests, pick a good-first-issue,
> open a PR, and sign the CLA once when the bot prompts you on your first PR. That's it. You do **not**
> need Claude Code, the agent hooks, the dev-stack
> tooling, or the governance/discipline gates to contribute — those are *how the maintainer develops*,
> published as transparency, not as a required contributor path. Adopt them if you like them; ignore them and
> just send a PR. Curious how the maintainer develops? See [`MAINTAINING.md`](MAINTAINING.md).

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

Use the [bug report template](https://github.com/eliasjustus/JustSearch/issues/new?template=bug_report.md) to report issues. Include:
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java version, GPU if applicable)
- Relevant logs from `%LOCALAPPDATA%\JustSearch\logs\`

### Suggesting Features

Use the [feature request template](https://github.com/eliasjustus/JustSearch/issues/new?template=feature_request.md) to propose new features. Describe:
- The problem you're trying to solve
- Your proposed solution
- Alternatives you've considered

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Make your changes
4. Verify locally: `./gradlew.bat build` (compile + tests), and for frontend changes `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
5. Commit your changes
6. Push and open a pull request

## Development Setup

### Prerequisites

**To build + test the code** (the contributor front door — this is all you need to send a PR):

- Windows 10/11
- A JDK to bootstrap Gradle — **the Gradle toolchain auto-resolves the required JDK 25**, so any recent JDK to
  launch the wrapper is enough
- Node.js 20+ (for the `modules/ui-web` frontend) — `scripts/setup/bootstrap-node-win.ps1` can install it for you

That's it. `./gradlew build` (compile + unit tests) does **not** require the Rust/Tauri toolchain (the desktop
shell in `modules/shell` builds separately, only for packaging the installer), a **GPU**, or the **~9 GB model
download** — the models are fetched on first *run* of the app, not to build or test it.

**To run the full desktop app** (not needed to contribute code):

- NVIDIA GPU with 8 GB+ VRAM is optional (CPU works); the local AI models download once on first run.

### Building

```powershell
# Full build with tests
./gradlew.bat build

# Build without tests (faster)
./gradlew.bat build -x test

# Run the desktop UI
./gradlew.bat :modules:ui:run
```

### Testing

```powershell
# Run all tests
./gradlew.bat test

# Run specific module tests
./gradlew.bat :modules:adapters-lucene:test

# Full verification (recommended before PR)
./gradlew.bat build
```

### Code Style

Code style is enforced automatically:

- **Java**: Spotless (auto-formats on build)
- **PMD**: Static analysis rules enforced
- **TypeScript**: ESLint + Prettier

To fix formatting issues:
```powershell
./gradlew.bat spotlessApply
```

## Contributor agreement: CLA

Contributing to JustSearch requires a one-time Contributor License Agreement (CLA). The CLA keeps
the project's long-term licensing options open — **without taking away your rights**: you keep the
copyright to everything you contribute, and the project is and stays open-source under
**Apache-2.0**.

### Contributor License Agreement (CLA) — signed once

When you open your **first pull request**, an automated **CLA assistant** comments on it. You agree by
replying once (a single comment); that records your signature against your GitHub account and covers
all your future contributions. A PR can't be merged until the CLA is signed.

**What you're agreeing to** (full text: [`CLA.md`](CLA.md)): you **keep your copyright**, and you grant
the maintainer a broad licence to use your contribution — including the right to license the project as
a whole under additional terms in the future (e.g. a commercial licence alongside the open-source one).
This is **not** a copyright assignment and does **not** make JustSearch proprietary. It exists because,
with only the open-source licence, the project could never offer a dual/commercial licence later without
the consent of every past contributor — the CLA preserves that option now, while contributors are few.
If you contribute on behalf of an employer, see §8 of the CLA (a Corporate CLA may be needed).

## Project Structure

| Directory | Purpose |
|-----------|---------|
| `modules/ui` | HTTP API (Head process) |
| `modules/ui-web` | Frontend (Lit web components, TypeScript, Vite) |
| `modules/indexer-worker` | Lucene indexing (Body process) |
| `modules/gpu-bridge` | GPU/VRAM detection, hardware capability helpers |
| `modules/app-inference` | Local `llama-server` lifecycle (chat/RAG) |
| `modules/shell` | Tauri desktop shell |
| `docs/` | Documentation (Diataxis structure) |

For detailed architecture, see [System Overview](docs/explanation/01-system-overview.md).

## Questions?

Open a [GitHub Discussion](https://github.com/eliasjustus/JustSearch/discussions) for questions or ideas that aren't bug reports or feature requests.
