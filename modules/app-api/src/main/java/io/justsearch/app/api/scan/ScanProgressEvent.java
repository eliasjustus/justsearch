/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.scan;

/**
 * Domain-level scan progress event for the {@code GET /api/scans/{scanId}/progress} SSE
 * endpoint (tempdoc 419 / T4). Decouples {@code ui.api} from the {@code ipc.ScanRootProgress}
 * proto type — the architecture rule forbids {@code ui.api} from importing proto message
 * types directly. The adapter layer ({@code KnowledgeHttpApiAdapter}) converts proto into
 * this record before publishing into the {@code ScanProgressRegistry}.
 *
 * <p>Stability: stable (API contract).
 *
 * @param scanId Worker-allocated UUID; same value across every event for a given scan.
 * @param filesWalked Total files visited (admitted + skipped + failed-to-stat).
 * @param filesAdmitted Files added to the indexing queue.
 * @param filesSkipped Files visited but not admitted (skip policy, cloud placeholders, etc.).
 * @param bytesWalked Total bytes accounted for during the walk.
 * @param currentDirectory Privacy-hashed directory path for the most recent observation
 *     (matches the {@code path_hash} normalization used by the ingestion ledger; not a raw
 *     path).
 * @param complete True only on the terminal event for the scan.
 * @param terminalReasonCode Empty on clean completion; populated with a stable code on
 *     failure paths ({@code IO_ERROR}, {@code CLIENT_CANCELLED}, {@code RPC_FAILED},
 *     {@code ROOT_NOT_DIRECTORY}, {@code UNKNOWN_SCAN_OR_RETENTION_EXPIRED}, etc.).
 */
public record ScanProgressEvent(
    String scanId,
    long filesWalked,
    long filesAdmitted,
    long filesSkipped,
    long bytesWalked,
    String currentDirectory,
    boolean complete,
    String terminalReasonCode) {

  public ScanProgressEvent {
    scanId = scanId == null ? "" : scanId;
    currentDirectory = currentDirectory == null ? "" : currentDirectory;
    terminalReasonCode = terminalReasonCode == null ? "" : terminalReasonCode;
  }

  /**
   * Synthesizes a terminal-only event for cases where the registry needs to emit something
   * but there's no real progress to report (unknown scanId, retention expired, etc.).
   */
  public static ScanProgressEvent terminal(String scanId, String terminalReasonCode) {
    return new ScanProgressEvent(scanId, 0L, 0L, 0L, 0L, "", true, terminalReasonCode);
  }
}
