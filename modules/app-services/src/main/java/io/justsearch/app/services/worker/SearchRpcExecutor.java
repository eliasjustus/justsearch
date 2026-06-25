/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.SearchServiceGrpc;
import java.util.function.Function;

/**
 * Abstraction for executing search RPCs with circuit breaker and deadline support.
 *
 * <p>Companion classes use this instead of depending on {@link RemoteKnowledgeClient} directly.
 */
@FunctionalInterface
interface SearchRpcExecutor {
    <T> T execute(
            String operation,
            RemoteKnowledgeClient.RpcDeadlineCategory category,
            Function<SearchServiceGrpc.SearchServiceBlockingStub, T> rpc);
}
