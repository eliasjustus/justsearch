/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Classification of API errors by their nature and recovery action.
 *
 * <p>Every {@link ApiErrorCode} carries an {@code ErrorClass} so that consumers
 * (frontends, retry logic, telemetry) can make data-driven decisions without
 * maintaining hardcoded lists of individual error codes.
 */
public enum ErrorClass {

    /** Temporary failure — safe to retry after a short delay. */
    TRANSIENT,

    /** Unrecoverable failure — stop retrying, surface to user. */
    PERMANENT,

    /** Policy or permission violation — escalate to user or administrator. */
    POLICY,

    /** Bad input or missing parameter — fix the request and retry. */
    VALIDATION
}
