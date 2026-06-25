/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

/**
 * Tempdoc 629 (LAYER) — the store-recoverability classification (the design's spine, §0). A store is
 * either:
 *
 * <ul>
 *   <li>{@link #DERIVED} — a rebuildable projection of an external source of truth (the user's
 *       files): the Lucene index, the SQLite job queue. Protected by OS disk encryption (FDE); never
 *       app-encrypted; total key loss is non-catastrophic (it rebuilds, tempdoc 628).
 *   <li>{@link #AUTHORED} — an authority of record with no upstream source: conversations, agent
 *       memories, agent-runs. Not rebuildable; these are the stores the LAYER passphrase-encrypts.
 * </ul>
 *
 * <p>This is the second consumer of the invariant tempdoc 628 named ("reconstruct from
 * source-of-truth") — durability there, confidentiality here.
 */
public enum StoreRecoverability {
  DERIVED,
  AUTHORED
}
