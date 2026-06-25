package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural defense for the validated-artifact boundary (tempdoc 410 §6 + §10).
 *
 * <p>Production code may only construct an {@link
 * io.justsearch.indexing.api.IndexApi.IndexDocument} via the {@code ValidatedExtractionArtifact}
 * overload — this prevents future contributors from accidentally re-introducing a public
 * overload that accepts unvalidated parser output. Test fixtures (and tests in the same module)
 * must route through {@link io.justsearch.indexerworker.fixtures.TestDocumentBuilder}, which wraps
 * an {@code ExtractionResult} into a default-policy validated artifact.
 */
final class IndexingDocumentOpsArchitectureTest {

  @Test
  @DisplayName("exactly one public buildDocument overload, and its first artifact-typed parameter is ValidatedExtractionArtifact")
  void onlyValidatedArtifactOverloadIsPublic() {
    Method[] publicBuildDocument =
        Arrays.stream(IndexingDocumentOps.class.getDeclaredMethods())
            .filter(m -> "buildDocument".equals(m.getName()))
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .toArray(Method[]::new);

    assertEquals(
        1,
        publicBuildDocument.length,
        () ->
            "Expected exactly one public buildDocument overload; found "
                + publicBuildDocument.length
                + ": "
                + Arrays.toString(publicBuildDocument));

    Method onlyPublic = publicBuildDocument[0];
    boolean hasArtifactParam =
        Arrays.stream(onlyPublic.getParameterTypes())
            .anyMatch(t -> t.equals(ValidatedExtractionArtifact.class));
    assertTrue(
        hasArtifactParam,
        () ->
            "The single public buildDocument overload must accept a ValidatedExtractionArtifact "
                + "(prevents bypassing artifact validation). Actual signature: "
                + onlyPublic);
  }

  @Test
  @DisplayName("no public buildDocument overload accepts a raw ExtractionResult")
  void noPublicOverloadAcceptsExtractionResult() {
    Method[] publicWithExtractionResult =
        Arrays.stream(IndexingDocumentOps.class.getDeclaredMethods())
            .filter(m -> "buildDocument".equals(m.getName()))
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(
                m ->
                    Arrays.stream(m.getParameterTypes())
                        .anyMatch(
                            t ->
                                t.getName()
                                    .equals(
                                        "io.justsearch.indexerworker.extract.ContentExtractor$ExtractionResult")))
            .toArray(Method[]::new);

    assertEquals(
        0,
        publicWithExtractionResult.length,
        () ->
            "No public buildDocument overload may accept ExtractionResult directly — "
                + "downstream code cannot bypass ValidatedExtractionArtifact. Found: "
                + Arrays.toString(publicWithExtractionResult));
  }
}
