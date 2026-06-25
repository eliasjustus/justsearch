package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.FileOperationsTool;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FileOperationsHandler} per tempdoc 429 §E.3 + §F.11 closure.
 *
 * <p>Critical: undo support must be preserved end-to-end. Per §E.3:
 * {@code execute(args)} returns {@link OperationResult} with {@code executionId} set
 * to the {@code batchId} from the underlying {@code FileOperationLog};
 * {@code undo(executionId)} delegates to the tool's undo method.
 */
final class FileOperationsHandlerTest {

  @Test
  void executePreservesBatchIdAsExecutionId() {
    FileOperationsTool tool = mock(FileOperationsTool.class);
    when(tool.execute("{\"operations\":[]}"))
        .thenReturn(OperationResult.success("batch executed", "batch-uuid-abc"));
    FileOperationsHandler handler = new FileOperationsHandler(tool);

    OperationResult result = handler.execute("{\"operations\":[]}");

    assertTrue(result.success());
    assertTrue(result.executionId().isPresent());
    assertEquals("batch-uuid-abc", result.executionId().get());
  }

  @Test
  void executeForwardsFailure() {
    FileOperationsTool tool = mock(FileOperationsTool.class);
    when(tool.execute("{}")).thenReturn(OperationResult.failure("no operations"));
    FileOperationsHandler handler = new FileOperationsHandler(tool);

    OperationResult result = handler.execute("{}");

    assertFalse(result.success());
    assertEquals("no operations", result.message());
  }

  @Test
  void undoDelegatesToWrappedTool() {
    FileOperationsTool tool = mock(FileOperationsTool.class);
    when(tool.undo("batch-uuid-abc"))
        .thenReturn(OperationResult.success("reversed 3 operations"));
    FileOperationsHandler handler = new FileOperationsHandler(tool);

    OperationResult result = handler.undo("batch-uuid-abc");

    assertTrue(result.success());
    assertEquals("reversed 3 operations", result.message());
  }

  @Test
  void undoForwardsFailure() {
    FileOperationsTool tool = mock(FileOperationsTool.class);
    when(tool.undo("missing-batch"))
        .thenReturn(OperationResult.failure("batch not found"));
    FileOperationsHandler handler = new FileOperationsHandler(tool);

    OperationResult result = handler.undo("missing-batch");

    assertFalse(result.success());
    assertEquals("batch not found", result.message());
  }
}
