/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.nio.file.Path;

/**
 * Physical artifacts required to construct an ORT session for an encoder.
 *
 * <p>Produced by the composition root from {@link io.justsearch.configuration.model.VariantSelection}.
 * Consumed by {@link OrtSessionAssembler} as one of three fields in {@link Composition}.
 *
 * <p>{@code cpuModelPath} and {@code gpuModelPath} may point at the same file when only one
 * variant is installed (e.g., GPU-lite profile in tempdoc 381 §B ships only the FP16 variant).
 * The assembler's FP16 → FP32 fallback (§7.3) is a no-op when the paths are equal.
 *
 * <p>Native-library path resolution (for DLL preflight via {@link OrtCudaHelper}) is performed
 * by the composition root before assembly, not carried here — the assembler delegates to
 * {@link OnnxSessionCache}, which consults the ORT environment directly.
 *
 * @param cpuModelPath absolute path to the CPU/FP32/INT8 ONNX file
 * @param gpuModelPath absolute path to the GPU/FP16 ONNX file (may equal {@code cpuModelPath})
 */
public record ModelArtifacts(Path cpuModelPath, Path gpuModelPath) {}
