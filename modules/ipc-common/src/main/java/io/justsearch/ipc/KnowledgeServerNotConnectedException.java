/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc;

/**
 * Thrown when an operation requires a connected Knowledge Server but none is available.
 *
 * <p>Extends {@link IllegalStateException} for binary compatibility with existing catch blocks.
 */
public class KnowledgeServerNotConnectedException extends IllegalStateException {

    public KnowledgeServerNotConnectedException() {
        super("Not connected to Knowledge Server");
    }
}
