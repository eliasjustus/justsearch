/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;

public record BackendHandle(BackendProvider provider, BackendDescriptor descriptor) {
  public BackendHandle {
    if (provider == null || descriptor == null) {
      throw new IllegalArgumentException("provider and descriptor must be non-null");
    }
  }

  public AiBackend create(LocalIntentTranslatorConfig config) throws BackendException {
    return provider.create(config);
  }
}
