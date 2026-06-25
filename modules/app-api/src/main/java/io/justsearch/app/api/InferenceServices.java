/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Inference-related services: online-AI runtime control, brain-runtime mode switching, runtime
 * variant activation (GPU booster pack), pack import, and brain-install orchestration. These
 * services manage the LLM lifecycle and on-disk model installation; they remain valid even when
 * the inference engine is OFFLINE (callers test {@code OnlineAiService.isAvailable()} etc.).
 *
 * <p>Part of the typed-service-graph that replaces the {@code AppFacade} locator interface
 * (tempdoc 519 §5 / Block C.1).
 *
 * <p>Stability: stable (API contract).
 */
public record InferenceServices(
    OnlineAiService onlineAi,
    BrainRuntimeService brainRuntime,
    RuntimeVariantService runtimeVariant,
    PackImportService packImport,
    BrainInstallService brainInstall) {}
