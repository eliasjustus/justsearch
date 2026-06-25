package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractorContributionRegistry.ExtractorContribution;
import io.justsearch.indexerworker.extract.ExtractorContributionRegistry.ExtractorTrust;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the Worker-side extractor composer (tempdoc 560 §4.4/§6) — composition, dispatch, lifecycle, trust. */
class ExtractorContributionRegistryTest {

  /** A stub provider that tags its result with its own name, so dispatch can be asserted. */
  private static ContentExtractorProvider provider(String tag) {
    return new ContentExtractorProvider() {
      @Override
      public ExtractionResult extract(Path file) {
        return new ExtractionResult(tag, tag, "text/plain");
      }

      @Override
      public String detectMimeType(Path file) {
        return "mime/" + tag;
      }
    };
  }

  @Test
  void withCoreTikaDispatchesEveryFileToTheCatchAll() throws Exception {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    assertEquals(java.util.List.of("core.tika"), registry.ids());
    // Behavior preservation: every file routes to the single catch-all.
    assertEquals("tika", registry.extract(Path.of("a.pdf")).content());
    assertEquals("tika", registry.extract(Path.of("b.unknown")).content());
    assertEquals("mime/tika", registry.detectMimeType(Path.of("c.docx")));
  }

  @Test
  void dispatchPicksTheFirstMatchingContributionInInstallOrder() throws Exception {
    ExtractorContributionRegistry registry = new ExtractorContributionRegistry();
    // A specific .md handler installed before the catch-all wins for .md files.
    registry.install(
        new ExtractorContribution(
            "vendor.md",
            ExtractorTrust.TRUSTED,
            f -> f.toString().endsWith(".md"),
            provider("md")));
    registry.install(ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("tika")));

    assertEquals("md", registry.extract(Path.of("notes.md")).content());
    assertEquals("tika", registry.extract(Path.of("notes.pdf")).content());
  }

  @Test
  void installRejectsIdCollision() {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.install(
                    ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("dup"))));
    // Same id == same owner in the shared composer → Lifecycle rejects the re-install.
    assertTrue(ex.getMessage().contains("Already installed"));
  }

  @Test
  void trustSubstrateRejectsNonCoreContributorMintingCoreId() {
    ExtractorContributionRegistry registry = new ExtractorContributionRegistry();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.install(
                    ExtractorContribution.catchAll(
                        "core.tika", ExtractorTrust.UNTRUSTED, provider("evil"))));
    assertTrue(ex.getMessage().contains("Host owns truth"));
  }

  @Test
  void uninstallRevokesContribution() {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    assertTrue(registry.uninstall("core.tika"));
    assertFalse(registry.uninstall("core.tika"));
    // With nothing installed, dispatch has no contribution to route to.
    assertThrows(IllegalStateException.class, () -> registry.extract(Path.of("a.pdf")));
  }
}
