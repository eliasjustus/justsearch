/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * Backend-side capability required for an Operation to be invocable.
 *
 * <p>Per tempdoc 429 §6 + §A.7 ExecutorBindingValidator: the executor verifies declared
 * capabilities exist before dispatch; missing capabilities surface as a typed denial
 * rather than silent failure.
 *
 * <p>Sealed type permits a closed V1 vocabulary; new variants land additively as
 * subsystems publish capability handles.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RequiredCapability.WorkerOnline.class, name = "worker-online"),
    @JsonSubTypes.Type(value = RequiredCapability.InferenceOnline.class, name = "inference-online"),
    @JsonSubTypes.Type(value = RequiredCapability.IndexedRoot.class, name = "indexed-root"),
    @JsonSubTypes.Type(value = RequiredCapability.GpuAvailable.class, name = "gpu-available")
})
public sealed interface RequiredCapability
    permits RequiredCapability.WorkerOnline,
        RequiredCapability.InferenceOnline,
        RequiredCapability.IndexedRoot,
        RequiredCapability.GpuAvailable {

  /** Operation requires the Worker (Body) process to be reachable via gRPC. */
  record WorkerOnline() implements RequiredCapability {
    public static final WorkerOnline INSTANCE = new WorkerOnline();
  }

  /** Operation requires the Inference (Brain) process to be reachable via HTTP. */
  record InferenceOnline() implements RequiredCapability {
    public static final InferenceOnline INSTANCE = new InferenceOnline();
  }

  /** Operation requires at least one indexed root (e.g., file-system tools). */
  record IndexedRoot() implements RequiredCapability {
    public static final IndexedRoot INSTANCE = new IndexedRoot();
  }

  /** Operation requires a GPU to be available (e.g., GPU-bound inference operations). */
  record GpuAvailable(String minVendor) implements RequiredCapability {
    public GpuAvailable {
      Objects.requireNonNull(minVendor, "minVendor");
    }
  }
}
