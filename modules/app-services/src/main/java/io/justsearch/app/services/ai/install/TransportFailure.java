/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.Set;

/**
 * Why a transport attempt failed, and whether re-attempting it later could plausibly succeed.
 *
 * <p>The retry policy in {@link ResumableFetch} is only allowed to spend time on failures this type
 * calls {@link #retryable()}. Round 16 measured the cost of getting that wrong in the other
 * direction: curl's built-in {@code --retry} covers only its own "transient" set, so an exit 52
 * (empty reply from server) produced exactly one attempt in ~3 ms and became a permanent package
 * failure. Retrying a deterministic failure is the mirror mistake — a 404 re-attempted for 40 s is
 * pure latency — so HTTP status failures (curl exit 22 under {@code --fail}) and integrity failures
 * are classified permanent and end the fetch immediately.
 *
 * @param code short, log-and-UI-safe identifier of the failure ("curl exit 52")
 * @param detail free text — for curl, the tail of its merged output; may be empty, never null
 * @param retryable whether a later, spaced attempt could plausibly succeed
 */
public record TransportFailure(String code, String detail, boolean retryable) {

  /** curl's CURLE_HTTP_RETURNED_ERROR: {@code --fail} saw an HTTP 4xx/5xx. Never retried here. */
  public static final int CURL_HTTP_RETURNED_ERROR = 22;

  /**
   * curl exits that mean "the connection did not work this time", which is exactly the class round
   * 16's environment produced in bursts: 52 empty reply, 35 TLS handshake, 7 connect failed, 28
   * operation timeout, 56 receive error, 18 partial transfer.
   */
  private static final Set<Integer> RETRYABLE_CURL_EXITS = Set.of(52, 35, 7, 28, 56, 18);

  public TransportFailure {
    code = code == null ? "" : code;
    detail = detail == null ? "" : detail;
  }

  /** Classifies a curl exit code. */
  public static TransportFailure curlExit(int exitCode, String detail) {
    return new TransportFailure(
        "curl exit " + exitCode, detail, RETRYABLE_CURL_EXITS.contains(exitCode));
  }

  /** curl.exe could not be launched at all — a retry can succeed (transient AV/handle exhaustion). */
  public static TransportFailure curlLaunchFailed(String detail) {
    return new TransportFailure("curl launch failed", detail, true);
  }

  /**
   * Classifies a BITS job failure by its terminal job state. {@code Error} is BITS' own verdict that
   * it has given up; every other state it fails out of (notably {@code TransientError} after its
   * retry budget) is worth another spaced attempt through a different transport.
   */
  public static TransportFailure bits(String jobState, String detail) {
    String state = jobState == null || jobState.isBlank() ? "unknown" : jobState;
    return new TransportFailure("BITS " + state, detail, !"Error".equals(state));
  }

  /** The user asked to stop. Never retried; the fetch reports cancellation, not failure. */
  public static TransportFailure cancelled() {
    return new TransportFailure("cancelled", "", false);
  }

  /** One-line "code: detail" summary for a failure message, collapsing an empty detail. */
  public String summary() {
    return detail.isEmpty() ? code : code + ": " + detail;
  }
}
