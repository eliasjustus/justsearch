package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the Workflow Manifest tier (§534) + the every-node-delegates validator (tempdoc 560 §4.4). */
class WorkflowTest {

  private static Workflow workflow(List<WorkflowNode> nodes) {
    return new Workflow(
        new WorkflowRef("core.triage"),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Provenance.core("1.0"),
        Audience.AGENT,
        nodes,
        List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
  }

  @Test
  void nodesAreSealedToDelegatingKinds() {
    // The sealed WorkflowNode makes a non-delegating node unrepresentable — only LLM/tool/gate exist.
    assertEquals(3, WorkflowNode.class.getPermittedSubclasses().length);
  }

  @Test
  void workflowComposesNodesWithSharedAxes() {
    Workflow wf =
        workflow(
            List.of(
                new WorkflowNode.LlmStep("plan", new ConversationShapeRef("core.free-chat")),
                new WorkflowNode.ToolStep("search", new OperationRef("core.search-index")),
                new WorkflowNode.GateStep("confirm", new ConfirmStrategy.Typed(new I18nKey("k.confirm")))));
    assertEquals(3, wf.nodes().size());
    assertEquals(Audience.AGENT, wf.audience());
    assertFalse(wf.consumers().isEmpty());
  }

  @Test
  void validatorPassesWhenEveryNodeDelegatesToAnExistingSubstrate() {
    Workflow wf =
        workflow(
            List.of(
                new WorkflowNode.LlmStep("plan", new ConversationShapeRef("core.free-chat")),
                new WorkflowNode.ToolStep("search", new OperationRef("core.search-index")),
                new WorkflowNode.GateStep("confirm", ConfirmStrategy.Inline.INSTANCE)));
    List<String> errors =
        WorkflowValidator.validate(wf, Set.of("core.free-chat"), Set.of("core.search-index"));
    assertTrue(errors.isEmpty(), errors.toString());
    assertTrue(WorkflowValidator.isValid(wf, Set.of("core.free-chat"), Set.of("core.search-index")));
  }

  @Test
  void validatorRejectsDanglingDelegation() {
    Workflow wf =
        workflow(
            List.of(
                new WorkflowNode.LlmStep("plan", new ConversationShapeRef("vendor.x.ghost-shape")),
                new WorkflowNode.ToolStep("run", new OperationRef("vendor.x.ghost-op"))));
    List<String> errors = WorkflowValidator.validate(wf, Set.of("core.free-chat"), Set.of("core.search-index"));
    assertEquals(2, errors.size(), errors.toString());
    assertFalse(WorkflowValidator.isValid(wf, Set.of(), Set.of()));
  }

  @Test
  void workflowRefIsManifestTierNotPrimitive() {
    assertFalse(RegistryEntry.class.isAssignableFrom(Workflow.class));
    RegistryRef<Workflow> ref = new WorkflowRef("core.triage");
    assertEquals("core.triage", ref.value());
  }
}
