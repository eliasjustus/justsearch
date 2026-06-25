package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural test ensuring the divergence check set and forwarding set stay consistent (tempdoc
 * 329).
 *
 * <p>Every key in {@link EnvRegistry#CONFIG_DIVERGENCE_CHECK_KEYS} must be forwarded to the Worker
 * subprocess — either via {@link WorkerSpawner#WORKER_FORWARDED_PROPS} or via hardcoded {@code
 * -D} additions in {@code buildCommand()}. If this test fails, the divergence check would compare a
 * key that the Worker never receives, producing a false WARN.
 */
@DisplayName("WorkerSpawner config forwarding consistency")
class WorkerSpawnerConfigForwardingTest {

  /** Keys forwarded via direct {@code cmd.add("-D...")} in buildCommand(), not via the EnumSet. */
  private static final Set<EnvRegistry> HARDCODED_FORWARDED =
      EnumSet.of(EnvRegistry.DATA_DIR); // hardcoded in WorkerSpawner.buildCommand()

  @Test
  @DisplayName("Every divergence-checked key is forwarded to Worker")
  void divergenceCheckKeysAreAllForwarded() {
    Set<EnvRegistry> covered = EnumSet.copyOf(WorkerSpawner.WORKER_FORWARDED_PROPS);
    covered.addAll(HARDCODED_FORWARDED);

    for (EnvRegistry key : EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS) {
      assertTrue(
          covered.contains(key),
          key.name()
              + " ("
              + key.sysProp()
              + ") is in CONFIG_DIVERGENCE_CHECK_KEYS but not forwarded to Worker. "
              + "Add it to WorkerSpawner.WORKER_FORWARDED_PROPS or HARDCODED_FORWARDED.");
    }
  }

  /**
   * Tempdoc 347: every EnvRegistry entry's env var starts with JUSTSEARCH_,
   * which means WorkerSpawner's blanket JUSTSEARCH_* forwarding covers it.
   * If this test fails, the Worker process won't receive the env var.
   */
  @Test
  @DisplayName("Every EnvRegistry env var is covered by blanket JUSTSEARCH_* forwarding")
  void allEnvVarsCoveredByBlanketForwarding() {
    for (EnvRegistry entry : EnvRegistry.values()) {
      assertTrue(
          entry.envVar().startsWith("JUSTSEARCH_"),
          entry.name() + " envVar '" + entry.envVar()
              + "' does not start with JUSTSEARCH_. "
              + "WorkerSpawner's blanket forwarding will miss it. "
              + "Either rename the env var or add explicit forwarding.");
    }
  }

  /**
   * Tempdoc 347: WORKER_FORWARDED_PROPS should not contain duplicates. Compile-time safety
   * already guarantees valid EnvRegistry references; this guards against accidental duplication.
   */
  @Test
  @DisplayName("WORKER_FORWARDED_PROPS has no duplicates")
  void forwardedPropsNoDuplicates() {
    // EnumSet already guarantees uniqueness, but let's assert the size matches
    // to catch any future change to a non-set collection type.
    Set<EnvRegistry> asSet = EnumSet.copyOf(WorkerSpawner.WORKER_FORWARDED_PROPS);
    assertEquals(asSet.size(), WorkerSpawner.WORKER_FORWARDED_PROPS.size(),
        "WORKER_FORWARDED_PROPS contains duplicates");
  }

  /**
   * Tempdoc 374 alpha.19 Bug J-1 regression: all five per-encoder model_path keys
   * must be forwarded to the worker subprocess. Pre-alpha.19 only EMBED_ONNX_MODEL_PATH
   * was forwarded — splade/ner/reranker/citation_scorer were dropped on the floor
   * between head and worker. Round-9 default-flow validation: SPLADE/NER/reranker
   * silently disabled because the worker saw modelPath=null and fell to
   * OnnxModelDiscovery which couldn't find the GPU_FULL fp16-only layout.
   */
  @Test
  @DisplayName("All per-encoder model_path keys are forwarded to Worker (374 alpha.19 Bug J-1)")
  void allEncoderModelPathsAreForwarded() {
    Set<EnvRegistry> forwarded = WorkerSpawner.WORKER_FORWARDED_PROPS;
    Set<EnvRegistry> required = EnumSet.of(
        EnvRegistry.EMBED_ONNX_MODEL_PATH,
        EnvRegistry.SPLADE_MODEL_PATH,
        EnvRegistry.NER_MODEL_PATH,
        EnvRegistry.RERANK_MODEL_PATH,
        EnvRegistry.CITATION_SCORER_MODEL_PATH);
    for (EnvRegistry key : required) {
      assertTrue(
          forwarded.contains(key),
          key.name()
              + " ("
              + key.sysProp()
              + ") must be in WORKER_FORWARDED_PROPS so the worker process gets the"
              + " explicit model path written by AiInstallService.applyOnnxSettings"
              + " post-Install-AI. Without it the worker falls to OnnxModelDiscovery"
              + " which can't handle the GPU_FULL fp16-only layout (374 alpha.19 Bug J-1).");
    }
  }
}
