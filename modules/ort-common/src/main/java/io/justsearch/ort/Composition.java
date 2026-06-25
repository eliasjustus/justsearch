/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Complete input to
 * {@link OrtSessionAssembler#buildManager(String, Composition, GpuArbiter)}.
 *
 * <p>The composition root (tempdoc 397 §7.6) assembles this by: resolving {@link RuntimePolicy}
 * from environment + hardware; resolving {@link ModelSessionPolicy} per encoder from the
 * resolved-config + hardware + variant; gathering {@link ModelArtifacts} from the variant and
 * native-path helpers; and calling {@code buildManager(...)} to get a
 * {@link SessionHandle}.
 *
 * <p>{@code OrtEnvironment} is fetched from its JVM singleton inside the assembler and not
 * carried here — there is exactly one per process by ORT's design (see 311-ort-session-lifecycle-
 * research §10).
 *
 * @param runtime process-wide options identical for every encoder
 * @param modelSession encoder-specific options including per-session RunOptions
 * @param artifacts paths the assembler hands to {@link SessionOptionsApplier} and
 *     {@link OnnxSessionCache}
 */
public record Composition(
    RuntimePolicy runtime, ModelSessionPolicy modelSession, ModelArtifacts artifacts) {}
