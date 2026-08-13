/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Boot-time worker PID validation exhausted its window without confirming the worker's identity.
 *
 * <p>A distinct type rather than a bare {@link IllegalStateException} because the Head has to tell
 * this apart from a genuinely unstartable worker: the worker process is already spawned and may be
 * perfectly healthy — only the Head's confirming RPC ran out of budget. That distinction drives two
 * behaviours: the bootstrap start is retried instead of pinning the worker capability DEGRADED for
 * the life of the process, and the operator hint does not blame a missing worker build.
 */
public final class PidValidationTimeoutException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PidValidationTimeoutException(String message) {
        super(message);
    }
}
