/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;
import java.util.Set;

/**
 * Per-request sampling parameters for LLM completions.
 *
 * <p>Presets are tuned for different workloads:
 *
 * <ul>
 *   <li>{@link #THINKING} — Higher temperature for chain-of-thought reasoning (Qwen3 recommended)
 *   <li>{@link #DETERMINISTIC} — Low temperature for structured/factual output
 *   <li>{@link #VDU} — Slightly creative for document understanding extraction
 *   <li>{@link #AGENT} — Balanced for agent tool calling with multi-step reasoning
 * </ul>
 *
 * @param temperature sampling temperature (0.0–2.0)
 * @param topP nucleus sampling probability mass (0.0–1.0)
 * @param toolChoice tool_choice parameter for llama-server; null = server default ("auto")
 * @param grammar GBNF grammar string for output-format constraints; null = no grammar
 * @param enableThinking if non-null, emits {@code chat_template_kwargs={"enable_thinking":<value>}};
 *     use {@code false} on mechanical turns (E0a, DECIDING) to suppress thinking-prompt formatting
 * @param responseFormat JSON schema for llama-server's response_format constraint (363). Mutually
 *     exclusive with grammar — if both are set, response_format takes precedence. Pass as {@code
 *     Map.of("type", "json_object", "schema", schemaMap)}.
 */
public record SamplingParams(
    double temperature,
    double topP,
    String toolChoice,
    String grammar,
    Boolean enableThinking,
    Map<String, Object> responseFormat) {

  private static final Set<String> VALID_TOOL_CHOICES = Set.of("auto", "required", "none");

  /** Validates that all fields are within expected ranges. */
  public SamplingParams {
    if (temperature < 0.0 || temperature > 2.0) {
      throw new IllegalArgumentException("temperature must be 0.0–2.0, got " + temperature);
    }
    if (topP < 0.0 || topP > 1.0) {
      throw new IllegalArgumentException("topP must be 0.0–1.0, got " + topP);
    }
    if (toolChoice != null && !VALID_TOOL_CHOICES.contains(toolChoice)) {
      throw new IllegalArgumentException(
          "toolChoice must be null, \"auto\", \"required\", or \"none\", got \"" + toolChoice + "\"");
    }
  }

  /** Backward-compatible constructor: enableThinking and responseFormat default to null. */
  public SamplingParams(
      double temperature, double topP, String toolChoice, String grammar, Boolean enableThinking) {
    this(temperature, topP, toolChoice, grammar, enableThinking, null);
  }

  /** Backward-compatible constructor: enableThinking defaults to null. */
  public SamplingParams(double temperature, double topP, String toolChoice, String grammar) {
    this(temperature, topP, toolChoice, grammar, null);
  }

  /** Backward-compatible constructor: toolChoice and grammar default to null. */
  public SamplingParams(double temperature, double topP, String toolChoice) {
    this(temperature, topP, toolChoice, null);
  }

  /** Backward-compatible constructor: toolChoice defaults to null (= server default "auto"). */
  public SamplingParams(double temperature, double topP) {
    this(temperature, topP, null);
  }

  /** Returns a copy with the given toolChoice override, preserving all other fields. */
  public SamplingParams withToolChoice(String toolChoice) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat);
  }

  /** Returns a copy with the given grammar override, preserving all other fields. */
  public SamplingParams withGrammar(String grammar) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat);
  }

  /**
   * Returns a copy with the given enableThinking override, preserving all other fields.
   *
   * <p>Use {@code false} on mechanical turns (E0a, DECIDING) to suppress thinking-prompt
   * formatting. Use {@code null} to let the server apply its default.
   */
  public SamplingParams withEnableThinking(Boolean enableThinking) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat);
  }

  /** Returns a copy with the given responseFormat, preserving all other fields. (363) */
  public SamplingParams withResponseFormat(Map<String, Object> responseFormat) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat);
  }

  /** Recommended for thinking/reasoning models (Qwen3.5 defaults: temp=0.7, top_p=0.8). */
  public static final SamplingParams THINKING = new SamplingParams(0.7, 0.8);

  /** Near-deterministic output for structured extraction and factual responses. */
  public static final SamplingParams DETERMINISTIC = new SamplingParams(0.1, 0.9);

  /**
   * Deterministic preset for vision document understanding (VDU).
   *
   * <p>Temperature 0 for deterministic OCR output. Thinking disabled because VLM output goes to
   * {@code reasoning_content} (lost) instead of {@code content} when thinking is enabled.
   */
  public static final SamplingParams VDU = new SamplingParams(0.0, 0.9, null, null, false);

  /**
   * Preset for agent tool calling, tuned to Qwen3.5 recommended sampling.
   *
   * <p>temp=0.7, top_p=0.8 per Qwen3.5 model card recommendations. Higher temperature improves
   * PRIMARY's text response quality; top_p=0.8 is tighter than Qwen3's 0.95, reducing low-quality
   * tail tokens. For tool-call turns, grammar constraints override sampling anyway.
   */
  public static final SamplingParams AGENT = new SamplingParams(0.7, 0.8);
}
