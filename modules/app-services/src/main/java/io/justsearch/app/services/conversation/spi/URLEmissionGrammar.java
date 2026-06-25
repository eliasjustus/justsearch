/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PromptContributor} that emits the URL grammar + catalog descriptor block for
 * {@code NavigateChatShape}.
 *
 * <p>Per tempdoc 487 §3.5 + §6 step 12: a single bundled contributor (user resolution
 * 2026-05-13 — kept as one piece rather than split into grammar + catalog descriptor).
 * Renders, at every request:
 *
 * <ul>
 *   <li>The URL grammar rules from §3.2.
 *   <li>A live catalog descriptor: every Op + Surface the LLM may emit a URL for,
 *       with a one-line per-entry descriptor (id, title, args, audience+confirm note).
 *   <li>A short style preamble.
 * </ul>
 *
 * <p>Catalog read at <em>contribute time</em>, not construction time — so
 * newly-registered Operations / Surfaces become emittable without a prompt-contributor
 * reconstruction. Cost: O(|ops| + |surfaces|) string-building per request; bounded
 * by the catalog size (currently ~28 ops + ~10 surfaces, per the 96.8% probe context).
 *
 * <p>Per tempdoc §3.3 MVP catalog filter: <strong>no audience pre-filter</strong>.
 * The trust lattice (slice 487 §4.4) gates by {@code SourceTier × RiskTier}; the
 * prompt exposes the full live catalog. Only the {@link ExecutorTag#UI} filter is
 * applied because URL emission is a UI-side ingress.
 *
 * <p>Priority 50 — catalog descriptor block, per the {@link PromptFragment}
 * convention (identity preambles use 0-9; catalog descriptors 50-69; dynamic
 * context 80-99).
 */
public final class URLEmissionGrammar implements PromptContributor {

  private static final Logger LOG = LoggerFactory.getLogger(URLEmissionGrammar.class);

  /** Stable id used by {@code ConversationShape.promptContributorIds}. */
  public static final String ID = "core.url-emission-grammar";

  private static final int PRIORITY = 50;

  /**
   * Preamble + grammar block. Mirrors the slice 487 §3.1 probe's render-prompt format
   * exactly so the substrate-driven path produces the same prompt format that the
   * 96.8% baseline was measured against. Deviations from this format produce
   * measurable shifts in model emission accuracy — Gate G2 protects against this.
   */
  private static final String PREAMBLE =
      "You are JustSearch's local assistant. The user's chrome will auto-route any"
          + " `justsearch://...` URLs you emit in your response. The user does not"
          + " click these URLs — the app routes them automatically. Destructive"
          + " actions surface a confirmation gate at the destination.\n\n"
          + "## URL grammar\n\n"
          + "```\n"
          + "justsearch://surface/<surfaceId>[?key=value&...]    # navigate to a view\n"
          + "justsearch://op/<opId>[?argName=value&...]          # perform an action\n"
          + "justsearch://query?q=<text>[&key=value&...]         # search and show results\n"
          + "justsearch://answer?q=<question>                    # cited one-turn answer\n"
          + "```\n\n"
          + "Use `query` to show the user search results for free-text (e.g. \"find my"
          + " notes on X\"); it opens the search view with that query. Use `answer` when the"
          + " user wants a direct cited answer to a question (e.g. \"what does my report say"
          + " about X?\") — it opens the chat view and answers from the indexed documents."
          + " Use `op/` only for the catalog actions listed below. "
          + "Use the IDs and arg names declared in the catalog below. Encode special"
          + " characters in args. Emit exactly one URL per action you want to perform,"
          + " in the order you want them to execute. URLs may appear in Markdown link"
          + " form `[label](justsearch://...)` or as bare URLs.\n\n"
          + "**Arg encoding rules:**\n"
          + "- For `array`-typed args (shown as `name:type[]`), repeat the key once per"
          + " value: `?ids=a&ids=b&ids=c`.\n"
          + "- For `enum(...)`-typed args, the value MUST be one of the listed options"
          + " exactly (case-sensitive).\n"
          + "- Omit optional args (shown with a trailing `?`) when their natural default"
          + " is what the user wants.\n\n"
          + "## Available actions\n\n";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final OperationCatalog operationCatalog;
  private final SurfaceCatalog surfaceCatalog;

  public URLEmissionGrammar(OperationCatalog operationCatalog, SurfaceCatalog surfaceCatalog) {
    this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog");
    this.surfaceCatalog = Objects.requireNonNull(surfaceCatalog, "surfaceCatalog");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Optional<PromptFragment> contribute(ConversationContext ctx) {
    StringBuilder sb = new StringBuilder(PREAMBLE);
    // Operations first — they're the broadest emission surface and the probe's
    // render-prompt convention puts them first. No audience filter (tempdoc §3.3
    // MVP catalog filter: expose the FULL live catalog; the trust lattice gates
    // by SourceTier × RiskTier rather than pre-filtering the prompt). UI executor
    // filter retained because URL emission is a UI-side ingress.
    for (Operation op : operationCatalog.definitions()) {
      if (!op.executors().contains(ExecutorTag.UI)) {
        continue;
      }
      renderOpDescriptor(sb, op);
    }

    sb.append("\n## Available surfaces\n\n");
    for (Surface surface : surfaceCatalog.definitions()) {
      renderSurfaceDescriptor(sb, surface);
    }

    return Optional.of(new PromptFragment(sb.toString(), PRIORITY));
  }

  /**
   * Render one Operation descriptor in the format the slice 487 §3.1 probe baseline
   * established. Surfaces enum values inline (the v2 renderer fix that took the
   * baseline from 87.1% to 96.8% — keeping this fix is load-bearing).
   */
  private void renderOpDescriptor(StringBuilder sb, Operation op) {
    sb.append("op:    ").append(op.id().value()).append("\n");
    sb.append("title: ").append(humanizeTitle(op.id().value())).append("\n");
    List<String> argLines = renderArgLines(op.id().value(), op.intf());
    if (!argLines.isEmpty()) {
      sb.append("args:  ").append(String.join(", ", argLines)).append("\n");
    }
    sb.append("note:  audience=")
        .append(op.audience())
        .append(", confirm=")
        .append(confirmFromOp(op))
        .append("\n\n");
  }

  private void renderSurfaceDescriptor(StringBuilder sb, Surface surface) {
    sb.append("surface: ").append(surface.id().value()).append("\n");
    sb.append("title:   ").append(humanizeTitle(surface.id().value())).append("\n\n");
  }

  /**
   * Inspect the Operation's input schema and render one arg-line entry per declared
   * property. Annotates with {@code [type]}, {@code [type[]]} for arrays,
   * {@code enum(a|b|c)} for enums, and a trailing {@code ?} for optional args.
   *
   * <p>Malformed schema is the catalog declaring corrupt data — log loudly and
   * continue without arg lines. The op still appears in the prompt; only the args
   * descriptor is missing for that one op.
   */
  private List<String> renderArgLines(String opId, Interface iface) {
    List<String> out = new ArrayList<>();
    JsonNode schema;
    try {
      schema = MAPPER.readTree(iface.inputs());
    } catch (JacksonException parseFailure) {
      LOG.warn(
          "URLEmissionGrammar: skipping arg-line render for op '{}' — input schema is"
              + " not valid JSON: {}",
          opId,
          parseFailure.getMessage());
      return out;
    }
    JsonNode props = schema.get("properties");
    if (props == null || !props.isObject()) {
      return out;
    }
    JsonNode required = schema.get("required");
    Set<String> requiredSet = new LinkedHashSet<>();
    if (required != null && required.isArray()) {
      for (JsonNode r : required) {
        requiredSet.add(r.asString());
      }
    }
    for (Map.Entry<String, JsonNode> entry :
        (Iterable<Map.Entry<String, JsonNode>>) props.properties()) {
      String name = entry.getKey();
      JsonNode propSchema = entry.getValue();
      String type = propSchema.has("type") ? propSchema.get("type").asString() : "string";
      boolean isArray = "array".equals(type);
      String elementType =
          isArray && propSchema.has("items") && propSchema.get("items").has("type")
              ? propSchema.get("items").get("type").asString()
              : type;
      StringBuilder line = new StringBuilder(name).append(":");
      if (propSchema.has("enum") && propSchema.get("enum").isArray()) {
        List<String> enumValues = new ArrayList<>();
        for (JsonNode ev : propSchema.get("enum")) {
          enumValues.add(ev.asString());
        }
        line.append("enum(").append(String.join("|", enumValues)).append(")");
      } else if (isArray) {
        line.append(elementType).append("[]");
      } else {
        line.append(type);
      }
      if (!requiredSet.contains(name)) {
        line.append("?");
      }
      out.add(line.toString());
    }
    return out;
  }

  private static String humanizeTitle(String id) {
    // Strip `core.` / `vendor.<x>.` prefix; replace dashes with spaces.
    String s = id;
    int firstDot = s.indexOf('.');
    if (firstDot >= 0 && firstDot + 1 < s.length()) {
      s = s.substring(firstDot + 1);
    }
    // For vendor namespace (vendor.<x>.<id>), strip one more segment.
    if (s.startsWith("vendor.")) {
      int second = s.indexOf('.', "vendor.".length());
      if (second >= 0 && second + 1 < s.length()) {
        s = s.substring(second + 1);
      }
    }
    return s.replace('-', ' ').replace('.', ' ');
  }

  /**
   * Render the operation's {@link ConfirmStrategy} as the lowercase form the probe
   * baseline expects. Exhaustive pattern-matching switch over the sealed permits.
   */
  private static String confirmFromOp(Operation op) {
    return switch (op.policy().confirm()) {
      case ConfirmStrategy.None ignored -> "none";
      case ConfirmStrategy.Inline ignored -> "inline";
      case ConfirmStrategy.Typed ignored -> "typed";
    };
  }
}
