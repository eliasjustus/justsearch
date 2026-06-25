plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  api(project(":modules:app-api"))  // Exposes API types in public interface
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  runtimeOnly(libs.jackson.dataformat.yaml)
  implementation(libs.slf4j.api)
  // jackson-datatype-jdk8 removed — merged into jackson-databind in Jackson 3.x
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for ai-backend classpaths")
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
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
    register<JvmTestSuite>("determinismTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("integrationTest")) } } }
    }
  }
}

tasks.named("check") {
  dependsOn(
      tasks.named("integrationTest"),
      tasks.named("determinismTest"))
}

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  workingDir = rootProject.projectDir
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
