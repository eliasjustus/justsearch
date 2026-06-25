# Contributing to JustSearch

Thank you for your interest in contributing to JustSearch! This document provides guidelines and instructions for contributing.

> **The short version.** Clone the repo, run `./gradlew.bat build`, run the tests, pick a good-first-issue,
> open a PR with a DCO sign-off. That's it. You do **not** need Claude Code, the agent hooks, the dev-stack
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
5. Commit with sign-off (see DCO below)
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

## Developer Certificate of Origin (DCO)

This project uses the Developer Certificate of Origin (DCO) instead of a Contributor License Agreement (CLA). By contributing, you certify that:

1. You have the right to submit the contribution
2. You are submitting it under the project's Apache 2.0 license
3. You understand the contribution will be public

### How to Sign Off

Add `-s` flag to your commits:

```bash
git commit -s -m "Your commit message"
```

This adds a `Signed-off-by` line to your commit:

```
Signed-off-by: Your Name <your.email@example.com>
```

If you forgot to sign off, you can amend:

```bash
git commit --amend -s
```

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
