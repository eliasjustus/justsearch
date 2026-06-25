/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend.providers;

import io.justsearch.aibackend.backend.BackendDescriptor;
import io.justsearch.aibackend.backend.BackendProvider;
import io.justsearch.aibackend.backend.CapabilityProfile;
import io.justsearch.aibackend.backend.SecurityTier;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.DeterministicBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.List;

public final class DeterministicBackendProvider implements BackendProvider {

  @Override
  public String providerId() {
    return "stub";
  }

  @Override
  public BackendDescriptor describe(LocalIntentTranslatorConfig config) {
    CapabilityProfile profile =
        new CapabilityProfile(config.contextLength(), 1.0d, SecurityTier.LOCAL_ONLY, List.of("deterministic"));
    return new BackendDescriptor(
        providerId(), List.of("intent_v1", "summary_v1", "embed_v1", "classify_v1"), profile);
  }

  @Override
  public AiBackend create(LocalIntentTranslatorConfig config) throws BackendException {
    return new DeterministicBackend(config);
  }
}
