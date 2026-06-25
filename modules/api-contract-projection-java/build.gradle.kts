// Phase 2 of slice 3a-1-8: Java projection of the wire-Category contract.
//
// Source proto files live at repo-root `contracts/wire/`; this module emits
// Java messages from them via the existing `com.google.protobuf` Gradle
// plugin (mirroring `modules/ipc-common`'s pattern). protovalidate-java is
// added as runtime + the bufbuild validate.proto is sourced from its jar
// onto the protoc include path.
//
// TS emission for the same .proto sources is driven separately by
// `buf generate` via npm in `scripts/wire-contract/` — see root build.gradle.kts
// `:wireGenerate`.

import org.gradle.api.plugins.quality.Pmd

plugins {
  `java-library`
  id("conventions.jvm-base")
  alias(libs.plugins.protobuf)
  `maven-publish`
}

tasks.withType<Pmd>().configureEach {
  // Generated Java messages don't follow project PMD rules; suppress.
  enabled = false
}

dependencies {
  api(libs.protobuf.java)
  api(libs.protovalidate.java)
  runtimeOnly(libs.jackson.databind)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.archunit.junit5)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}

// Source the .proto files from repo root.
//
// Bufbuild's `buf.validate.validate` proto lives inside the
// build.buf:protovalidate jar's resources. We extract it onto a build dir
// that protoc consumes as an additional source dir at codegen time.
val protovalidateProtoExtract = tasks.register<Sync>("extractProtovalidateProtos") {
  group = "build"
  description = "Extract bufbuild validate.proto from protovalidate-java jar for protoc include."
  val resolved = configurations.detachedConfiguration(
      dependencies.create(libs.protovalidate.java.get())
  ).apply {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }
  from(zipTree(resolved.singleFile)) {
    include("buf/validate/**/*.proto")
    include("google/api/expr/**/*.proto")
  }
  // Use a path NOT under build/extracted-protos/ — that's the protobuf
  // plugin's reserved location for extract*Proto tasks, which would collide.
  into(layout.buildDirectory.dir("protovalidate-protos"))
}

sourceSets {
  main {
    proto {
      srcDir(rootProject.layout.projectDirectory.dir("contracts/wire"))
      srcDir(layout.buildDirectory.dir("protovalidate-protos"))
    }
  }
}

tasks.matching { it.name.startsWith("generateProto") }.configureEach {
  dependsOn(protovalidateProtoExtract)
}

// processResources reads sourceSets.main.resources, which transitively includes
// the protobuf srcDir. Declare the dependency explicitly so Gradle doesn't
// flag an implicit-input ordering issue.
tasks.matching { it.name == "processResources" || it.name == "processTestResources" }.configureEach {
  dependsOn(protovalidateProtoExtract)
}

// Exclude the extracted-protos dir from resource packaging — it's only an
// include-path source for protoc, not a runtime resource.
tasks.named<ProcessResources>("processResources") {
  exclude("buf/validate/**")
  exclude("google/api/expr/**")
}

val protocVersion = libs.versions.protoc.get()
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val isMac = System.getProperty("os.name").lowercase().contains("mac")
val arch = System.getProperty("os.arch").lowercase()
val osClassifier = when {
  isWindows -> "windows-x86_64"
  isMac -> if (arch == "aarch64") "osx-aarch_64" else "osx-x86_64"
  else -> "linux-x86_64"
}

fun resolveTool(notation: String): java.io.File {
  val configuration =
      configurations.detachedConfiguration(dependencies.create(notation)).apply {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
      }
  return configuration.singleFile
}

val protocBinary =
    resolveTool("com.google.protobuf:protoc:${protocVersion}:${osClassifier}@exe")

protobuf {
  protoc {
    path = protocBinary.absolutePath
  }
  generateProtoTasks {
    all().configureEach {
      // Default Java emission only; no plugins needed for V1.
    }
  }
}

// Generated sources don't pass JaCoCo verification.
tasks.withType<JacocoReport>().configureEach {
  classDirectories.setFrom(files(classDirectories.files.map {
    fileTree(it) {
      exclude("**/io/justsearch/contract/wire/**")
      exclude("**/justsearch/wire/v1/**")
    }
  }))
}

tasks.withType<JacocoCoverageVerification> {
  enabled = false
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      val ghRepo = System.getenv("GITHUB_REPOSITORY")
      if (ghRepo != null && ghRepo.contains('/')) {
        setUrl("https://maven.pkg.github.com/$ghRepo")
      } else {
        setUrl("https://maven.pkg.github.com/owner/repo")
      }
      credentials {
        username = System.getenv("GITHUB_ACTOR") ?: ""
        password = System.getenv("GITHUB_TOKEN") ?: ""
      }
    }
  }
}
