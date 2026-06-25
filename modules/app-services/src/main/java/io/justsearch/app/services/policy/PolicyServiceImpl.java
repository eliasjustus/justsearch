/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.policy;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.PolicyService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Production implementation of {@link PolicyService}, extracted from {@code PolicyController}
 * as part of tempdoc 519 §9 Block B3 / Step 3. Composes the B1-extracted
 * {@link EnterprisePolicyService} interface and the (already-relocated)
 * {@link UserPolicyWriter} (moved by B3.0.c).
 */
public final class PolicyServiceImpl implements PolicyService {

  private final EnterprisePolicyService policyService;

  public PolicyServiceImpl(EnterprisePolicyService policyService) {
    this.policyService = Objects.requireNonNull(policyService, "policyService");
  }

  @Override
  public Map<String, Object> createUserPolicy(String manifestSha256) throws Exception {
    if (manifestSha256 == null || manifestSha256.isBlank()) {
      throw new IllegalArgumentException("Missing manifestSha256");
    }
    EffectivePolicy effective = policyService.snapshot();
    UserPolicyWriter.Result r =
        UserPolicyWriter.createUserPolicyForPackImportIfMissing(effective, manifestSha256);
    Map<String, Object> out = new HashMap<>();
    out.put("path", r.path() == null ? "" : r.path().toAbsolutePath().toString());
    return out;
  }

  @Override
  public Map<String, Object> addDigestToAllowlist(String manifestSha256) throws Exception {
    if (manifestSha256 == null || manifestSha256.isBlank()) {
      throw new IllegalArgumentException("Missing manifestSha256");
    }
    EffectivePolicy effective = policyService.snapshot();
    UserPolicyWriter.AppendResult r =
        UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(effective, manifestSha256);
    Map<String, Object> out = new HashMap<>();
    out.put("path", r.path() == null ? "" : r.path().toAbsolutePath().toString());
    out.put("changed", r.changed());
    out.put("allowlistedCount", r.allowlistedCount());
    return out;
  }
}
