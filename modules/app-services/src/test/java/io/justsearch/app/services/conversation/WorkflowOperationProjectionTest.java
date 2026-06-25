package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.WorkflowRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tempdoc 560 WS5 — the workflow → agent-tool Operation projection + ref bijection. */
final class WorkflowOperationProjectionTest {

  @Test
  void projectsEachWorkflowToAnAgentFacingOperation() {
    List<Operation> ops = WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog());
    assertFalse(ops.isEmpty(), "the core workflow catalog projects at least one tool");

    Operation demo =
        ops.stream()
            .filter(op -> op.id().equals(new OperationRef("core.workflow-demo-compose")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("demo-compose workflow was not projected"));
    // Agent-only: never reaches the UI registry path; AGENT audience so the agent loop sees it.
    assertTrue(demo.executors().contains(ExecutorTag.AGENT));
    assertFalse(demo.executors().contains(ExecutorTag.UI), "workflow tools are agent-only");
    assertEquals(Audience.AGENT, demo.audience());
    // NonEmpty<ConsumerHook> carried over from the Workflow (no synthetic hook needed).
    assertFalse(demo.consumers().isEmpty());
  }

  @Test
  void opRefAndWorkflowRefAreABijection() {
    WorkflowRef workflow = new WorkflowRef("core.demo-compose");
    OperationRef op = WorkflowOperationProjection.opRefFor(workflow);
    assertEquals(new OperationRef("core.workflow-demo-compose"), op);
    assertEquals(workflow, WorkflowOperationProjection.workflowRefFor(op).orElseThrow());
  }

  @Test
  void workflowRefForReturnsEmptyOnANonWorkflowOperation() {
    assertTrue(
        WorkflowOperationProjection.workflowRefFor(new OperationRef("core.restart-worker")).isEmpty(),
        "a normal operation ref is not a projected workflow");
  }
}
