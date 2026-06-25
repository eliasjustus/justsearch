/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimevariant;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.RuntimeActivationService;
import io.justsearch.app.api.RuntimeVariantService;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production implementation of {@link RuntimeVariantService}, extracted from
 * {@code AiRuntimeController} as part of tempdoc 519 §9 Block B3 / Step 3.
 *
 * <p>Composes the {@link RuntimeActivationService} helper interface (defined in {@code app-api}
 * by B2; impl in {@code modules/ui/.../ai/runtime/RuntimeActivationService.java}) and the
 * {@link EnterprisePolicyService} interface (B1) for policy enforcement.
 */
public final class RuntimeVariantServiceImpl implements RuntimeVariantService {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final RuntimeActivationService helper;
  private final EnterprisePolicyService policyService;

  public RuntimeVariantServiceImpl(
      RuntimeActivationService helper, EnterprisePolicyService policyService) {
    this.helper = Objects.requireNonNull(helper, "helper");
    this.policyService = policyService; // may be null (best-effort)
  }

  @Override
  public Map<String, Object> activate(String variantId) throws Exception {
    if (variantId == null || variantId.isBlank()) {
      throw new IllegalArgumentException("Missing variantId");
    }
    EffectivePolicy p = policyService != null ? policyService.snapshot() : null;
    if (p != null) {
      if (!p.onlineAiEnabled()) {
        throw new IllegalStateException("Online AI is disabled by administrator policy.");
      }
      if (!p.gpuAccelerationEnabled()) {
        throw new IllegalStateException("GPU acceleration is disabled by administrator policy.");
      }
    }
    helper.startActivate(variantId);
    return statusAsMap();
  }

  @Override
  public Map<String, Object> deactivate() throws Exception {
    helper.startDeactivate();
    return statusAsMap();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> statusAsMap() {
    return MAPPER.convertValue(helper.getStatus(), Map.class);
  }
}
