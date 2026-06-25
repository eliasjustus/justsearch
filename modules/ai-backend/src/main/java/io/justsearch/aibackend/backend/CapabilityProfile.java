/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import java.util.List;
import java.util.Objects;

public record CapabilityProfile(
    int contextLength, double maxThroughput, SecurityTier securityTier, List<String> capabilities) {
  public CapabilityProfile(
      int contextLength, double maxThroughput, SecurityTier securityTier, List<String> capabilities) {
    this.contextLength = Math.max(0, contextLength);
    this.maxThroughput = Math.max(0.0d, maxThroughput);
    this.securityTier = Objects.requireNonNull(securityTier, "securityTier");
    this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }
}
