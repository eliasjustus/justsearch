/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.IngestServiceGrpc;
import java.util.function.Function;

/**
 * Abstraction for executing ingest RPCs with circuit breaker and deadline support.
 *
 * <p>Companion classes use this instead of depending on {@link RemoteKnowledgeClient} directly.
 */
@FunctionalInterface
interface IngestRpcExecutor {
    <T> T execute(
            String operation,
            RemoteKnowledgeClient.RpcDeadlineCategory category,
            Function<IngestServiceGrpc.IngestServiceBlockingStub, T> rpc);
}
