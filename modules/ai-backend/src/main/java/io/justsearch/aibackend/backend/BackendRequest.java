/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.local.DeterminismProfile;
import java.util.Locale;
import java.util.Objects;

public record BackendRequest(String text, Locale locale, DeterminismProfile determinismProfile) {
  public BackendRequest {
    text = text == null ? "" : text;
    Objects.requireNonNull(determinismProfile, "determinismProfile");
  }
}
