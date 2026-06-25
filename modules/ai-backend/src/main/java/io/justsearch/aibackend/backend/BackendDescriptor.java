/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import java.util.List;
import java.util.Objects;

public record BackendDescriptor(
    String providerId, List<String> supportedTasks, CapabilityProfile profile) {
  public BackendDescriptor(
      String providerId, List<String> supportedTasks, CapabilityProfile profile) {
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.supportedTasks = supportedTasks == null ? List.of() : List.copyOf(supportedTasks);
    this.profile = Objects.requireNonNull(profile, "profile");
  }
}
