package conventions

import com.fasterxml.jackson.databind.ObjectMapper
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.configure

/**
 * The mutation convention's extension (tempdoc 576 §3.2 — mutation-as-a-capability). A module may
 * point PIT at one or more logic-seam registers; the default is the canonical `logic-seams` register,
 * so existing modules need no change. A second register (e.g. a domain register whose guards are
 * tagged `verify: mutation`) opts its seams into the SAME ratchet machinery by adding its path here.
 */
abstract class MutationExtension {
  /** Repo-relative paths to logic-seam registers that scope PIT for this module. */
  abstract val registerPaths: ListProperty<String>
}

/**
 * Mutation-testing conventions (tempdoc 555 — test-efficacy authority, Pillar C).
 *
 * Applies the PIT (pitest) Gradle plugin with shared defaults so each opted-in module only needs to
 * declare its `targetClasses` (the registered law-bearing seams) in its own build file. This is the
 * structural, build-file integration that replaces the throwaway init-script probe used during
 * de-risking (tempdoc 555 §10/U1): PIT artifacts flow through dependency-verification + locking like
 * any other dependency, and the plugin is a first-class convention alongside coverage/spotbugs.
 *
 * PIT runs the test suite once per mutant, so it MUST stay off the default build graph. It is invoked
 * explicitly (`:modules:<m>:pitest`) or via the test-efficacy report producer
 * (scripts/ci/report-pit-strength.mjs) — never wired into `check`.
 *
 * Pinned versions (verified on the Java 25 / Gradle 9.4 / JUnit 5.14 stack, tempdoc 555 §9.1):
 *   - PIT tool  : 1.25.3  (bundles ASM 9.9 → reads class-file v69 / Java 25)
 *   - JUnit5 plugin: 1.2.3
 * The gradle-pitest-plugin (1.19.0, Gradle 9-compatible) is declared in build-logic/build.gradle.kts.
 */
class MutationConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // PIT requires the Java plugin; apply only once it is present so the extension + tasks wire up.
    project.pluginManager.withPlugin("java") {
      project.pluginManager.apply("info.solidsoft.pitest")

      // The logic-seam register(s) (Pillar A) are the SINGLE authority for what is mutation-targeted:
      // scope PIT to exactly the seams registered for this module. Never mutate a whole module
      // (repo-wide mutation is meaningless noise — tempdoc 555 §6/§8). The register list is
      // configurable (tempdoc 576 §3.2); default is the canonical logic-seams register.
      val ext = project.extensions.create("mutation", MutationExtension::class.java)
      ext.registerPaths.convention(listOf(DEFAULT_REGISTER))

      project.extensions.configure<PitestPluginExtension> {
        pitestVersion.set("1.25.3")
        junit5PluginVersion.set("1.2.3")
        // Default PIT mutators (NegateConditionals, ConditionalsBoundary, Math, Increments,
        // Return-values, VoidMethodCall) — the operators that bite the silent-value bug class.
        threads.set(2)
        timestampedReports.set(false)
        outputFormats.set(setOf("XML", "HTML"))
        // A module may apply this convention before any seam is registered for it; do not fail.
        failWhenNoMutations.set(false)
      }

      // Seam scope depends on the configurable register list, which the build file may set AFTER
      // this convention is applied — resolve it in afterEvaluate so an override is respected.
      project.afterEvaluate {
        val seams = readSeamsFor(project, ext.registerPaths.get())
        val seamClasses = seams.mapNotNull { it.targetClass }.toCollection(LinkedHashSet())
        val seamTests = seams.flatMap { it.targetTests }.toCollection(LinkedHashSet())
        if (seamClasses.isNotEmpty()) {
          project.extensions.configure<PitestPluginExtension> {
            targetClasses.set(seamClasses)
            // Each seam declares its guard test(s) (`targetTests`); PIT runs that exact set and
            // filters to the coverage-providing subset per mutant. Explicit guard tests keep the
            // mutation suite green (a broad package glob would pull in unrelated/integration tests
            // that PIT cannot run green — "mutation testing requires a green suite"). Fall back to
            // the seam's package glob only when a seam omits targetTests.
            targetTests.set(
                if (seamTests.isNotEmpty()) seamTests
                else seamClasses.map { it.substringBeforeLast('.') + ".*" }.toSet())
          }
        }
      }
    }
  }

  /** A registered seam belonging to the current module. */
  private data class Seam(val targetClass: String?, val targetTests: List<String>)

  /**
   * Reads each configured seam register and returns the seams whose `gradlePath` matches this
   * project. A register is the single source of mutation scope, so a seam is mutation-targeted iff
   * it is declared in one of them (tempdoc 555 Pillar A; multi-register per tempdoc 576 §3.2).
   */
  private fun readSeamsFor(project: Project, registerPaths: List<String>): List<Seam> {
    val out = ArrayList<Seam>()
    val mapper = ObjectMapper()
    for (path in registerPaths) {
      val registry = project.rootProject.file(path)
      if (!registry.exists()) continue
      val seams = mapper.readTree(registry).get("seams") ?: continue
      for (seam in seams) {
        if (seam.get("gradlePath")?.asText() != project.path) continue
        val tests = seam.get("targetTests")?.mapNotNull { it.asText() } ?: emptyList()
        out.add(Seam(seam.get("targetClass")?.asText(), tests))
      }
    }
    return out
  }

  private companion object {
    /** The canonical logic-seam register — the default mutation scope authority. */
    const val DEFAULT_REGISTER = "governance/logic-seams.v1.json"
  }
}
