// Tempdoc 560 §4.2/§4.3 — the ONE transactional contribution composer + the four shared substrates
// (Boundary/Trust/Dispatch/Lifecycle), reused by EVERY axis and BOTH processes (Head + Worker) with
// zero per-axis/per-process re-derivation. Deliberately dep-free (pure JDK): trust/boundary specifics
// are reduced to booleans by each caller, so neither the Head's enums (app-agent-api) nor the Worker's
// Lucene classpath (worker-services) leak in. This is the clean shared module both can depend on.
plugins {
  `java-library`
  id("conventions.jvm-base")
}

dependencies {
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
