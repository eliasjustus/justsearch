/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

/**
 * Sealed type enumerating the observable lifecycle transitions of a single {@code SessionHandle}.
 * The set of permits IS the set of observable handle-lifetime events: every {@code ort.session.*}
 * counter derives from one of these permits, and adding a new permit forces every consumer (the
 * metric adapter, the future ring-buffer recorder, any diagnostic surface) to handle it via
 * exhaustive {@code switch}. Drift between "what the handle actually does" and "what we observe"
 * becomes structurally impossible.
 *
 * <p>Construction-time events (failures inside {@code OrtSessionAssembler.buildManager}, before
 * a handle exists) are modeled separately as {@link AssemblerEvent}.
 *
 * <p>Tempdoc 414. Pattern reference: ADR-0027 telemetry catalog substrate.
 */
public sealed interface TransitionReason {

  /** The encoder name (e.g., {@code "embed"}, {@code "splade"}). Always non-null. */
  String consumer();

  /** GPU session created successfully. */
  record GpuInitialized(String consumer) implements TransitionReason {
    public GpuInitialized {
      java.util.Objects.requireNonNull(consumer, "consumer");
    }
  }

  /** GPU session creation failed; handle will operate on CPU until the retry interval elapses. */
  record GpuInitFailed(String consumer, FailureCause cause) implements TransitionReason {
    public GpuInitFailed {
      java.util.Objects.requireNonNull(consumer, "consumer");
      java.util.Objects.requireNonNull(cause, "cause");
    }
  }

  /** {@code releaseGpu()} completed successfully; VRAM yielded. */
  record GpuReleaseCompleted(String consumer) implements TransitionReason {
    public GpuReleaseCompleted {
      java.util.Objects.requireNonNull(consumer, "consumer");
    }
  }

  /** {@code releaseGpu()} threw — typically a session-close failure or an unexpected JVM error. */
  record GpuReleaseFailed(String consumer) implements TransitionReason {
    public GpuReleaseFailed {
      java.util.Objects.requireNonNull(consumer, "consumer");
    }
  }

  /**
   * The silent line-260 case: an {@code acquire()} caller passed the fast-path GPU check, was
   * scheduled behind the inference semaphore, and on resumption discovered that
   * {@code releaseGpu()} had run while it waited. The lease falls back to CPU. Today this only
   * sets a span attribute (when tracing is enabled, which is off by default); making this a
   * counter is the central operational win of tempdoc 414.
   */
  record GpuFallbackTaken(String consumer) implements TransitionReason {
    public GpuFallbackTaken {
      java.util.Objects.requireNonNull(consumer, "consumer");
    }
  }

  /**
   * CPU session torn down + recreated after a reported failure (BFCArena allocation failure,
   * unspecified inference error). See F-009 in the inference-runtime register.
   */
  record CpuSessionRecreated(String consumer, CpuRecreateCause cause) implements TransitionReason {
    public CpuSessionRecreated {
      java.util.Objects.requireNonNull(consumer, "consumer");
      java.util.Objects.requireNonNull(cause, "cause");
    }
  }

  /**
   * GPU session creation re-attempted after a prior {@link GpuInitFailed}. Fired from
   * {@code selectSession()} when the retry interval has elapsed.
   *
   * @param sinceFailureMs milliseconds since {@link GpuInitFailed} fired (recorded into
   *     {@code ort.session.retry_interval_ms})
   */
  record GpuRetryAttempted(String consumer, long sinceFailureMs) implements TransitionReason {
    public GpuRetryAttempted {
      java.util.Objects.requireNonNull(consumer, "consumer");
    }
  }
}
