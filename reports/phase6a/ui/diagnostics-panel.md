# Diagnostics Panel Evidence

Rendered via DiagnosticsPanel.render() using the Phase 6a capabilities payload and telemetry snapshot.

| Signal | Value |
| ------ | ----- |
| Pipeline | `search.desktop-default` |
| Profile / Version | `search.desktop-default@1` / `1` |
| Pipeline hash | `3cb78e4523e948bc7833a0b51397995b29d9a821fe9bcb32ea1f7b17212d8d9e` |
| Translator state | `disabled` (`reason=disabled_in_config`) |
| Translator endpoint | `127.0.0.1:50061`, deadline `1500 ms` |
| SSOT snapshot | schema `1.0.0`, grammar `1.0.0`, template `1` |
| Manifest hash | `f09116e6306c3d630724cf5411a779b02f8a49d182b864d138810917a6148256` |
| Search hash | `3cb78e4523e948bc7833a0b51397995b29d9a821fe9bcb32ea1f7b17212d8d9e` |
| Indexing hash | `035d1cbc93199d644441e52213e92a0b1640748a3ae525ee35aaa9f114fb1859` |

**Admitted plugins**

| Plugin | Stage Types | Capabilities | Egress |
| ------ | ----------- | ------------ | ------ |
| extract_ocr (0.1.0) | `extract_ocr` | `ocr` | `https://ocr.service.internal` |
| rerank_onnx (0.1.0) | `rerank_onnx` | `rerank` | n/a |

**Stage latency (p95 ms)**

| Stage | p95 (ms) |
| ----- | -------- |
| parse | 10 |
| rerank | 14 |

**Skip counters**

| Stage | Reason | Count |
| ----- | ------ | ----- |
| rerank | `rerank_skipped_deadline` | 2 |
| translate_intent | `translator_bypass` | 1 |
