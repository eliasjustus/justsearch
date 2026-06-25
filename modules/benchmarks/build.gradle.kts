import org.gradle.api.plugins.quality.PmdExtension

plugins {
  id("conventions.jvm-base")
}

description = "JustSearch Indexing & Search Benchmarks"

// Benchmarks are not production code - relax PMD enforcement
plugins.withId("pmd") {
  configure<PmdExtension> {
    isIgnoreFailures = true
  }
}

dependencies {
  // Core indexing & configuration
  implementation(project(":modules:adapters-lucene"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:indexing"))
  implementation(project(":modules:reranker"))
  implementation(project(":modules:ort-common"))
  // Lucene query types for microbench filters (e.g., TermInSetQuery, PrefixQuery)
  implementation(libs.lucene.core)
  runtimeOnly(libs.lucene.analysis.common)
  runtimeOnly(libs.lucene.analysis.icu)

  // Jackson for JSON I/O
  implementation(libs.jackson.databind)

  // Logging
  implementation(libs.slf4j.api)
  runtimeOnly(libs.logback.classic)

  // Statistics (percentile calculation)
  implementation(libs.commons.math3)

  // OSHI for process-level memory metrics (RSS)
  implementation(libs.oshi.core)

  // GPU ONNX Runtime for reranker benchmarks (overrides CPU-only transitive dep)
  runtimeOnly(libs.onnxruntime.gpu)

  // Testing (for benchmark utility tests if needed)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// Register the engine index benchmark runner task
tasks.register<JavaExec>("engineIndexBench") {
  group = "benchmark"
  description = "Run the Claim A engine-only indexing benchmark."
  mainClass.set("io.justsearch.benchmarks.EngineIndexBench")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir

  // Pass Gradle properties as arguments
  val corpus = project.findProperty("benchCorpus")?.toString() ?: "tmp/eval-corpus-beir/scifact/docs.ndjson"
  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/claim-a"
  val timerStart = project.findProperty("benchTimerStart")?.toString() ?: ""
  val queries = project.findProperty("benchQueries")?.toString() ?: ""
  val queryLimit = project.findProperty("benchQueryLimit")?.toString() ?: ""
  val batchSize = project.findProperty("benchBatchSize")?.toString() ?: ""

  val argsList = mutableListOf("--corpus=$corpus", "--out-dir=$outDir")
  if (timerStart.isNotBlank()) {
    argsList.add("--timer-start=$timerStart")
  }
  if (queries.isNotBlank()) {
    argsList.add("--queries=$queries")
  }
  if (queryLimit.isNotBlank()) {
    argsList.add("--query-limit=$queryLimit")
  }
  if (batchSize.isNotBlank()) {
    argsList.add("--batch-size=$batchSize")
  }
  args(argsList)

  // Ensure reasonable JVM settings for benchmarking
  jvmArgs("-Xms512m", "-Xmx2g")
}

// Register the indexing overhead profiler
tasks.register<JavaExec>("indexingOverheadProfile") {
  group = "benchmark"
  description = "Profile per-document indexing overhead: validation, field mapping, Lucene write."
  mainClass.set("io.justsearch.benchmarks.IndexingOverheadProfiler")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir

  val corpus = project.findProperty("benchCorpus")?.toString() ?: "tmp/eval-corpus-beir/scifact/docs.ndjson"
  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/overhead-profile"

  args("--corpus=$corpus", "--out-dir=$outDir")
  jvmArgs("-Xms512m", "-Xmx2g")
}

// Register the raw Lucene indexing benchmark (no JustSearch overhead)
tasks.register<JavaExec>("rawLuceneIndexBench") {
  group = "benchmark"
  description = "Run the raw Lucene indexing benchmark (no JustSearch schema/metadata overhead)."
  mainClass.set("io.justsearch.benchmarks.RawLuceneIndexBench")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir

  val corpus = project.findProperty("benchCorpus")?.toString() ?: "tmp/eval-corpus-beir/scifact/docs.ndjson"
  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/raw-lucene"

  args("--corpus=$corpus", "--out-dir=$outDir")
  jvmArgs("-Xms512m", "-Xmx2g")
}

// Register the engine vector index benchmark runner task
tasks.register<JavaExec>("engineVectorIndexBench") {
  group = "benchmark"
  description = "Run the engine-only vector indexing benchmark (pre-embedded corpus)."
  mainClass.set("io.justsearch.benchmarks.EngineVectorIndexBench")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir

  val vectors = project.findProperty("benchVectors")?.toString() ?: ""
  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/vector"
  val corpusId = project.findProperty("benchCorpusId")?.toString() ?: ""
  val timerStart = project.findProperty("benchTimerStart")?.toString() ?: ""
  val batchSize = project.findProperty("benchBatchSize")?.toString() ?: ""
  val queryVectors = project.findProperty("benchQueryVectorsNdjson")?.toString() ?: ""
  val queryLimit = project.findProperty("benchQueryLimit")?.toString() ?: ""
  val queryCount = project.findProperty("benchQueryCount")?.toString() ?: ""
  val truthKnn = project.findProperty("benchTruthKnnNdjson")?.toString() ?: ""
  val hnswM = project.findProperty("benchHnswM")?.toString() ?: ""
  val efConstruction = project.findProperty("benchEfConstruction")?.toString() ?: ""
  val efSearch = project.findProperty("benchEfSearch")?.toString() ?: ""
  val quantizationEnabled = project.findProperty("benchQuantizationEnabled")?.toString() ?: ""

  if (vectors.isNotBlank()) {
    args("--vectors=$vectors")
  }
  if (corpusId.isNotBlank()) {
    args("--corpus-id=$corpusId")
  }
  args("--out-dir=$outDir")
  if (timerStart.isNotBlank()) {
    args("--timer-start=$timerStart")
  }
  if (batchSize.isNotBlank()) {
    args("--batch-size=$batchSize")
  }
  if (queryVectors.isNotBlank()) {
    args("--queries-vectors=$queryVectors")
  }
  if (queryLimit.isNotBlank()) {
    args("--query-limit=$queryLimit")
  }
  if (queryCount.isNotBlank()) {
    args("--query-count=$queryCount")
  }
  if (truthKnn.isNotBlank()) {
    args("--truth-knn=$truthKnn")
  }
  if (hnswM.isNotBlank()) {
    args("--hnsw-m=$hnswM")
  }
  if (efConstruction.isNotBlank()) {
    args("--ef-construction=$efConstruction")
  }
  if (efSearch.isNotBlank()) {
    args("--ef-search=$efSearch")
  }
  if (quantizationEnabled.isNotBlank()) {
    args("--quantization-enabled=$quantizationEnabled")
  }

  jvmArgs("-Xms512m", "-Xmx2g", "--add-modules=jdk.incubator.vector")
}

// Register the filtered kNN microbenchmark runner task
tasks.register<JavaExec>("filteredKnnBench") {
  group = "benchmark"
  description = "Run the filtered kNN microbenchmark (filter selectivity envelope)."
  mainClass.set("io.justsearch.benchmarks.FilteredKnnBench")
  classpath = sourceSets["main"].runtimeClasspath

  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/filtered-knn"
  val docCount = project.findProperty("benchDocCount")?.toString() ?: "20000"
  val vectorDim = project.findProperty("benchVectorDim")?.toString() ?: "768"
  val k = project.findProperty("benchK")?.toString() ?: "10"
  val warmup = project.findProperty("benchWarmup")?.toString() ?: "5"
  val iterations = project.findProperty("benchIterations")?.toString() ?: "50"
  val docIdInSizes = project.findProperty("benchDocIdInSizes")?.toString() ?: "10,100,1000,10000"
  val parentIdInSizes = project.findProperty("benchParentIdInSizes")?.toString() ?: ""
  val pathPrefix = project.findProperty("benchPathPrefix")?.toString() ?: "d:/bench/folder0/"
  val chunkMode = project.findProperty("benchChunkMode")?.toString()?.toBoolean() ?: false
  val chunksPerParent = project.findProperty("benchChunksPerParent")?.toString() ?: "10"
  val efSearchValues = project.findProperty("benchEfSearchValues")?.toString()
    ?: project.findProperty("benchEfSearch")?.toString() ?: ""

  val argsList = mutableListOf(
    "--out-dir=$outDir",
    "--doc-count=$docCount",
    "--vector-dim=$vectorDim",
    "--k=$k",
    "--warmup=$warmup",
    "--iterations=$iterations",
    "--docid-in-sizes=$docIdInSizes",
    "--path-prefix=$pathPrefix",
  )
  if (parentIdInSizes.isNotBlank()) {
    argsList.add("--parentid-in-sizes=$parentIdInSizes")
  }
  if (chunkMode) {
    argsList.add("--chunk-mode")
    argsList.add("--chunks-per-parent=$chunksPerParent")
  }
  if (efSearchValues.isNotBlank()) {
    argsList.add("--ef-search-values=$efSearchValues")
  }
  args(argsList)

  // kNN microbench can be memory-heavy for large docCount/vectorDim
  jvmArgs("-Xms512m", "-Xmx4g", "--add-modules=jdk.incubator.vector")
}

// Register the vector quantization gate runner task
tasks.register<JavaExec>("quantizationGate") {
  group = "benchmark"
  description = "Run the vector quantization compatibility gate (quantized vs float, CFS on/off)."
  mainClass.set("io.justsearch.benchmarks.VectorQuantizationGate")
  classpath = sourceSets["main"].runtimeClasspath

  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/quantization-gate"
  val docCount = project.findProperty("benchDocCount")?.toString() ?: "5000"
  val vectorDim = project.findProperty("benchVectorDim")?.toString() ?: "768"
  val k = project.findProperty("benchK")?.toString() ?: "10"
  val keepIndex = project.findProperty("benchKeepIndex")?.toString() ?: "false"

  args(
    "--out-dir=$outDir",
    "--doc-count=$docCount",
    "--vector-dim=$vectorDim",
    "--k=$k",
  )
  if (keepIndex.equals("true", ignoreCase = true)) {
    args("--keep-index")
  }

  jvmArgs("-Xms512m", "-Xmx4g", "--add-modules=jdk.incubator.vector")
}

// Register the reranker deadline microbenchmark runner task
tasks.register<JavaExec>("rerankerBench") {
  group = "benchmark"
  description = "Run the cross-encoder reranker deadline microbenchmark."
  mainClass.set("io.justsearch.benchmarks.RerankerDeadlineBench")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir

  val outDir = project.findProperty("benchOutDir")?.toString() ?: "tmp/bench/reranker"
  val modelDir = project.findProperty("benchModelDir")?.toString() ?: "models/reranker/ms-marco-MiniLM-L6-v2"
  val maxSeqLen = project.findProperty("benchMaxSeqLen")?.toString() ?: "512"
  val candidateCounts = project.findProperty("benchCandidateCounts")?.toString() ?: "1,3,5,10"
  val docChars = project.findProperty("benchDocChars")?.toString() ?: "512,1024,2048"
  val deadlines = project.findProperty("benchDeadlinesMs")?.toString() ?: "50,100,150,250"
  val warmup = project.findProperty("benchWarmup")?.toString() ?: "3"
  val iterations = project.findProperty("benchIterations")?.toString() ?: "20"
  val gpuEnabled = project.findProperty("benchGpu")?.toString()?.toBoolean() ?: false
  val gpuDeviceId = project.findProperty("benchGpuDeviceId")?.toString() ?: "0"

  val argsList = mutableListOf(
    "--out-dir=$outDir",
    "--model-dir=$modelDir",
    "--max-seq-len=$maxSeqLen",
    "--candidate-counts=$candidateCounts",
    "--doc-chars=$docChars",
    "--deadlines-ms=$deadlines",
    "--warmup=$warmup",
    "--iterations=$iterations",
  )
  if (gpuEnabled) {
    argsList.add("--gpu")
    argsList.add("--gpu-device-id=$gpuDeviceId")
  }
  args(argsList)

  jvmArgs("-Xms512m", "-Xmx4g", "--enable-native-access=ALL-UNNAMED")
}
