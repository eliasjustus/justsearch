package io.justsearch.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.agent.api.AgentEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 565 §3.A / §6 / §12.16 — the grounded-answer authority's single-seam gate.
 *
 * <p>561 made a second interaction surface <em>unrepresentable by construction</em> via a build
 * gate. 565's content-tier analog — "a grounded answer is produced by one authority, not an
 * impoverished fork per mode" — was originally left to <em>discipline</em> (§11 named {@code
 * groundedDone} as "the single emission seam by construction" but added no gate). This test closes
 * that gap for the one invariant where a clean structural form exists: <strong>grounding is
 * ATTACHED to an agent answer in exactly one place — {@link AgentStepRunner}{@code .groundedDone()},
 * which derives the {@link AgentEvent.AgentSource} list from the session.</strong> Any second site
 * that emits a grounded {@link AgentEvent.AgentDone} fails this test, so the "second grounding
 * authority" the tempdoc forbids cannot land silently.
 *
 * <p><b>Mechanism.</b> {@code AgentDone} has THREE grounding-carrying constructors — the 7-arg
 * {@code (String,int,int,int,List,List,String)}, the 8-arg {@code (…,List,List,String,String)} that
 * tempdoc 859 §D added for the terminal disposition, and the canonical 9-arg {@code
 * (…,List,List,String,String,TraceContext)} — and two ungrounded ones (4-arg, 5-arg-with-trace).
 * A grounding-carrying constructor is identified by a {@code java.util.List} parameter in its
 * signature: the {@code sources}/{@code citations} lists. The rule walks compiled bytecode
 * (regex-free, rename-proof — the slice-execution test-precision discipline) and forbids
 * constructing a grounding-carrying {@code AgentDone} anywhere except the three legitimate sites:
 *
 * <ol>
 *   <li><b>{@code AgentStepRunner.groundedDone}</b> — THE attach seam (computes sources from the
 *       session, resolves inline citations via {@code AgentCitationResolver}).
 *   <li><b>{@code AgentEvent.AgentDone} itself</b> — the record's own convenience-constructor
 *       delegations (4/5/7/8-arg → canonical 9-arg) AND the {@code ofDisposition} static factory
 *       (859 §D), which builds an UNGROUNDED terminal — {@code List.of()} sources and citations,
 *       {@code SCORER_NONE} — for the max-iterations ceiling. It attaches nothing; it exists because
 *       that ceiling has to declare its disposition, and routing it through the canonical
 *       constructor from {@code AgentLoopService} would trip this rule for a reason that has nothing
 *       to do with grounding.
 *   <li><b>{@code AgentEventTracing}</b> — the uniform trace-decoration pass-through, which
 *       reconstructs every event type with a {@code TraceContext} added; it <em>copies</em> the
 *       source event's already-attached {@code sources()}/{@code citations()}, it does not attach
 *       new grounding.
 * </ol>
 *
 * <p><b>Second honest limit, from the {@code ofDisposition} route (859 §D review).</b> This rule is
 * {@code callConstructorWhere}: it sees CONSTRUCTOR calls. A static factory on the record is
 * therefore invisible to it twice over — the factory's own call to the canonical constructor is
 * exempted by the {@code AGENT_DONE} origin check, and the factory's CALLERS are not constructor
 * calls at all, so they are never examined. That is correct today, because {@code ofDisposition}
 * hardcodes empty grounding and cannot be handed any. It stops being correct the moment a factory on
 * this record accepts sources or citations as parameters: such a factory would be a second attach
 * site that this rule cannot see. If one is ever added, extend the predicate to method calls
 * targeting {@code AgentDone}'s grounding-bearing static factories.
 *
 * <p><b>Honest scope (the seam, not the runtime property).</b> This pins "no second site ATTACHES
 * grounding" — it does not assert the runtime property "every answer that had search hits IS
 * grounded" (that lives in {@code groundedDone}'s logic + the live end-to-end check). It is the
 * structural half of the guarantee: the differentiator's correctness cannot be forked.
 *
 * <p><b>Tempdoc 865 §7.8 / review A4 — the SECOND grounding-carrying record.</b> {@code AgentDone}
 * stopped being the only one. Under 865 §7.1 the mint's authoritative attach point is the per-call
 * stamp {@code OperationResult.withGrounding}, which carries the delta on {@code
 * tool_exec_completed.structuredData} — and it is a METHOD on a record with no {@code
 * java.util.List} constructor, so the discriminator above cannot see it at all. Left unextended,
 * this class would keep asserting it guards "the one attach site" while the site that now matters
 * had moved outside its reach: a green gate claiming a property it had stopped covering. The
 * discriminator is therefore widened to BOTH grounding-carrying records, and the stamp gets two
 * rules rather than one — uniqueness ({@link #groundingDeltaIsStampedOnlyAtTheDispatchSeam}) and
 * EXISTENCE ({@link #theDispatchSeamStillStampsTheGroundingDelta}). Uniqueness alone would be
 * decorative: deleting the stamp entirely satisfies "no second site" perfectly.
 */
final class AgentGroundingSeamAuditTest {

  /** {@code io.justsearch.agent.api.AgentEvent$AgentDone}. */
  private static final String AGENT_DONE = AgentEvent.AgentDone.class.getName();

  /**
   * A grounding-carrying {@code AgentDone} constructor — one whose signature takes the {@code
   * sources}/{@code citations} lists (the 7-arg and 8-arg overloads). The ungrounded 4-arg / 5-arg
   * overloads carry no {@code java.util.List} parameter. Matched on the target's full signature
   * ({@code …AgentDone.<init>(java.lang.String, int, int, int, java.util.List, …)}), so it is
   * agnostic to argument ORDER.
   *
   * <p><b>Honest limit (independent review):</b> this is a signature-<em>substring</em>
   * discriminator, not a semantic one. If {@code AgentDone}'s shape evolves it can misclassify:
   * a future <em>ungrounded</em> overload that adds an unrelated {@code java.util.List} parameter
   * would be a false positive (wrongly forced through {@code groundedDone}), and a future
   * <em>grounding</em> overload that carries the sources as a non-{@code List} type (array/wrapper)
   * would be a false negative (a second attach site could slip). Tighten this (e.g. match the exact
   * grounded-ctor signatures) if the {@code AgentDone} constructor set changes.
   */
  private static boolean isGroundingCarryingAgentDoneCtor(JavaConstructorCall call) {
    String target = call.getTarget().getFullName();
    return target.startsWith(AGENT_DONE + ".<init>(") && target.contains(List.class.getName());
  }

  /**
   * The three sites permitted to construct a grounding-carrying {@code AgentDone} (see class
   * javadoc): the attach seam {@code AgentStepRunner.groundedDone}, the record's own ctor
   * delegations and {@code ofDisposition} factory, and the trace-decoration pass-through.
   */
  private static boolean isPermittedGroundingCtorCaller(JavaConstructorCall call) {
    String origin = call.getOriginOwner().getName();
    String method = call.getOrigin().getName();
    // 1. THE attach seam.
    if (origin.equals(AgentStepRunner.class.getName()) && method.equals("groundedDone")) {
      return true;
    }
    // 2. AgentDone's own delegations: the convenience constructors (4/5/7/8-arg -> canonical 9-arg)
    // and the ungrounded `ofDisposition` factory. Both are intra-record shims, not attach sites.
    if (origin.equals(AGENT_DONE)) {
      return true;
    }
    // 3. The uniform trace-decoration pass-through (copies existing grounding, adds TraceContext).
    return origin.equals(AgentEventTracing.class.getName());
  }

  /**
   * Tempdoc 865 §7.1 — the delta stamp's fully-qualified target: {@code OperationResult
   * .withGrounding(java.util.List)}.
   */
  private static final String GROUNDING_STAMP =
      io.justsearch.agent.api.registry.OperationResult.class.getName() + ".withGrounding(";

  /**
   * Is this a call to the grounding-delta STAMP made from somewhere that is not the dispatch seam?
   *
   * <p>Takes plain strings rather than a {@code JavaMethodCall} so the discriminator itself can be
   * exercised by a negative fixture ({@link #theStampDiscriminatorFlagsANonSeamCaller}). An ArchUnit
   * rule cannot be handed a violating production class to prove it bites, and a predicate no test
   * has ever seen return {@code true} is a rule nobody has checked.
   *
   * @param originOwner the calling class's fully-qualified name
   * @param originMethod the calling method's name
   * @param targetFullName the called method's ArchUnit full name
   */
  static boolean isStampOutsideDispatchSeam(
      String originOwner, String originMethod, String targetFullName) {
    if (!targetFullName.startsWith(GROUNDING_STAMP)) {
      return false;
    }
    return !(originOwner.equals(AgentStepRunner.class.getName())
        && DISPATCH_SEAM_METHODS.contains(originMethod));
  }

  /**
   * The methods on {@link AgentStepRunner} that DISPATCH a tool call and record its result — the two
   * places 865 §7.1 mints and stamps a grounding delta.
   *
   * <p>They are two because the loop has two dispatch channels, not because the seam was widened for
   * convenience: {@code executeIteration} runs the synchronous executor (and already stamps {@code
   * withLineage} in the same block), and {@code handleVirtualToolCall} runs the {@code vop_*} channel
   * whose result arrives from the FE. Both call {@code recordExecution}, so both feed the
   * accumulator — and a site that feeds the accumulator without stamping breaks terminal equivalence
   * just as surely as a second mint would, by leaving a gap in the deltas that the terminal list does
   * not have. This rule cannot see that shape (it governs stamping, not recording), which is why the
   * property is also pinned by a test.
   */
  private static final List<String> DISPATCH_SEAM_METHODS =
      List.of("executeIteration", "handleVirtualToolCall");

  @Test
  void groundingIsAttachedOnlyInGroundedDone() {
    // Production classes under io.justsearch.agent (this pulls in app-agent AND the sibling
    // app-agent-api, which is intended: AgentDone's own delegating constructors live there and are
    // exempted by the AGENT_DONE origin check). Tests are excluded — they legitimately fabricate
    // AgentDone fixtures via the ungrounded constructors, which this rule never touches anyway.
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.justsearch.agent");

    ArchRule rule =
        noClasses()
            .should()
            .callConstructorWhere(
                new DescribedPredicate<JavaConstructorCall>(
                    "constructs a grounding-carrying AgentEvent.AgentDone outside the one attach"
                        + " seam (AgentStepRunner.groundedDone)") {
                  @Override
                  public boolean test(JavaConstructorCall call) {
                    return isGroundingCarryingAgentDoneCtor(call)
                        && !isPermittedGroundingCtorCaller(call);
                  }
                })
            .because(
                "Tempdoc 565 §3.A/§6 — grounding (the clickable local-passage citations that are the"
                    + " category differentiator) is ATTACHED to an agent answer in exactly one seam:"
                    + " AgentStepRunner.groundedDone(), which derives the sources from the session."
                    + " A second site emitting a grounded AgentDone would be the 'second"
                    + " grounded-answer authority' 565 makes unrepresentable — the content-tier"
                    + " analog of 561's interaction-surface gate. The only non-seam constructors of"
                    + " a grounded AgentDone are the record's own ctor delegations and"
                    + " AgentEventTracing's trace pass-through (which copies existing grounding, does"
                    + " not attach new grounding).");

    rule.check(classes);
  }

  /**
   * Tempdoc 865 §7.1 / review A4 — the delta stamp is the mint's authoritative attach point, and it
   * has exactly one caller for the same reason {@code groundedDone} does: a second site stamping a
   * grounding delta would be a second mint, free to apply a different dedup or a different order,
   * and the order is what {@code AgentSentenceCite.sourceIndex} resolves through.
   */
  @Test
  void groundingDeltaIsStampedOnlyAtTheDispatchSeam() {
    JavaClasses classes = productionAgentClasses();

    ArchRule rule =
        noClasses()
            .should()
            .callMethodWhere(
                new DescribedPredicate<JavaMethodCall>(
                    "stamps a grounding delta (OperationResult.withGrounding) outside"
                        + " AgentStepRunner's two tool-dispatch seams") {
                  @Override
                  public boolean test(JavaMethodCall call) {
                    return isStampOutsideDispatchSeam(
                        call.getOriginOwner().getName(),
                        call.getOrigin().getName(),
                        call.getTarget().getFullName());
                  }
                })
            .because(
                "Tempdoc 865 §7.1 — the grounding a tool call established is minted ONCE, by"
                    + " AgentSession's accumulator, and stamped ONCE, at the dispatch seam that"
                    + " already stamps OutputLineage. A second stamp site would be the same 'second"
                    + " grounding authority' 565 forbids at the terminal, one event earlier.");

    rule.check(classes);
  }

  /**
   * The half that makes the rule above non-decorative: deleting a stamp satisfies "no second site"
   * perfectly, and would silently restore the terminal-only minting 865 §7.1 retired — evidence
   * would go back to dying on every cancel, error and iteration ceiling, with every gate green.
   *
   * <p>Asserted per DISPATCH SEAM, not once for the class (865 PR-1 review F-2). A single
   * "somewhere in this class" check would stay green after the stamp was dropped from one of the two
   * channels, which is exactly the state the virtual-tool seam shipped in: recorded into the
   * accumulator, never stamped onto the wire.
   */
  @Test
  void everyDispatchSeamStillStampsTheGroundingDelta() {
    JavaClasses classes = productionAgentClasses();
    for (String seam : DISPATCH_SEAM_METHODS) {
      boolean stamped =
          classes.get(AgentStepRunner.class).getMethodCallsFromSelf().stream()
              .anyMatch(
                  call ->
                      call.getTarget().getFullName().startsWith(GROUNDING_STAMP)
                          && call.getOrigin().getName().equals(seam));
      org.junit.jupiter.api.Assertions.assertTrue(
          stamped,
          "AgentStepRunner."
              + seam
              + " records a tool execution, so it must also stamp that call's grounding delta via"
              + " OperationResult.withGrounding (tempdoc 865 §7.1). Recording without stamping puts"
              + " the source in the terminal list and NOT in the deltas, so the concatenation stops"
              + " equalling the terminal and every position after the gap shifts.");
    }
  }

  /**
   * The NEGATIVE fixture for {@link #isStampOutsideDispatchSeam}. The ArchUnit rule above cannot be
   * handed a violating production class, so the discriminator is exercised directly: a stamp made
   * from anywhere other than the seam must be flagged, and the seam itself must not be.
   */
  @Test
  void theStampDiscriminatorFlagsANonSeamCaller() {
    String stampTarget = GROUNDING_STAMP + "java.util.List)";
    org.junit.jupiter.api.Assertions.assertTrue(
        isStampOutsideDispatchSeam(
            "io.justsearch.agent.SomeSecondMintSite", "emitToolResult", stampTarget),
        "a grounding stamp from a class that is not the dispatch seam must be flagged");
    org.junit.jupiter.api.Assertions.assertTrue(
        isStampOutsideDispatchSeam(
            AgentStepRunner.class.getName(), "groundedDone", stampTarget),
        "a grounding stamp from the WRONG method of the right class must still be flagged");
    for (String seam : DISPATCH_SEAM_METHODS) {
      org.junit.jupiter.api.Assertions.assertFalse(
          isStampOutsideDispatchSeam(AgentStepRunner.class.getName(), seam, stampTarget),
          "a legitimate dispatch seam must not be flagged: " + seam);
    }
    org.junit.jupiter.api.Assertions.assertFalse(
        isStampOutsideDispatchSeam(
            "io.justsearch.agent.SomeSecondMintSite",
            "emitToolResult",
            "io.justsearch.agent.api.registry.OperationResult.withLineage(java.lang.Object)"),
        "an unrelated OperationResult method is not a grounding stamp");
  }

  /**
   * Production classes under {@code io.justsearch.agent} (this pulls in app-agent AND the sibling
   * app-agent-api, which is intended). Tests are excluded — they legitimately fabricate fixtures
   * through the very constructors and stamps these rules govern.
   */
  private static JavaClasses productionAgentClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.justsearch.agent");
  }
}
