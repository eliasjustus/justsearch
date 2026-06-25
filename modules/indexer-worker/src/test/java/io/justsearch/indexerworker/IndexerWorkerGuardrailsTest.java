package io.justsearch.indexerworker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.indexerworker", importOptions = ImportOption.DoNotIncludeTests.class)
class IndexerWorkerGuardrailsTest {
  // 371: DevReloadManager and IndexStatusOps are exempt — DevReloadManager is dev-only code
  // that updates the build stamp; IndexStatusOps reads it via EnvRegistry indirection.
  // 374 alpha.25 R13-A: GrpcHealthService exempt — `JUSTSEARCH_WORKER_HEALTH_SYNTHETIC_DELAY_MS`
  // is a deliberate test-only synthetic-delay hook for retry-loop verification (default 0/off).
  // Tempdoc 607 OCR: TikaOcrRuntime exempt — its env/sysprop reads are native-resource DISCOVERY
  // (locating the bundled/system Tesseract executable + tessdata via PATH/APPDATA/LOCALAPPDATA/
  // TESSERACT_PATH/TESSDATA_PREFIX, and os.name for the platform binary name). This is runtime
  // environment probing of the worker's own filesystem, not user configuration — the same class of
  // bootstrapping/infra discovery as the three exemptions above, and it cannot be meaningfully
  // routed through the head's config snapshot (the head does not know the worker host's FS layout).
  @ArchTest
  static final ArchRule indexerWorkerMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.indexerworker.server.DevReloadManager")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.indexerworker.services.IndexStatusOps")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.indexerworker.services.GrpcHealthService")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.indexerworker.extract.TikaOcrRuntime")
          .should()
          .callMethod(System.class, "getenv")
          .orShould()
          .callMethod(System.class, "getenv", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "setProperty", String.class, String.class);

  @ArchTest
  static final ArchRule indexerWorkerMustNotDependOnTestSupport =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.testsupport..");

  @ArchTest
  static final ArchRule mmfMappedByteBufferMustBeIsolatedToMmfWorkerSignalBus =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.indexerworker.coordination.MmfWorkerSignalBus")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.nio.MappedByteBuffer");

  // ============================================================
  // Tempdoc 517 — capture-once enforcement on the search-execution package layout.
  //
  // The SearchInputCapture class in services.input.* is the only class permitted
  // to depend on the IO primitives (CommitOps, IndexCountOps, encoders, the
  // EmbeddingProvider). The planner (services.plan.*) and response-builder
  // (services.respond.*) must depend only on captured values flowing through
  // SearchInputs. This makes the capture-once invariant (cluster snapshot,
  // corpus profile, encode result) a compile-time check rather than a comment.
  //
  // SearchExecutor (services.execute.*) is permitted to depend on these types
  // because it runs the actual retrieval IO. The point of the rule is to keep
  // planner + response-builder pure relative to the captured SearchInputs.
  // ============================================================

  @ArchTest
  static final ArchRule plannerMustNotDependOnIoPrimitives =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker.services.plan..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("CommitOps")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("IndexCountOps")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeIdfQueryEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("BgeM3Encoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("EmbeddingProvider");

  @ArchTest
  static final ArchRule responseBuilderMustNotDependOnEncoders =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker.services.respond..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("SpladeEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeIdfQueryEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("BgeM3Encoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("EmbeddingProvider")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("CommitOps");

  // NOTE — a third tempdoc-517 rule ("encoder imports confined to input-capture")
  // was considered but dropped. Peer classes outside the search-execution
  // scope (CitationMatchOps for citation embeddings; GrpcHealthService for
  // readiness probes; RagContextOps for RAG embeddings; GrpcSearchService for
  // gRPC-level wiring) legitimately depend on EmbeddingProvider/SPLADE/BGE-M3
  // for their own concerns. The narrower rules above (planner / responder no IO)
  // enforce what tempdoc 517's design actually requires — namely that the
  // captured-once invariant holds for SearchInputs consumers — without
  // over-reaching into peer-class concerns.
}
