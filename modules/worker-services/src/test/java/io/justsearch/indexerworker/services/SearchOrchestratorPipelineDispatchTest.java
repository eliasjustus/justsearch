package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SearchOrchestrator pipeline dispatch helpers (256: Phase C).
 *
 * <p>Tests {@link SearchOrchestrator#modeToDefaultPipeline} (Worker-side preset expansion) and
 * {@link SearchOrchestrator#deriveEffectiveMode} (config → display name).
 */
@DisplayName("SearchOrchestrator pipeline dispatch helpers")
class SearchOrchestratorPipelineDispatchTest {

  @Nested
  @DisplayName("modeToDefaultPipeline")
  class ModeToDefaultPipeline {

    @Test
    @DisplayName("TEXT mode → sparse + lambdamart, no dense/splade")
    void textMode() {
      PipelineConfig cfg = SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_TEXT);
      assertTrue(cfg.getSparseEnabled());
      assertFalse(cfg.getDenseEnabled());
      assertFalse(cfg.getSpladeEnabled());
      assertTrue(cfg.getLambdamartEnabled());
    }

    @Test
    @DisplayName("VECTOR mode → dense only, no sparse/splade/lambdamart")
    void vectorMode() {
      PipelineConfig cfg = SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_VECTOR);
      assertFalse(cfg.getSparseEnabled());
      assertTrue(cfg.getDenseEnabled());
      assertFalse(cfg.getSpladeEnabled());
      assertFalse(cfg.getLambdamartEnabled());
    }

    @Test
    @DisplayName("HYBRID mode → sparse + dense + rrf + lambdamart")
    void hybridMode() {
      PipelineConfig cfg = SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_HYBRID);
      assertTrue(cfg.getSparseEnabled());
      assertTrue(cfg.getDenseEnabled());
      assertFalse(cfg.getSpladeEnabled());
      assertEquals("rrf", cfg.getFusionAlgorithm());
      assertTrue(cfg.getLambdamartEnabled());
    }

    @Test
    @DisplayName("SPLADE mode → splade only, no sparse/dense/lambdamart")
    void spladeMode() {
      PipelineConfig cfg = SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_SPLADE);
      assertFalse(cfg.getSparseEnabled());
      assertFalse(cfg.getDenseEnabled());
      assertTrue(cfg.getSpladeEnabled());
      assertFalse(cfg.getLambdamartEnabled());
    }

    @Test
    @DisplayName("unrecognized mode defaults to TEXT preset")
    void unrecognizedMode() {
      PipelineConfig cfg =
          SearchOrchestrator.modeToDefaultPipeline(SearchMode.UNRECOGNIZED);
      assertTrue(cfg.getSparseEnabled());
      assertFalse(cfg.getDenseEnabled());
      assertTrue(cfg.getLambdamartEnabled());
    }
  }

  @Nested
  @DisplayName("deriveEffectiveMode")
  class DeriveEffectiveMode {

    @Test
    @DisplayName("dense-only → VECTOR")
    void vector() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder().setDenseEnabled(true).build();
      assertEquals("VECTOR", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("sparse + dense → HYBRID")
    void hybrid() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder()
              .setSparseEnabled(true)
              .setDenseEnabled(true)
              .setFusionAlgorithm("rrf")
              .build();
      assertEquals("HYBRID", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("splade-only → SPLADE")
    void splade() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder().setSpladeEnabled(true).build();
      assertEquals("SPLADE", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("sparse-only → TEXT")
    void text() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder().setSparseEnabled(true).build();
      assertEquals("TEXT", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("empty config → TEXT (default)")
    void emptyConfig() {
      PipelineConfig cfg = PipelineConfig.getDefaultInstance();
      assertEquals("TEXT", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("sparse + splade → HYBRID")
    void sparsePlusSplade() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder()
              .setSparseEnabled(true)
              .setSpladeEnabled(true)
              .build();
      assertEquals("HYBRID", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("dense + splade → HYBRID")
    void densePlusSplade() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder()
              .setDenseEnabled(true)
              .setSpladeEnabled(true)
              .build();
      assertEquals("HYBRID", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("all three components → HYBRID")
    void allThree() {
      PipelineConfig cfg =
          PipelineConfig.newBuilder()
              .setSparseEnabled(true)
              .setDenseEnabled(true)
              .setSpladeEnabled(true)
              .build();
      assertEquals("HYBRID", SearchOrchestrator.deriveEffectiveMode(cfg));
    }

    @Test
    @DisplayName("round-trip: modeToDefaultPipeline → deriveEffectiveMode preserves mode name")
    void roundTrip() {
      assertEquals(
          "TEXT",
          SearchOrchestrator.deriveEffectiveMode(
              SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_TEXT)));
      assertEquals(
          "VECTOR",
          SearchOrchestrator.deriveEffectiveMode(
              SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_VECTOR)));
      assertEquals(
          "HYBRID",
          SearchOrchestrator.deriveEffectiveMode(
              SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_HYBRID)));
      assertEquals(
          "SPLADE",
          SearchOrchestrator.deriveEffectiveMode(
              SearchOrchestrator.modeToDefaultPipeline(SearchMode.SEARCH_MODE_SPLADE)));
    }
  }

  @Nested
  @DisplayName("deriveActualMode (256-E4: from actual execution state)")
  class DeriveActualMode {

    @Test
    @DisplayName("no legs ran → TEXT")
    void noLegs() {
      assertEquals("TEXT", SearchOrchestrator.deriveActualMode(false, false, false));
    }

    @Test
    @DisplayName("sparse only → TEXT")
    void sparseOnly() {
      assertEquals("TEXT", SearchOrchestrator.deriveActualMode(true, false, false));
    }

    @Test
    @DisplayName("dense only → VECTOR")
    void denseOnly() {
      assertEquals("VECTOR", SearchOrchestrator.deriveActualMode(false, true, false));
    }

    @Test
    @DisplayName("splade only → SPLADE")
    void spladeOnly() {
      assertEquals("SPLADE", SearchOrchestrator.deriveActualMode(false, false, true));
    }

    @Test
    @DisplayName("sparse + dense → HYBRID")
    void sparsePlusDense() {
      assertEquals("HYBRID", SearchOrchestrator.deriveActualMode(true, true, false));
    }

    @Test
    @DisplayName("sparse + splade → HYBRID")
    void sparsePlusSplade() {
      assertEquals("HYBRID", SearchOrchestrator.deriveActualMode(true, false, true));
    }

    @Test
    @DisplayName("dense + splade → HYBRID")
    void densePlusSplade() {
      assertEquals("HYBRID", SearchOrchestrator.deriveActualMode(false, true, true));
    }

    @Test
    @DisplayName("all three legs → HYBRID")
    void allThree() {
      assertEquals("HYBRID", SearchOrchestrator.deriveActualMode(true, true, true));
    }
  }
}
