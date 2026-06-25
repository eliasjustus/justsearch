/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc;

/**
 * Exception thrown when an RPC is rejected because the circuit breaker is open.
 *
 * <p>This indicates that the worker is unavailable and the circuit breaker
 * is in its cooldown period to prevent tight-loop hammering.
 *
 * <p>Callers should catch this exception and display an appropriate message
 * to the user indicating that the search service is temporarily unavailable.
 */
public class CircuitBreakerOpenException extends RuntimeException {

    public CircuitBreakerOpenException(String message) {
        super(message);
    }

    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
