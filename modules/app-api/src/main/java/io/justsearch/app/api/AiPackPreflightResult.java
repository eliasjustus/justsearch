/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Preflight result for an AI Pack import: parsed manifest identity.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.pack} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. {@link AiPackImportService#preflight} returns this type.
 */
public record AiPackPreflightResult(String packId, String packVersion, String manifestSha256) {}
