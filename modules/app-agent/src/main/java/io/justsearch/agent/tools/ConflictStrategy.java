/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

/** Strategy for handling destination conflicts in file operations. */
enum ConflictStrategy {
  /** Fail the entire batch if any destination already exists (default). */
  FAIL,
  /** Skip individual operations whose destination already exists. */
  SKIP,
  /** Automatically rename the destination with a numeric suffix, e.g. "file (1).txt". */
  AUTO_SUFFIX
}
