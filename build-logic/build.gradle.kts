plugins {
  `kotlin-dsl`
}

  gradlePlugin {
    plugins {
    register("dependencyLockingConventions") {
      id = "conventions.locking"
      implementationClass = "conventions.LockingConventionsPlugin"
      displayName = "Dependency Locking Conventions"
      description = "Enables dependency locking, adds resolveAndLockAll task"
    }
    register("jvmBaseConventions") {
      id = "conventions.jvm-base"
      implementationClass = "conventions.JvmBaseConventionsPlugin"
      displayName = "JVM Base Conventions"
      description = "Applies java-library, Spotless, PMD, Error Prone, coverage, toolchain + test base"
    }
    register("archivingReproducible") {
      id = "conventions.archiving-reproducible"
      implementationClass = "conventions.ArchivingReproduciblePlugin"
      displayName = "Reproducible Archiving Conventions"
      description = "Sets reproducible archive properties across AbstractArchiveTask"
    }
    register("coverageConventions") {
      id = "conventions.coverage"
      implementationClass = "conventions.CoverageConventionsPlugin"
      displayName = "Coverage Conventions"
      description = "Applies JaCoCo, configures verification rules, and wires into check"
    }
    register("errorProneConventions") {
      id = "conventions.errorprone"
      implementationClass = "conventions.ErrorProneConventionsPlugin"
      displayName = "Error Prone Conventions"
      description = "Applies Error Prone and configures checks/suppressions across Java modules"
    }
    register("dependencyHygiene") {
      id = "conventions.deps-hygiene"
      implementationClass = "conventions.DependencyHygienePlugin"
      displayName = "Dependency Hygiene Conventions"
      description = "Verifies no dynamic/SNAPSHOT dependency versions and enforces catalog hygiene"
    }
    register("spotbugsConventions") {
      id = "conventions.spotbugs"
      implementationClass = "conventions.SpotBugsConventionsPlugin"
      displayName = "SpotBugs Conventions"
      description = "Applies SpotBugs + FindSecBugs for security-focused static analysis"
    }
    register("mutationConventions") {
      id = "conventions.mutation"
      implementationClass = "conventions.MutationConventionsPlugin"
      displayName = "Mutation Testing Conventions"
      description = "Applies PIT (pitest) with shared defaults; modules declare seam targetClasses (tempdoc 555)"
    }
  }
}

java {
  // Use latest supported target for the plugin code; it runs on Gradle's embedded JDK
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
  // Make Error Prone Gradle plugin classes available to the convention plugin at compile time
  implementation(libs.gradle.errorprone.plugin)
  // Make Spotless Gradle plugin types available for configuration in conventions
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
  // SpotBugs plugin for security analysis
  implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:${libs.versions.spotbugsPlugin.get()}")
  // PIT (pitest) Gradle plugin — mutation-testing conventions (tempdoc 555). Gradle 9-compatible refresh.
  implementation("info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0")
  // Jackson on buildscript classpath — used by modules/ui/build.gradle.kts dev-stack tasks
  implementation("com.fasterxml.jackson.core:jackson-databind:2.18.6")
}
