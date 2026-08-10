package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 819 defect A: the empty-index fast path was structurally unreachable on a fresh profile.
 *
 * <p><b>Why this defect survived tempdoc 730's investigation, and what that means for this test.</b>
 * 730 looked for the same symptom with WORKER-LEVEL tests — index documents from inside the worker,
 * close, reopen — and concluded "no code-level defect reproduced". The trigger is not worker-level:
 * {@code KnowledgeServerBootstrap.tryIngestHelpFiles} lives in {@code app-services} and reaches the
 * Worker over gRPC FROM THE HEAD, in the window before {@code initDeferredModels} had constructed
 * the controller. Isolate the worker and the index under test is genuinely empty when
 * {@code refresh()} runs, the fast path fires correctly, and the defect vanishes. (Eval mode skips
 * help ingest precisely "so a fresh index truly starts empty" —
 * {@code KnowledgeServerBootstrap.java:572-578} — so a repro attempt under
 * {@code justsearch.eval.mode} removes the trigger by construction, too.)
 *
 * <p>So a test that indexes documents itself and then reopens passes BEFORE and AFTER the fix. This
 * class therefore covers the ORDER, in two complementary ways:
 *
 * <ul>
 *   <li>{@link #productionOrderKeepsAFreshProfileCompatibleAndStampsIt()} /
 *       {@link #preFixOrderResolvesBlockedLegacyOnAFreshProfile()} — the same fresh, empty index and
 *       the same five-document "help batch", differing ONLY in whether the controller resolved
 *       before or after that batch committed. These prove the order is what decides the outcome.
 *   <li>{@link #knowledgeServerResolvesTheControllerBeforeStartingTheIndexingLoop()} — pins the
 *       production call order in {@code KnowledgeServer.start()} itself, which is the thing that
 *       actually regressed. It is the only assertion here that goes red if someone moves the
 *       controller's construction back into {@code initDeferredModels()}, and
 *       {@link #theOrderCheckerBitesOnThePreFixArrangement()} proves that checker bites by running
 *       it against the pre-fix arrangement.
 * </ul>
 */
class EmbeddingCompatibilityBootOrderingTest {

  private static final String FP = "boot-ordering-embed-fp-sha256";
  private static final String SIBLING_FP = "boot-ordering-splade-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  /** The bundled help batch: `ssot/docs/help/*.md`, five files, ingested on first boot. */
  private static final int HELP_DOC_COUNT = 5;

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  // ---- Behavioural pair: the order decides the outcome ----

  @Test
  @DisplayName(
      "Production order (post-fix): the controller resolves against the still-empty index BEFORE "
          + "the help batch commits — COMPATIBLE / NEW_INDEX_NO_FINGERPRINT, and the batch's own "
          + "commit carries the fingerprint, so the next boot reads FINGERPRINT_MATCH")
  void productionOrderKeepsAFreshProfileCompatibleAndStampsIt() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-boot-order-production");

    // KnowledgeServer.start(): the runtime opens, appServices is constructed, and
    // initEmbeddingCompatibilityController() runs — all BEFORE appServices.startIndexingLoop().
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    try (var runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir)
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              runtime::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(runtime),
              () -> completedEmbeddingsOrThrow(runtime));
      ecc.refresh();
      fpSupplierRef.set(ecc::fingerprintToStamp);

      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "a genuinely empty index must take the fast path");
      assertEquals("NEW_INDEX_NO_FINGERPRINT", ecc.reasonCode());

      // ...and ONLY NOW does the Head's help batch arrive over gRPC and get committed by the
      // indexing loop. This is the commit that used to precede the controller entirely.
      indexHelpBatch(runtime);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "documents arriving after the controller resolved must not un-resolve it");
      assertEquals(
          HELP_DOC_COUNT,
          (int) docCountOrThrow(runtime),
          "sanity: the batch really is visible to the same live count refresh() reads — otherwise"
              + " the COMPATIBLE above would be a tautology of an index that never filled");
    }

    // Restart: the help batch's own commit carried the fingerprint, so this is a normal
    // FINGERPRINT_MATCH boot — no BLOCKED_LEGACY, no auto-rescue, no full re-embed.
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var reopened =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir)
            .open()) {
      assertEquals(
          HELP_DOC_COUNT,
          (int) docCountOrThrow(reopened),
          "sanity: the help batch really is on disk — the reopen below is not trivially empty");
      var freshEcc =
          new EmbeddingCompatibilityController(
              reopened::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(reopened),
              () -> completedEmbeddingsOrThrow(reopened));
      freshEcc.refresh();

      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, freshEcc.state());
      assertEquals("FINGERPRINT_MATCH", freshEcc.reasonCode());
      assertEquals(FP, freshEcc.storedFingerprint());
    }
  }

  @Test
  @DisplayName(
      "Pre-fix order (the defect): the same fresh profile, but the help batch commits BEFORE the "
          + "controller is constructed — refresh() sees docCount>0 with no stored fingerprint and "
          + "resolves BLOCKED_LEGACY, which the auto-rescue then converts into a full re-embed")
  void preFixOrderResolvesBlockedLegacyOnAFreshProfile() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-boot-order-prefix");

    AtomicReference<Supplier<Optional<String>>> fpSupplierRef =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    try (var runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir)
            .open()) {
      // The indexing loop started first (KnowledgeServer.java:702, synchronous) and the Head's
      // help batch landed while initDeferredModels was still composing ONNX sessions on the
      // ForkJoinPool thread. Nothing offers a fingerprint yet — fpSupplierRef is still empty.
      indexHelpBatch(runtime);
      runtime.commitOps().commitAndTrack();
      // Make the commit visible to the count/query reads, as the loop's NRT refresh cadence does
      // in production — the live log recorded `docCount=7` at the moment refresh() ran.
      runtime.commitOps().maybeRefreshBlocking();

      // Only now does initDeferredModels get around to the controller.
      var ecc =
          new EmbeddingCompatibilityController(
              runtime::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(runtime),
              () -> completedEmbeddingsOrThrow(runtime));
      ecc.refresh();
      fpSupplierRef.set(ecc::fingerprintToStamp);

      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "this is the defect: a brand-new profile resolving as a legacy index");
      assertEquals("LEGACY_INDEX_NO_FINGERPRINT", ecc.reasonCode());
      assertTrue(
          ecc.fingerprintToStamp().isEmpty(),
          "and BLOCKED_LEGACY withholds the stamp, so every later boot repeats this");
    }
  }

  // ---- Production call-site pin ----

  @Test
  @DisplayName(
      "KnowledgeServer.start() resolves the embedding compatibility controller BEFORE it starts "
          + "the indexing loop — the ordering invariant the behavioural pair above shows to matter")
  void knowledgeServerResolvesTheControllerBeforeStartingTheIndexingLoop() throws Exception {
    String source = Files.readString(knowledgeServerSource());
    String startMethod = bodyOfStartMethod(source);

    assertCallOrder(
        startMethod,
        "initEmbeddingCompatibilityController()",
        "appServices.startIndexingLoop()",
        "KnowledgeServer.start()");
  }

  @Test
  @DisplayName(
      "…and the order checker bites: run against the PRE-FIX arrangement (the controller built in "
          + "initDeferredModels, scheduled after the loop started) it must fail")
  void theOrderCheckerBitesOnThePreFixArrangement() {
    String preFix =
        """
        appServices.startIndexingLoop();
        startSentinelThread();
        deferredModelInit = CompletableFuture.supplyAsync(this::initDeferredModels);
        // ... inside initDeferredModels, much later:
        initEmbeddingCompatibilityController();
        """;

    AssertionError raised = null;
    try {
      assertCallOrder(
          preFix,
          "initEmbeddingCompatibilityController()",
          "appServices.startIndexingLoop()",
          "pre-fix arrangement");
    } catch (AssertionError e) {
      raised = e;
    }
    assertTrue(
        raised != null,
        "the ordering assertion must FAIL on the pre-fix arrangement — a check that cannot go red "
            + "is not a regression test");
  }

  // ---- helpers ----

  /**
   * Asserts {@code earlier} appears before {@code later}, and that BOTH appear — so a rename that
   * removes one of the call sites fails loudly instead of vacuously passing.
   */
  private static void assertCallOrder(
      String source, String earlier, String later, String context) {
    int earlierAt = source.indexOf(earlier);
    int laterAt = source.indexOf(later);
    if (earlierAt < 0) {
      throw new AssertionError(
          "`" + earlier + "` not found in " + context + " — the call site was renamed or removed;"
              + " update this test with the new name rather than deleting the invariant");
    }
    if (laterAt < 0) {
      throw new AssertionError("`" + later + "` not found in " + context);
    }
    if (earlierAt >= laterAt) {
      throw new AssertionError(
          "tempdoc 819 defect A regression in "
              + context
              + ": `"
              + earlier
              + "` must run BEFORE `"
              + later
              + "`. Anything the indexing loop commits before the embedding compatibility"
              + " controller resolves makes the empty-index fast path unreachable — on a fresh"
              + " profile the Head's bundled help batch is exactly such a commit, and the index"
              + " then resolves BLOCKED_LEGACY and re-embeds on every boot.");
    }
  }

  /** The body of {@code public void start()}, so a call elsewhere in the file cannot satisfy it. */
  private static String bodyOfStartMethod(String source) {
    int at = source.indexOf("public void start() throws IOException {");
    if (at < 0) {
      throw new AssertionError(
          "KnowledgeServer.start() signature not found — update this test's anchor");
    }
    int end = source.indexOf("\n  private static boolean isCorruptIndexCause(", at);
    if (end < 0) {
      throw new AssertionError(
          "could not bound KnowledgeServer.start() — update this test's end anchor");
    }
    return source.substring(at, end);
  }

  /**
   * Resolves {@code KnowledgeServer.java} independently of the test's working directory (Gradle
   * runs tests from the module dir; an IDE may use the repo root).
   */
  private static Path knowledgeServerSource() {
    String relative =
        "src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java";
    Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
      Path direct = candidate.resolve(relative);
      if (Files.isRegularFile(direct)) {
        return direct;
      }
      Path viaModule = candidate.resolve("modules/indexer-worker").resolve(relative);
      if (Files.isRegularFile(viaModule)) {
        return viaModule;
      }
    }
    throw new AssertionError("could not locate KnowledgeServer.java from user.dir=" + here);
  }

  private static void indexHelpBatch(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime) {
    for (int i = 0; i < HELP_DOC_COUNT; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "help-" + i,
                      SchemaFields.DOC_UID, "help-" + i + "#0")));
    }
  }

  private static long docCountOrThrow(io.justsearch.adapters.lucene.runtime.LuceneRuntime r) {
    try {
      return r.indexCountOps().docCountOrThrow();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int completedEmbeddingsOrThrow(
      io.justsearch.adapters.lucene.runtime.LuceneRuntime r) {
    try {
      return r.indexCountOps()
          .countByFieldOrThrow(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
