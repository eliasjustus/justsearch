package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SamplingParamsTest {

  @Test
  void twoArgConstructorSetsToolChoiceNull() {
    var params = new SamplingParams(0.5, 0.9);
    assertNull(params.toolChoice());
  }

  @Test
  void threeArgConstructorAcceptsValidToolChoices() {
    assertEquals("required", new SamplingParams(0.5, 0.9, "required").toolChoice());
    assertEquals("auto", new SamplingParams(0.5, 0.9, "auto").toolChoice());
    assertEquals("none", new SamplingParams(0.5, 0.9, "none").toolChoice());
    assertNull(new SamplingParams(0.5, 0.9, null).toolChoice());
  }

  @Test
  void invalidToolChoiceThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new SamplingParams(0.5, 0.9, "always"));
  }

  @Test
  void withToolChoiceReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var forced = base.withToolChoice("required");
    assertEquals("required", forced.toolChoice());
    assertEquals(base.temperature(), forced.temperature());
    assertEquals(base.topP(), forced.topP());
    assertNull(base.toolChoice(), "Original must be unchanged");
    assertNull(forced.grammar(), "Grammar must be null when not set");
  }

  @Test
  void presetsHaveNullToolChoice() {
    assertNull(SamplingParams.AGENT.toolChoice());
    assertNull(SamplingParams.THINKING.toolChoice());
    assertNull(SamplingParams.DETERMINISTIC.toolChoice());
    assertNull(SamplingParams.VDU.toolChoice());
  }

  @Test
  void withGrammarReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var constrained = base.withGrammar("root ::= \"ok\"");
    assertEquals("root ::= \"ok\"", constrained.grammar());
    assertEquals(base.temperature(), constrained.temperature());
    assertEquals(base.topP(), constrained.topP());
    assertNull(base.grammar(), "Original must be unchanged");
  }

  @Test
  void withToolChoicePreservesGrammar() {
    var constrained = SamplingParams.AGENT
        .withGrammar("root ::= \"ok\"")
        .withToolChoice("required");
    assertEquals("required", constrained.toolChoice());
    assertEquals("root ::= \"ok\"", constrained.grammar(), "Grammar must survive withToolChoice");
  }

  @Test
  void withGrammarPreservesToolChoice() {
    var forced = SamplingParams.AGENT.withToolChoice("required");
    var constrained = forced.withGrammar("root ::= \"ok\"");
    assertEquals("required", constrained.toolChoice(), "toolChoice must survive withGrammar");
    assertEquals("root ::= \"ok\"", constrained.grammar());
  }

  @Test
  void presetsHaveNullGrammar() {
    assertNull(SamplingParams.AGENT.grammar());
    assertNull(SamplingParams.THINKING.grammar());
    assertNull(SamplingParams.DETERMINISTIC.grammar());
    assertNull(SamplingParams.VDU.grammar());
  }

  @Test
  void withEnableThinkingReturnsCopyWithOverride() {
    var base = SamplingParams.AGENT;
    var suppressed = base.withEnableThinking(false);
    assertEquals(false, suppressed.enableThinking());
    assertEquals(base.temperature(), suppressed.temperature());
    assertEquals(base.topP(), suppressed.topP());
    assertNull(base.enableThinking(), "Original must be unchanged");
  }

  @Test
  void presetsHaveExpectedEnableThinking() {
    assertNull(SamplingParams.AGENT.enableThinking());
    assertNull(SamplingParams.THINKING.enableThinking());
    assertNull(SamplingParams.DETERMINISTIC.enableThinking());
    // VDU explicitly disables thinking — output goes to reasoning_content (lost) otherwise
    assertEquals(false, SamplingParams.VDU.enableThinking());
  }

  @Test
  void withEnableThinkingPreservesOtherFields() {
    var base = SamplingParams.AGENT
        .withToolChoice("required")
        .withGrammar("root ::= \"ok\"");
    var suppressed = base.withEnableThinking(false);
    assertEquals("required", suppressed.toolChoice(), "toolChoice must survive withEnableThinking");
    assertEquals("root ::= \"ok\"", suppressed.grammar(), "grammar must survive withEnableThinking");
    assertEquals(base.temperature(), suppressed.temperature());
    assertEquals(base.topP(), suppressed.topP());
  }
}
