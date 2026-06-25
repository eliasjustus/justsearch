/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

public final class BackendRegistry {
  private static final Map<String, String> ALIASES =
      Map.of(
          "ggml", "llama",
          "llama_cpp", "llama",
          "llama-cpp", "llama");
  private final Map<String, BackendProvider> providers = new LinkedHashMap<>();

  public BackendRegistry() {
    ServiceLoader.load(BackendProvider.class).forEach(provider -> providers.put(provider.providerId(), provider));
  }

  /** Package-private constructor for testing with custom providers. */
  BackendRegistry(Map<String, BackendProvider> providers) {
    this.providers.putAll(providers);
  }

  public Optional<BackendHandle> resolve(String providerId, LocalIntentTranslatorConfig config)
      throws BackendException {
    BackendProvider provider = providers.get(providerId);
    if (provider == null) {
      String alias = ALIASES.get(providerId);
      if (alias != null) {
        provider = providers.get(alias);
      }
    }
    if (provider == null) {
      return Optional.empty();
    }
    BackendDescriptor descriptor = provider.describe(config);
    if (!config.allowRemoteExecution()
        && descriptor.profile().securityTier() != SecurityTier.LOCAL_ONLY) {
      throw new BackendException(
          "Remote backend "
              + providerId
              + " blocked: allow_remote flag is false (security_tier="
              + descriptor.profile().securityTier()
              + ")");
    }
    return Optional.of(new BackendHandle(provider, descriptor));
  }

  public Collection<BackendDescriptor> listDescriptors(LocalIntentTranslatorConfig config) {
    return Collections.unmodifiableList(
        providers.values().stream().map(provider -> provider.describe(config)).toList());
  }
}
