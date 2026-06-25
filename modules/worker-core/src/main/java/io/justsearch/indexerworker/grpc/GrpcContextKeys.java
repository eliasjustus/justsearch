/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.Context;

final class GrpcContextKeys {
  private GrpcContextKeys() {}

  static final Context.Key<String> REQUEST_ID = Context.key("js-indexer-request-id");
}
