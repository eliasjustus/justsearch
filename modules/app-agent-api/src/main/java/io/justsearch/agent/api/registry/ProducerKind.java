/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Producer-side mechanism for a {@link DiagnosticChannel}'s emissions.
 *
 * <p>Per slice 448 §4: declared on the channel so consumers can reason about delivery
 * semantics. V1 only implements {@link #IN_PROCESS_LOGBACK}; the other two values are
 * forward-compat slots — wire shape supports them, V1 does not implement them.
 *
 * <p>The cross-process slot {@link #WORKER_GRPC_STREAM} mirrors the existing
 * {@code InfraDiagnosticsService} server-streaming gRPC pattern (see
 * {@code modules/ipc-common/src/main/proto/.../infra_diagnostics.proto}); see slice 448
 * §B.A confirmation that this precedent reduces "single-instance sample" risk.
 */
public enum ProducerKind {

  /** In-process Logback appender forwarding events to the substrate. V1 implementation. */
  IN_PROCESS_LOGBACK,

  /**
   * Worker → Head log forwarding via server-streaming gRPC. Forward-compat slot for V1;
   * declared so consumers can plan but not implemented in this slice.
   */
  WORKER_GRPC_STREAM,

  /**
   * Third-party producers (e.g., OTel collector forwarding spans into the substrate).
   * Forward-compat slot for V1.
   */
  EXTERNAL_OBSERVER
}
