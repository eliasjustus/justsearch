/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

/**
 * Tempdoc 629 (#1) — the ONE authoritative classification of every {@code dataDir} store 629 touches.
 *
 * <p>This is the design's spine made load-bearing: "which store is which {@link StoreRecoverability
 * class}" is declared here, ONCE, and every obligation projects from it instead of hardcoding its own
 * copy — the encryption cipher selection reads {@link #recoverability()}, the encrypted backup/import
 * enumerate the {@link StoreRecoverability#AUTHORED} entries, and the {@code store-recoverability} register
 * mirrors this list (its closure gate fails the build if a constructed store is missing a class). Before
 * this, encryption selected its cipher per-site and the export re-enumerated the AUTHORED stores by hand
 * — two independent copies of the same fact. Now there is one.
 */
public enum StoreCatalog {
  CONVERSATIONS("conversations", StoreRecoverability.AUTHORED, Framing.MIXED),
  MEMORIES("memories", StoreRecoverability.AUTHORED, Framing.FULL_REWRITE),
  AGENT_RUNS("agent-runs", StoreRecoverability.AUTHORED, Framing.MIXED),
  INDEX("index", StoreRecoverability.DERIVED, Framing.OPAQUE),
  JOBS_DB("jobs.db", StoreRecoverability.DERIVED, Framing.OPAQUE);

  /** How a store frames its on-disk writes — the input to {@link StoreCipher}'s seal/open granularity. */
  public enum Framing {
    /** Per-line sealed append logs ({@code events.ndjson}, {@code messages.jsonl}). */
    APPEND_ONLY_LINES,
    /** Whole-file sealed rewrites ({@code memory.json}). */
    FULL_REWRITE,
    /** Both an append log and a full-rewrite meta file (conversations, agent-runs). */
    MIXED,
    /** Not app-framed — a DERIVED store covered by OS disk encryption only (the index, jobs.db). */
    OPAQUE
  }

  private final String dirName;
  private final StoreRecoverability recoverability;
  private final Framing framing;

  StoreCatalog(String dirName, StoreRecoverability recoverability, Framing framing) {
    this.dirName = dirName;
    this.recoverability = recoverability;
    this.framing = framing;
  }

  /** The store's directory/file name under {@code dataDir} (also the backup-bundle section key). */
  public String dirName() {
    return dirName;
  }

  public StoreRecoverability recoverability() {
    return recoverability;
  }

  public Framing framing() {
    return framing;
  }

  public boolean isAuthored() {
    return recoverability == StoreRecoverability.AUTHORED;
  }
}
