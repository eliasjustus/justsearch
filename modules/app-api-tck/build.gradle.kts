plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for app-api-tck test classpaths")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for app-api-tck test classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for app-api-tck test classpaths")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for app-api-tck test classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for app-api-tck test classpaths")
    }
  }
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
        runtimeOnly(libs.jackson.core)
        runtimeOnly(libs.jackson.databind)
        runtimeOnly(project(":modules:ai-backend"))
      }
    }
  }
}
