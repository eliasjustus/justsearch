# ai-backend module

`modules/ai-backend` contains backend abstractions and local translator support used by the application AI paths.

It is not the old AI bridge module, and it does not own the live `llama-server` process lifecycle. Online llama-server start/adopt/health/reload/stop behavior lives in `modules/app-inference`. GPU and VRAM detection live in `modules/gpu-bridge`. Prompt templates and prompt support utilities live in `modules/prompt-support`.

See [docs/explanation/05-ai-architecture.md](../../docs/explanation/05-ai-architecture.md) and [docs/explanation/19-module-architecture.md](../../docs/explanation/19-module-architecture.md) for current ownership.

## Responsibilities

- Define backend-facing interfaces used by local translation and summarization code.
- Keep local translator support independent from UI and Local API route registration.
- Avoid owning process lifecycle for `llama-server`; call into the app inference layer for online runtime control.
- Avoid owning GPU capability detection; use `gpu-bridge` surfaces where hardware information is needed.
- Avoid owning prompt templates; use `prompt-support` for prompt-specific assets and utilities.

## Development Notes

- Do not add FFM/jextract llama.cpp bindings here. The former in-process `ai-bridge` binding path is historical.
- Do not add direct HTTP lifecycle control for `llama-server` here. Lifecycle belongs to `modules/app-inference`.
- Keep tests focused on backend abstractions and translator behavior, not process management.

## Key References

- Architecture: [05-ai-architecture.md](../../docs/explanation/05-ai-architecture.md)
- Module ownership: [19-module-architecture.md](../../docs/explanation/19-module-architecture.md)
- Historical decomposition decision: [0017-ai-bridge-module-decomposition.md](../../docs/decisions/0017-ai-bridge-module-decomposition.md)
