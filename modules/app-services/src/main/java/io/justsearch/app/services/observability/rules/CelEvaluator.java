/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded CEL evaluator for operational-signal rule predicates and magnitudes.
 *
 * <p>Per tempdoc 430 §A.4: registers a {@code signals: map<string, Signal>} variable plus
 * member overloads on {@code Signal} ({@code latest()}, {@code window(string)}) and
 * {@code WindowedView} ({@code avg()/min()/max()/rate()/count()}). CEL expressions can read
 * archived metric values via {@code signals['<metric.name>'].latest()} and time-aggregate via
 * {@code signals['<metric.name>'].window('5m').avg()}.
 *
 * <p>Per rev 3.11 §B.X.5: when a metric is missing from the RRD store, the underlying Java
 * code throws {@link MissingMetricException}. CEL wraps it in a {@link CelEvaluationException};
 * the rule runner catches it and treats the tick as predicate-false. No stale-data flapping.
 *
 * <p>{@link CelRuntime.Program} is documented as immutable / thread-safe per cel-java; we cache
 * compiled programs per (rule + expression key) to avoid per-tick compile cost.
 */
public final class CelEvaluator {

  private static final Logger log = LoggerFactory.getLogger(CelEvaluator.class);

  private static final OpaqueType SIGNAL_TYPE = OpaqueType.create("Signal");
  private static final OpaqueType WINDOWED_VIEW_TYPE = OpaqueType.create("WindowedView");
  private static final MapType SIGNALS_MAP_TYPE =
      MapType.create(SimpleType.STRING, SIGNAL_TYPE);

  /** The variable name used by CEL expressions to access the signals map. */
  public static final String SIGNALS_VAR = "signals";

  private final CelCompiler compiler;
  private final CelRuntime runtime;
  private final ConcurrentMap<String, CelRuntime.Program> programCache = new ConcurrentHashMap<>();

