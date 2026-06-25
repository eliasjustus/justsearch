pluginManagement {
  includeBuild("build-logic")
  repositories {
    // Keep plugin repos minimal and centralized
    gradlePluginPortal()
    mavenCentral()
  }
  plugins {
    // Define settings-only plugin versions here
    id("com.gradle.develocity") version "4.4.2"
  }
}



dependencyResolutionManagement {
  // Enforce centralized repositories; forbid project-level repos
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // 0) GitHub Packages (read-only) for previous release artifacts (Revapi baselines)
    // Enabled only in CI or environments providing GITHUB_ACTOR/TOKEN
    if (System.getenv("GITHUB_ACTOR") != null && System.getenv("GITHUB_TOKEN") != null) {
      exclusiveContent {
        forRepository {
          maven {
            name = "GitHubPackages"
            // e.g., owner/repo => https://maven.pkg.github.com/owner/repo
            val ghRepo = System.getenv("GITHUB_REPOSITORY")
            if (ghRepo != null && ghRepo.contains('/')) {
              url = uri("https://maven.pkg.github.com/$ghRepo")
            } else {
              // Fallback to a placeholder to avoid configuration errors
              url = uri("https://maven.pkg.github.com/owner/repo")
            }
            credentials {
              username = System.getenv("GITHUB_ACTOR")
              password = System.getenv("GITHUB_TOKEN")
            }
            // Resolves only released artifacts; snapshots are not part of the Phase 1 posture.
            mavenContent { releasesOnly() }
          }
        }
        // Limit to our published group only
        filter { includeGroup("io.justsearch") }
      }
    }
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
  ":reports",
  ":modules:app-api-tck"
)
// Enable automatic Java toolchain resolution via Foojay to allow EP-compatible compilers
