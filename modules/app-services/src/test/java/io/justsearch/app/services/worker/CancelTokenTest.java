package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 / T3 — verifies the {@link CancelToken} substrate behavior. The end-to-end
 * "HTTP abort cancels in-flight gRPC scan" assertion lives in the SSE/integration test layer
 * (T6 + T4); this unit test pins the contract that CancelToken correctly delegates to the
 * underlying {@link Context.CancellableContext}.
 */
final class CancelTokenTest {

  @Test
  @DisplayName("Newly created token is not cancelled")
  void newTokenIsNotCancelled() {
    CancelToken token = new CancelToken();
    assertFalse(token.isCancelled(), "Fresh token must not report cancelled");
  }

  @Test
  @DisplayName("cancel() flips isCancelled() and is observable through the gRPC context")
  void cancelPropagatesToGrpcContext() {
    CancelToken token = new CancelToken();
    Context.CancellableContext ctx = token.context();
    assertFalse(ctx.isCancelled(), "Underlying gRPC context starts not-cancelled");
    token.cancel("test cancel");
    assertTrue(token.isCancelled(), "Token reports cancelled after cancel()");
    assertTrue(ctx.isCancelled(), "Underlying gRPC context reflects the cancel signal");
  }

  @Test
  @DisplayName("cancel() is idempotent — second call does not throw")
  void cancelIsIdempotent() {
    CancelToken token = new CancelToken();
    token.cancel();
    token.cancel("second cancel"); // must not throw
    assertTrue(token.isCancelled());
  }

  @Test
  @DisplayName("Cancel from one thread is visible to another thread (cross-thread propagation)")
  void crossThreadCancelIsVisible() throws InterruptedException {
    CancelToken token = new CancelToken();
    Thread canceller =
        new Thread(
            () -> {
              try {
                Thread.sleep(50);
                token.cancel("cross-thread");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    canceller.start();
    long start = System.nanoTime();
    while (!token.isCancelled() && (System.nanoTime() - start) < 2_000_000_000L) {
      Thread.sleep(10);
    }
    canceller.join();
    assertTrue(token.isCancelled(), "Token must observe cancel issued from another thread");
  }

  @Test
  @DisplayName("Context.run inside the token reflects the cancel via Context.current().isCancelled()")
  void contextRunSeesCancellationDuringExecution() throws Exception {
    CancelToken token = new CancelToken();
    boolean[] sawCancel = {false};
    // Issue cancel before running so the context is already cancelled when call() executes.
    token.cancel("pre-run");
    token
        .context()
        .call(
            () -> {
              sawCancel[0] = Context.current().isCancelled();
              return null;
            });
    assertTrue(sawCancel[0], "Context.current() inside the cancellable must report cancelled");
  }
}
