plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  implementation(libs.jackson.databind)
  runtimeOnly(libs.jackson.core)
  implementation(libs.handlebars) {
    exclude(group = "org.openjdk.nashorn", module = "nashorn-core")
  }
  implementation(libs.guava)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}