  public CelEvaluator() {
    this.compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar(SIGNALS_VAR, SIGNALS_MAP_TYPE)
            .addFunctionDeclarations(
                // Signal.latest() -> double
                CelFunctionDecl.newFunctionDeclaration(
                    "latest",
                    CelOverloadDecl.newMemberOverload(
                        "signal_latest", SimpleType.DOUBLE, SIGNAL_TYPE)),
                // Signal.window(duration: string) -> WindowedView
                CelFunctionDecl.newFunctionDeclaration(
                    "window",
                    CelOverloadDecl.newMemberOverload(
                        "signal_window_string",
                        WINDOWED_VIEW_TYPE,
                        SIGNAL_TYPE,
                        SimpleType.STRING)),
                // WindowedView accessors
                CelFunctionDecl.newFunctionDeclaration(
                    "avg",
                    CelOverloadDecl.newMemberOverload(
                        "window_avg", SimpleType.DOUBLE, WINDOWED_VIEW_TYPE)),
                CelFunctionDecl.newFunctionDeclaration(
                    "min",
                    CelOverloadDecl.newMemberOverload(
                        "window_min", SimpleType.DOUBLE, WINDOWED_VIEW_TYPE)),
                CelFunctionDecl.newFunctionDeclaration(
                    "max",
                    CelOverloadDecl.newMemberOverload(
                        "window_max", SimpleType.DOUBLE, WINDOWED_VIEW_TYPE)),
                CelFunctionDecl.newFunctionDeclaration(
                    "rate",
                    CelOverloadDecl.newMemberOverload(
                        "window_rate", SimpleType.DOUBLE, WINDOWED_VIEW_TYPE)),
                CelFunctionDecl.newFunctionDeclaration(
                    "count",
                    CelOverloadDecl.newMemberOverload(
                        "window_count", SimpleType.INT, WINDOWED_VIEW_TYPE)))
            .build();

    this.runtime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "signal_latest", Signal.class, (Signal s) -> s.latest()),
                CelFunctionBinding.from(
                    "signal_window_string",
                    Signal.class,
                    String.class,
                    (Signal s, String dur) -> s.window(dur)),
                CelFunctionBinding.from(
                    "window_avg", WindowedView.class, (WindowedView w) -> w.avg()),
                CelFunctionBinding.from(
                    "window_min", WindowedView.class, (WindowedView w) -> w.min()),
                CelFunctionBinding.from(
                    "window_max", WindowedView.class, (WindowedView w) -> w.max()),
                CelFunctionBinding.from(
                    "window_rate", WindowedView.class, (WindowedView w) -> w.rate()),
                CelFunctionBinding.from(
                    "window_count", WindowedView.class, (WindowedView w) -> w.count()))
            .build();
  }

  /**
   * Compiles a CEL expression into a cached {@link CelRuntime.Program}. Compilation errors
   * (parse/check failures) throw {@link IllegalArgumentException} — these indicate a malformed
   * rule file (build-time bug), not a runtime condition.
   *
   * <p>{@code cacheKey} should uniquely identify the expression (e.g.,
   * {@code "rule:memory-pressure:expr_cel"} or {@code "rule:memory-pressure:magnitudes:used_bytes"}).
   * Identical {@code expression} strings under different cache keys compile to equivalent
   * Programs but the cache is keyed on logical identity for clarity.
   */
  public CelRuntime.Program compile(String cacheKey, String expression) {
    Objects.requireNonNull(cacheKey, "cacheKey");
    Objects.requireNonNull(expression, "expression");
    return programCache.computeIfAbsent(cacheKey, k -> compileFresh(k, expression));
  }

  private CelRuntime.Program compileFresh(String cacheKey, String expression) {
    try {
      CelValidationResult validation = compiler.compile(expression);
      CelAbstractSyntaxTree ast = validation.getAst();
      return runtime.createProgram(ast);
    } catch (CelValidationException e) {
      throw new IllegalArgumentException(
          "CelEvaluator: failed to compile expression for "
              + cacheKey
              + ": "
              + e.getMessage(),
          e);
    } catch (CelEvaluationException e) {
      // createProgram can throw this for static analysis failures.
      throw new IllegalArgumentException(
          "CelEvaluator: failed to build program for " + cacheKey + ": " + e.getMessage(), e);
    }
  }

  /**
   * Evaluates {@code program} against {@code signals}. Returns the resulting Java object
   * (typically Boolean for predicates, Double or Long for magnitudes).
   *
   * @throws CelEvaluationException if evaluation fails (including wrapped
   *     {@link MissingMetricException} from underlying Signal accessors)
   */
  public Object evaluate(CelRuntime.Program program, Map<String, Signal> signals)
      throws CelEvaluationException {
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(signals, "signals");
    Map<String, Object> bindings = new HashMap<>();
    bindings.put(SIGNALS_VAR, new LinkedHashMap<>(signals));
    return program.eval(bindings);
  }

  /**
   * Evaluates {@code program} as a predicate, returning a closed {@link PredicateOutcome} tri-state.
   * A {@link MissingMetricException} yields {@link PredicateOutcome.Indeterminate} (the metric has
   * no samples — the predicate <em>could not be evaluated</em>), NOT {@code false}: tempdoc 600
   * Design B requires "cannot evaluate" to be distinct from "evaluated false (healthy)" so a blind
   * rule cannot masquerade as healthy. Other CEL exceptions propagate as {@link IllegalStateException}
   * (genuine evaluator bugs).
   *
   * <p>Logs a one-time WARN per missing-metric (per rev §B.S #4 dedup pattern).
   */
  public PredicateOutcome evaluatePredicate(
      CelRuntime.Program program, Map<String, Signal> signals, String ruleNameForLog) {
    try {
      Object result = evaluate(program, signals);
      if (!(result instanceof Boolean b)) {
        throw new IllegalStateException(
            "CEL predicate for rule '"
                + ruleNameForLog
                + "' returned non-boolean: "
                + result);
      }
      return PredicateOutcome.evaluated(b);
    } catch (CelEvaluationException e) {
      Throwable cause = e.getCause();
      if (cause instanceof MissingMetricException missing) {
        if (warnedMissing.add(ruleNameForLog + ": " + missing.getMessage())) {
          log.warn(
              "CelEvaluator: rule '{}' predicate missing metric: {}",
              ruleNameForLog,
              missing.getMessage());
        }
        return PredicateOutcome.indeterminate(missing.getMessage());
      }
      // Re-raise as an unchecked: this is unexpected.
      throw new IllegalStateException(
          "CEL predicate for rule '" + ruleNameForLog + "' threw: " + e.getMessage(), e);
    }
  }

  /** WARN-once dedup set for missing-metric occurrences. Bounded by {rule, metric} pair. */
  private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

  /** Returns the CEL type for the {@code signals} variable, exposed for tests. */
  public static CelType signalsMapType() {
    return SIGNALS_MAP_TYPE;
  }
}
