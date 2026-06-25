package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.WorkerService;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RestartWorkerHandler}'s real implementation (slice 3a-1-2 closure).
 *
 * <p>Replaces the prior stub-behavior tests with delegation tests against a
 * lambda-implemented {@link WorkerService}.
 */
final class RestartWorkerHandlerTest {

  @Test
  void executeReturnsFailureWhenWorkerUnavailable() {
    // Default constructor uses WorkerService.unavailable() → available()
    // returns false → handler returns failure.
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Worker service unavailable"));
  }

  @Test
  void executeReturnsSuccessWithPortWhenWorkerRestarts() {
    RestartWorkerHandler handler =
        new RestartWorkerHandler(
            () ->
                new WorkerService() {
                  @Override
                  public boolean available() {
                    return true;
                  }

                  @Override
                  public long workerPid() {
                    return 12345L;
                  }

                  @Override
                  public int restart() {
                    return 9001;
                  }
                });
    OperationResult result = handler.execute("{}");
    assertTrue(result.success());
    assertTrue(result.message().contains("9001"));
    assertEquals(9001, result.structuredData().get("port"));
  }

  @Test
  void executeReturnsFailureWhenRestartThrows() {
    RestartWorkerHandler handler =
        new RestartWorkerHandler(
            () ->
                new WorkerService() {
                  @Override
                  public boolean available() {
                    return true;
                  }

                  @Override
                  public long workerPid() {
                    return 0L;
                  }

                  @Override
                  public int restart() throws Exception {
                    throw new java.io.IOException("port already in use");
                  }
                });
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("port already in use"));
  }

  @Test
  void executeIgnoresArguments() {
    // Restart-worker takes no arguments per CoreOperationCatalog.intf
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult one = handler.execute("{}");
    OperationResult two = handler.execute("{\"foo\":\"bar\"}");
    assertEquals(one.success(), two.success());
  }

  @Test
  void executeHasNoExecutionId() {
    // Restart is not undoable.
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult result = handler.execute("{}");
    assertTrue(result.executionId().isEmpty());
  }
}
