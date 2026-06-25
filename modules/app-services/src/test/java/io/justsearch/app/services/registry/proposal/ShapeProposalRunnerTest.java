package io.justsearch.app.services.registry.proposal;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parameterized harness over the three registered {@link ShapeProposal} instances per
 * tempdoc 429 §A.6 + §"Acceptance criteria".
 *
 * <p>Each primitive (Operation, Resource, Prompt) ships a {@code ShapeProposal} via its
 * Provider class. The harness asserts every proposal validates without ERROR findings.
 */
final class ShapeProposalRunnerTest {

  @ParameterizedTest
  @MethodSource("registeredShapeProposals")
  void shapeProposalIsConsistent(ShapeProposal proposal) {
    ShapeProposalValidator.assertNoErrors(proposal);
  }

  static Stream<ShapeProposal> registeredShapeProposals() {
    return Stream.of(
        OperationShapeProposalProvider.PROPOSAL,
        ResourceShapeProposalProvider.PROPOSAL,
        PromptShapeProposalProvider.PROPOSAL);
  }
}
