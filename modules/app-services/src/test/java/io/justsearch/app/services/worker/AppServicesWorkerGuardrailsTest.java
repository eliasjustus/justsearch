package io.justsearch.app.services.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.app.services", importOptions = ImportOption.DoNotIncludeTests.class)
class AppServicesWorkerGuardrailsTest {
  /**
   * Workstream B guardrail: prevent ad-hoc env/sysprop access outside of configuration + explicit entrypoints.
   *
   * <p>NOTE: This rule includes a temporary allowlist for legacy callsites. Prefer migrating these to
   * {@code modules/configuration} (e.g., EnvRegistry / PlatformPaths / RepoRootLocator) over adding new allowlist entries.
   */
  @ArchTest
  static final ArchRule appServicesMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          // Allowlisted: bootstrap/entrypoint + legacy config surfaces (to be reduced over time).
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.HeadAssembly")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.inference.InferenceLifecycleManager")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.OnlineAiServiceImpl")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.LocalAiTranslatorService")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.worker.WorkerSpawner")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.worker.KnowledgeServerConfig")
          // Tempdoc 519 §9 Block B3.0.b: ExcludeGlobs reads System.getProperty("os.name") for
          // path-matching case-sensitivity (Windows vs POSIX). This is JVM platform detection,
          // not ad-hoc config access - the rule's intent. Allowlist with a clear scope note.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.indexing.ExcludeGlobs")
          // Tempdoc 519 §7 Step 7: bootstrap-internal phase functions extracted from
          // HeadAssembly. These inherit the bootstrap's allowlisted sysprop reads
          // (autostart flags, grpc disable flag, occurrence buffer size, eval mode flag).
          // The intent of the guardrail — bar new ad-hoc config access — is preserved
          // because all these reads were already allowlisted via the bootstrap before
          // the §7 extractions; they're just in a different file now.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.InferenceWiring")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.BootstrapHelpers")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.RuleRunnerBuilder")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.InferenceDecision")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.InfraPhase")
          // §31 Phase 1.A: EnterprisePolicyService impl moved from ui to app-services. It sets
          // 2 policy sysprops (gpu_acceleration_enabled, disallowExternalInferenceServers) +
          // reads PROGRAMDATA env + os.name sysprop. These reads pre-existed in the ui location;
          // the allowlist entry just follows the file move.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.policy.EnterprisePolicyServiceImpl")
          // §31 Phase 1.B-D: 7 helper-impl + sibling classes moved from ui to app-services.
          // Their sysprop reads pre-existed in the ui location; this allowlist just follows
          // the file moves (no NEW ad-hoc config access introduced).
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.install.AiInstallService")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.install.DownloadExecutor")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.install.RuntimeRestoreUtil")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.pack.AiPackImportService")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.pack.AiPackValidator")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.pack.PackStagingOps")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.runtime.RuntimeActivationService")
          // Tempdoc 542 §B Layer 3: OperationLeaseServiceImpl reads two env vars at construction:
          //   JUSTSEARCH_DEV_RUNNER_STATE_ROOT — set by dev-runner.cjs when it spawns Head;
          //     absent → service is no-op (production / non-dev-runner launch).
          //   JUSTSEARCH_AGENT_SESSION_ID — agent attribution; mirrors dev-runner's holder ident.
          // These are not application-config: they are dev-runner-coordination signals. The
          // guardrail's intent — bar new ad-hoc application-config reads — is preserved.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.lease.OperationLeaseServiceImpl")
          // Tempdoc 560 §10.4: ExamplePlugin.enabled() reads a single demo-plugin enable toggle
          //   justsearch.demo.plugin / JUSTSEARCH_DEMO_PLUGIN (off by default).
          // This is a demo/dev toggle, not application config — the same carve-out class as
          // OperationLeaseServiceImpl's dev-runner signals above, and consistent with the sibling
          // bootstrap phases (BootstrapHelpers, InferenceWiring) that read their enable-flags
          // directly. Routing through EnvRegistry is barred here anyway: EnvRegistryDirectReadTest
          // forbids EnvRegistry value-reads outside io.justsearch.configuration.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.bootstrap.phases.ExamplePlugin")
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
  static final ArchRule mmfMappedByteBufferMustBeIsolatedToMainSignalBus =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.worker.MainSignalBus")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.nio.MappedByteBuffer");

  // Tempdoc 541 fix-pass B.3: the §31 Rule 2 service-construction-site rule (formerly
  // serviceImplsConstructedOnlyInBootstrapPhases) was lifted to
  // io.justsearch.app.services.bootstrap.CompositionRootGuardrailsTest where it consolidates
  // with the other §4.3 composition-root rules. Predicate moved verbatim; allowlist preserved.
}
