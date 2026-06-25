/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

/**
 * Model-intrinsic facts about a BGE-M3 session. Tempdoc 397 §14.24 FD-BgeM3: lets
 * {@link BgeM3Encoder} receive input-schema + vocabulary metadata via typed record instead of
 * loading it from the session + filesystem at construction time.
 *
 * <p>BGE-M3's vocabulary is ~250K XLM-RoBERTa tokens parsed from {@code tokenizer.json} (not
 * a separate {@code vocab.txt}). Parsing that once at boot saves ~100–300ms of parser time on
 * every encoder-recreate path.
 *
 * @param maxSequenceLength maximum tokens per inference call (typically 8192)
 * @param vocabulary token-id → token-string mapping (XLM-RoBERTa, ~250K entries)
 */
public record BgeM3Shape(int maxSequenceLength, String[] vocabulary) {}
