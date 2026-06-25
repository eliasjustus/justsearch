---
title: Author an Offline AI Pack (v2 models-only)
type: how-to
status: draft
description: "Step-by-step guide for creating offline AI packs."
---

## What this does

This repo supports importing **offline AI Packs** (v2, **models-only**) via the local UI/API.

An offline AI Pack is a **zip** with:

- `pack-manifest.v1.json` at the zip root
- model files under `payload/...`

The app **allowlists packs by the SHA-256 of the exact `pack-manifest.v1.json` bytes** stored in the pack.

## Tooling (PowerShell)

Use:

- `scripts/ai/pack-author.ps1`

It will:

- build a valid v2 models-only pack zip
- compute strict SHA-256 hashes and sizes
- print the **manifest digest** and a **ready-to-paste `policy.v1.json` snippet**

## Example

From repo root (Windows PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ai/pack-author.ps1 `
  -ChatModelPath .\models\Qwen_Qwen3.5-9B-Q4_K_M.gguf `
  -EmbeddingModelPath .\models\nomic-embed-text-v1.5.Q4_K_M.gguf `
  -PackId justsearch.ai-pack.v2.models.default `
  -PackVersion 2.0.0 `
  -RequiresAppMin 1.0.0 `
  -OutputZip .\dist\ai-pack.v2.models.default-2.0.0.zip `
  -Force
```

## Where to paste the printed policy snippet

Policy file locations (Windows):

- **Machine policy (admin-managed)**: `%PROGRAMDATA%\JustSearch\policy.v1.json`
- **User policy (restrictive-only)**: `<AI_HOME>\policy.v1.json` (AI Home is typically `%APPDATA%\io.justsearch.shell\`)

See:

- `docs/explanation/15-enterprise-policy.md`
- `docs/explanation/14-ai-pack-spec.md`

## Important notes (digest stability)

- The allowlist key is the SHA-256 of the **manifest file bytes** stored in the pack.
  - If you change **whitespace**, **field ordering**, **newline style**, or include/remove a **UTF-8 BOM**, the digest changes.
- The authoring script writes UTF-8 **without BOM** and uses deterministic JSON output.
- If you run with `-PrettyManifest`, the manifest bytes (and therefore the digest) will be different than the default compact form.

## Importing the pack

The UI calls the local API endpoints:

- `POST /api/ai/packs/preflight` (compute digest + basic checks)
- `POST /api/ai/packs/import` (install)

The importer is fail-closed:

- every payload file must be declared in the manifest with exact `sha256` and `sizeBytes`
- no extra payload files are allowed
- `pathInPack` must start with `payload/` and use `/` separators


