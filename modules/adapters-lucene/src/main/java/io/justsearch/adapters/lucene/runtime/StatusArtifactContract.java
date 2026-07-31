/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-time contract (tempdoc 798, executing tempdoc 717's deferred "contract fix at the write
 * boundary"): <b>a write that sets {@code <stage>_status = COMPLETED} must carry that status's
 * witnessing artifact field in the same write's field map.</b>
 *
 * <p>Enforced at both write lanes — {@link IndexingCoordinator#validate} for full documents and
 * {@link WritePathOps#readModifyWrite} over the merged map for partial updates — because the RMW
 * lane never calls {@code validate()}.
 *
 * <p>Why it exists: writers that stamped a status COMPLETED without producing the artifact
 * manufactured a data-less COMPLETED, and the RMW preservation policy (tempdocs 711/717) correctly
 * healed each one straight back to PENDING. The two fought forever and livelocked the indexing
 * worker. The reset lanes stay as they are — they are the defense-in-depth backstop for state that
 * is already on disk. This contract is the front door that stops new lies from being written.
 *
 * <p>The status&harr;artifact pairing is not a new schema key: it is inverted from the {@code
 * rmwPolicy} strings the catalog already declares (see {@link FieldMapper#statusWitnessFields()}).
 *
 * <p>Deliberately a pure map check — no index IO, no vector read-back, no corpus-wide presence
 * counting. The question "does this write claim something it did not bring?" is answerable from the
 * write itself.
 */
final class StatusArtifactContract {

  private static final Logger log = LoggerFactory.getLogger(StatusArtifactContract.class);

  /** The one status token that asserts an artifact exists. PENDING/FAILED/COMPLETED_EMPTY do not. */
  private static final String STATUS_COMPLETED = "COMPLETED";

  private StatusArtifactContract() {}

  /**
   * Enforces the contract over one write's field map, honouring the session's {@link
   * ValidationMode}: FAIL throws, WARN logs and proceeds.
   *
   * @param session the runtime session (supplies the catalog, validation mode, and telemetry sink)
   * @param fields the fields this write will index — for the RMW lane, the fully merged map
   * @param lane short description of the write lane, for the failure message
   */
  static void enforce(RuntimeSession session, Map<String, Object> fields, String lane) {
    if (fields == null || fields.isEmpty()) return;
    Map<String, List<String>> witnesses = session.fieldMapper.statusWitnessFields();
    for (Map.Entry<String, List<String>> entry : witnesses.entrySet()) {
      String statusField = entry.getKey();
      if (!claimsCompleted(fields.get(statusField))) continue;
      List<String> artifacts = entry.getValue();
      if (anyArtifactMaterializes(session, fields, artifacts)) continue;

      TelemetryEvents events = session.telemetryEvents;
      if (events != null) events.onValidationFailure(ValidationReason.STATUS_WITHOUT_ARTIFACT);
      String detail =
          statusField
              + "=COMPLETED without "
              + String.join(" or ", artifacts)
              + " in the write ["
              + lane
              + "]";
      if (session.validationMode == ValidationMode.FAIL) {
        throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.CONFIGURATION,
            "status_without_artifact: " + detail,
            null);
      }
      log.warn("Validation warn: status_without_artifact — {}", detail);
    }
  }

  private static boolean claimsCompleted(Object statusValue) {
    return statusValue instanceof CharSequence cs && STATUS_COMPLETED.contentEquals(cs);
  }

  /**
   * The question is materialization, not presence: an empty (or all-non-positive) {@code splade}
   * weight map is non-null yet indexes zero postings, so a bare null check would wave through the
   * very data-less COMPLETED this contract exists to reject. {@link FieldMapper#wouldMaterialize}
   * owns the predicate so it stays identical to what {@code addFields} actually does.
   */
  private static boolean anyArtifactMaterializes(
      RuntimeSession session, Map<String, Object> fields, List<String> artifacts) {
    for (String artifact : artifacts) {
      if (session.fieldMapper.wouldMaterialize(artifact, fields.get(artifact))) return true;
    }
    return false;
  }
}
