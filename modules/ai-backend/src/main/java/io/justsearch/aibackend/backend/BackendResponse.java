/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

public record BackendResponse(String intentJson) {
  public BackendResponse {
    intentJson = intentJson == null ? "" : intentJson;
  }
}
