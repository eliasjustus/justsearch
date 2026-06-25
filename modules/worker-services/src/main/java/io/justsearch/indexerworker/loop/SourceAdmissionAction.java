/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

enum SourceAdmissionAction {
  ADMIT,
  SKIP_DONE,
  STALE_DONE,
  RETRYABLE_FAILURE
}
