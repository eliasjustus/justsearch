/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;

public interface BackendProvider {
  String providerId();

  BackendDescriptor describe(LocalIntentTranslatorConfig config);

  AiBackend create(LocalIntentTranslatorConfig config) throws BackendException;
}
