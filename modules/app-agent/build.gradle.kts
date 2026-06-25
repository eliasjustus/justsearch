plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  api(project(":modules:app-agent-api"))

  api(project(":modules:app-api"))
  implementation(project(":modules:configuration"))
  api(project(":modules:telemetry"))

  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  api(libs.opentelemetry.api)
  implementation(libs.slf4j.api)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:configuration")))
        implementation(testFixtures(project(":modules:telemetry")))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        // Slice 487 Phase 1.7 audit-test (post-impl fix A2): ArchUnit-based
        // assertion that no class in modules/app-agent calls
        // OperationDispatcher.dispatch directly (except the documented legacy
        // fallback in AgentLoopService.dispatchToolCall). Bytecode-level check
        // — name-independent, so a future field rename does not silently break
        // the gate.
        implementation(libs.archunit.junit5)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            // Tempdoc 577 §2.12 Move 2 — the budget gate falls through immediately under test (0s
            // timeout), so an interactive budget-exhaustion test exercises exactly the legacy
            // finalize-else-error fallback (plus the two observable park events) without blocking.
            // The gate's CONTINUE/STOP resolution is unit-tested directly via AgentSession.
            systemProperty("justsearch.agent.budgetGateTimeoutSec", "0")
            // Tempdoc 577 §2.14 Root II — likewise the context gate falls through immediately under
            // test (0s ⇒ CONTINUE), so a high-context-pressure run never blocks the suite.
            systemProperty("justsearch.agent.contextGateTimeoutSec", "0")
            // Tempdoc 577 §2.14 Root I — the zero-observer park also falls through immediately (0s),
            // so a Watch test with no subscribed observer never blocks the suite.
            systemProperty("justsearch.agent.zeroObserverParkTimeoutSec", "0")
          }
        }
      }
    }
  }
}
