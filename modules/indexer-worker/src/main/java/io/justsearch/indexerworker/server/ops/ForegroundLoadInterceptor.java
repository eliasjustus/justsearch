/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.ipc.SearchServiceGrpc;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds {@link ForegroundLoad} from the gRPC server (tempdoc 885 item 3).
 *
 * <p>Deliberately the <b>only</b> producer of the gauge, and deliberately thin: under the lane-F
 * Head/Worker merge there is no gRPC boundary left, so this adapter is thrown away while the gauge
 * and the pacing policy it feeds survive.
 *
 * <p><b>Foreground</b> is the nine user-waiting {@code SearchService} methods. The set is built from
 * the generated method descriptors rather than from hand-written strings so a proto rename is a
 * compile error, not a silently-empty filter. Two exclusions matter:
 *
 * <ul>
 *   <li>The whole of {@code IngestService} — notably {@code IndexStatus}, which the Head polls on a
 *       timer. Counting a status poll as foreground would recreate the defect this item removes:
 *       indexing throttled by an observer rather than by a user.
 *   <li>{@code SearchService/ListAllDocumentIds}, whose only caller is the Head's background GPL
 *       job coordinator ({@code GplJobCoordinator} pages the whole corpus through it), not a person
 *       waiting for an answer.
 * </ul>
 */
public final class ForegroundLoadInterceptor implements ServerInterceptor {

  private static final Set<String> FOREGROUND_METHODS =
      Set.of(
          SearchServiceGrpc.getSearchMethod().getFullMethodName(),
          SearchServiceGrpc.getSuggestMethod().getFullMethodName(),
          SearchServiceGrpc.getFetchDocumentsMethod().getFullMethodName(),
          SearchServiceGrpc.getFetchDocumentSliceMethod().getFullMethodName(),
          SearchServiceGrpc.getRetrieveContextMethod().getFullMethodName(),
          SearchServiceGrpc.getMatchCitationsMethod().getFullMethodName(),
          SearchServiceGrpc.getListFoldersMethod().getFullMethodName(),
          SearchServiceGrpc.getListFolderFilesMethod().getFullMethodName(),
          SearchServiceGrpc.getRerankMethod().getFullMethodName());

  private final ForegroundLoad load;

  public ForegroundLoadInterceptor(ForegroundLoad load) {
    this.load = load;
  }

  /** The exact set of full gRPC method names that count as foreground. */
  public static Set<String> foregroundMethods() {
    return FOREGROUND_METHODS;
  }

  public static boolean isForeground(String fullMethodName) {
    return FOREGROUND_METHODS.contains(fullMethodName);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!isForeground(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }

    load.started();
    // One decrement per increment, whichever of the three terminal paths happens first: the server
    // closing the call (OK or error), or the transport cancelling it. `close` and `onCancel` can
    // both fire, so the latch is what keeps the gauge balanced.
    AtomicBoolean finished = new AtomicBoolean();
    Runnable finishOnce =
        () -> {
          if (finished.compareAndSet(false, true)) {
            load.finished();
          }
        };

    ServerCall<ReqT, RespT> trackedCall =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            try {
              super.close(status, trailers);
            } finally {
              finishOnce.run();
            }
          }
        };

    ServerCall.Listener<ReqT> delegate;
    try {
      delegate = next.startCall(trackedCall, headers);
    } catch (RuntimeException | Error e) {
      finishOnce.run();
      throw e;
    }

    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onCancel() {
        try {
          super.onCancel();
        } finally {
          finishOnce.run();
        }
      }

      @Override
      public void onComplete() {
        try {
          super.onComplete();
        } finally {
          finishOnce.run();
        }
      }
    };
  }
}
