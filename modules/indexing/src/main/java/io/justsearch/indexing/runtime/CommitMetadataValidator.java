/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.runtime;

import java.util.Map;

/**
 * Validates commit metadata against an authoritative schema.
 *
 * <p>Stability: experimental
 */
public interface CommitMetadataValidator {
  /**
   * Validate metadata; throws an exception on validation failure.
   */
  void validate(Map<String, Object> metadata);
}
