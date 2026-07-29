pluginManagement {
  includeBuild("build-logic")
  repositories {
    // Keep plugin repos minimal and centralized
    gradlePluginPortal()
    mavenCentral()
  }
  plugins {
    // Define settings-only plugin versions here
    id("com.gradle.develocity") version "4.5.0"
  }
}



dependencyResolutionManagement {
  // Enforce centralized repositories; forbid project-level repos
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // NOTE (tempdoc 793): a credentialed GitHub Packages repository used to be declared here,
    // filtered to `includeGroup("io.justsearch")` and guarded by a GITHUB_ACTOR/GITHUB_TOKEN
    // conditional, to resolve previously-published artifacts as Revapi baselines.
    //
    // It was removed because it resolved nothing and broke dependency automation:
    //   * Revapi is not applied by any build script, so no baseline is ever resolved.
    //   * No module declares an external `io.justsearch:` dependency — inter-module edges are
    //     all `project(...)` — and no `gradle.lockfile` has ever pinned an `io.justsearch`
    //     artifact, so the repository had no resolution to serve.
    //   * Dependabot parses this block STATICALLY and does not evaluate the env-var guard, so
    //     it saw a private registry it cannot authenticate to (unsupported for the `gradle`
    //     ecosystem) and every Gradle job failed with "Dependabot can't authenticate to a
    //     private package registry". Plugins kept updating because they resolve through
    //     `pluginManagement` above; ALL library updates were silently lost for the repository's
    //     entire public history.
    //
    // Publishing to GitHub Packages is unaffected: that is a separate `publishing { repositories }`
    // block in modules/app-api and modules/api-contract-projection-java.
    //
    // If Revapi baseline resolution is ever wired up, do NOT re-add it here — supply it through a
    // CI-only init script so the settings file Dependabot reads stays free of private registries.

    // 1) Google for AndroidX & GMS (exclusive content)
    exclusiveContent {
      forRepository { google() }
      filter {
        includeGroupByRegex("androidx(\\..*)?")
        includeGroup("com.google.gms")
      }
    }

    // 2) Fallback: Maven Central (exclude groups owned by exclusive repos)
    mavenCentral {
      content {
        excludeGroupByRegex("androidx(\\..*)?")
        excludeGroup("com.google.gms")
      }
      mavenContent { releasesOnly() }
    }
  }
}

// Dependency verification is configured via gradle/verification-metadata.xml

rootProject.name = "justsearch"

// Build Scans & Observability (Phase 1) via Develocity
plugins {
  id("com.gradle.develocity")
  id("org.gradle.toolchains.foojay-resolver") version "1.0.0"
}

toolchainManagement {
  jvm {
    javaRepositories {
      repository("foojay") {
        resolverClass.set(org.gradle.toolchains.foojay.FoojayToolchainResolver::class.java)
      }
    }
  }
}

develocity {
  // Use public scans.gradle.com by default (omit server)
  buildScan {
    if (System.getenv("CI") != null) {
      termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
      termsOfUseAgree.set("yes")
      publishing.onlyIf { true }
      uploadInBackground.set(false)
      tag("CI")
      // Add a link back to the GH Actions run when available
      val repo = System.getenv("GITHUB_REPOSITORY")
      val runId = System.getenv("GITHUB_RUN_ID")
      if (repo != null && runId != null) {
        link("GitHub Actions run", "https://github.com/$repo/actions/runs/$runId")
      }
    } else {
      // Locally: publish only when --scan is used
      publishing.onlyIf { false }
      uploadInBackground.set(true)
      tag("LOCAL")
    }
  }
}

include(
  ":modules:configuration",
  ":modules:core",
  ":modules:core-contracts",
  ":modules:dead-code-audit",
  ":modules:extension-substrate",
  ":modules:adapters-lucene",
  ":modules:indexing",
  ":modules:ort-common",
  ":modules:reranker",
  ":modules:ipc-common",
  ":modules:worker-core",
  ":modules:worker-services",
  ":modules:indexer-worker",
  ":modules:ai-backend",
  ":modules:gpu-bridge",
  ":modules:prompt-support",
  ":modules:app-api",
  ":modules:api-contract-projection-java",
  ":modules:app-agent-api",
  ":modules:app-agent",
  ":modules:app-inference",
  ":modules:app-config",
  ":modules:app-util",
  ":modules:app-observability",
  ":modules:app-services",
  ":modules:infra-core",
  ":modules:ssot-tools",
  ":modules:telemetry",
  ":modules:ui",
  ":modules:app-launcher",
  ":modules:test-support",
  ":modules:system-tests",
  ":modules:benchmarks",
  ":modules:app-api-tck"
)
// Enable automatic Java toolchain resolution via Foojay to allow EP-compatible compilers
