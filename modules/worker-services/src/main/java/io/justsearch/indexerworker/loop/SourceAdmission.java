/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.ingest.IngestionOutcome;

/** Admission decision for a source path before it reaches parser code. */
record SourceAdmission(SourceAdmissionAction action, FileEnvelope envelope, IngestionOutcome outcome) {
  static SourceAdmission admitted(FileEnvelope envelope) {
    return new SourceAdmission(SourceAdmissionAction.ADMIT, envelope, null);
  }

  static SourceAdmission terminal(SourceAdmissionAction action, IngestionOutcome outcome) {
    return new SourceAdmission(action, null, outcome);
  }
}
