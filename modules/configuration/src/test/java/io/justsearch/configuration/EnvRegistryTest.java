package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnvRegistryTest {

    @Test
    void sysProp_returnsCorrectValue() {
        assertEquals("justsearch.data.dir", EnvRegistry.DATA_DIR.sysProp());
        assertEquals("justsearch.ssot.path", EnvRegistry.SSOT_PATH.sysProp());
        assertEquals("justsearch.llm.model_sha256", EnvRegistry.LLM_MODEL_SHA256.sysProp());
        assertEquals(
            "justsearch.translator.pipeline.intent",
            EnvRegistry.TRANSLATOR_PIPELINE_INTENT.sysProp());
        assertEquals("justsearch.summary.pipeline", EnvRegistry.SUMMARY_PIPELINE.sysProp());
        assertEquals(
            "justsearch.embed.dimension", EnvRegistry.EMBED_DIMENSION_OVERRIDE.sysProp());
        assertEquals("justsearch.vram.threshold.12gb", EnvRegistry.VRAM_THRESHOLD_12GB.sysProp());
        assertEquals("justsearch.vram.threshold.8gb", EnvRegistry.VRAM_THRESHOLD_8GB.sysProp());
        assertEquals("justsearch.vram.threshold.4gb", EnvRegistry.VRAM_THRESHOLD_4GB.sysProp());
    }

    @Test
    void envVar_returnsCorrectValue() {
        assertEquals("JUSTSEARCH_DATA_DIR", EnvRegistry.DATA_DIR.envVar());
        assertEquals("JUSTSEARCH_SSOT_PATH", EnvRegistry.SSOT_PATH.envVar());
        assertEquals("JUSTSEARCH_LLM_MODEL_SHA256", EnvRegistry.LLM_MODEL_SHA256.envVar());
        assertEquals(
            "JUSTSEARCH_TRANSLATOR_PIPELINE_INTENT",
            EnvRegistry.TRANSLATOR_PIPELINE_INTENT.envVar());
        assertEquals("JUSTSEARCH_SUMMARY_PIPELINE", EnvRegistry.SUMMARY_PIPELINE.envVar());
        assertEquals("JUSTSEARCH_EMBED_DIM", EnvRegistry.EMBED_DIMENSION_OVERRIDE.envVar());
        assertEquals("JUSTSEARCH_VRAM_THRESHOLD_12GB", EnvRegistry.VRAM_THRESHOLD_12GB.envVar());
        assertEquals("JUSTSEARCH_VRAM_THRESHOLD_8GB", EnvRegistry.VRAM_THRESHOLD_8GB.envVar());
        assertEquals("JUSTSEARCH_VRAM_THRESHOLD_4GB", EnvRegistry.VRAM_THRESHOLD_4GB.envVar());
    }

    @Test
    void getInt_returnsDefaultWhenNotSet() {
        assertEquals(8080, EnvRegistry.API_PORT.getInt(8080));
    }

    @Test
    void getBoolean_returnsDefaultWhenNotSet() {
        assertEquals(false, EnvRegistry.PROD_MODE.getBoolean(false));
    }

    @Test
    void getString_returnsDefaultWhenNotSet() {
        assertEquals("default", EnvRegistry.EMBED_BACKEND.getString("default"));
    }

    @Test
    void getInt_readsSystemPropertyAndFallsBackOnInvalid() {
        withSysProp(EnvRegistry.LLM_MAX_PARALLEL.sysProp(), "7", () ->
            assertEquals(7, EnvRegistry.LLM_MAX_PARALLEL.getInt(1)));
        withSysProp(EnvRegistry.LLM_MAX_PARALLEL.sysProp(), "invalid", () ->
            assertEquals(1, EnvRegistry.LLM_MAX_PARALLEL.getInt(1)));
    }

    @Test
    void getLong_readsSystemProperty() {
        withSysProp(EnvRegistry.LLM_DEADLINE_MS.sysProp(), "1234", () ->
            assertEquals(1234L, EnvRegistry.LLM_DEADLINE_MS.getLong(900L)));
    }

    @Test
    void getBoolean_parsesConfiguredValues() {
        withSysProp(EnvRegistry.LLM_ALLOW_REMOTE.sysProp(), "yes", () ->
            assertTrue(EnvRegistry.LLM_ALLOW_REMOTE.getBoolean(false)));
        withSysProp(EnvRegistry.LLM_ALLOW_REMOTE.sysProp(), "no", () ->
            assertFalse(EnvRegistry.LLM_ALLOW_REMOTE.getBoolean(true)));
    }

    @Test
    void getPath_readsSystemProperty() {
        Path expected = Path.of("tmp", "registry-test");
        withSysProp(EnvRegistry.LLM_TEMPLATE_ROOT.sysProp(), expected.toString(), () ->
            assertEquals(expected, EnvRegistry.LLM_TEMPLATE_ROOT.getPath()));
    }

    @Test
    void useThinking_hasMappingsAndDefaultsToTrue() {
        assertEquals("justsearch.llm.use_thinking", EnvRegistry.USE_THINKING.sysProp());
        assertEquals("JUSTSEARCH_USE_THINKING", EnvRegistry.USE_THINKING.envVar());
        assertTrue(EnvRegistry.USE_THINKING.getBoolean(true),
            "USE_THINKING default should be true");
    }

    @Test
    void rerankGpuMemMb_hasMappingsAndDefault() {
        assertEquals("justsearch.rerank.gpu_mem_mb", EnvRegistry.RERANK_GPU_MEM_MB.sysProp());
        assertEquals("JUSTSEARCH_RERANK_GPU_MEM_MB", EnvRegistry.RERANK_GPU_MEM_MB.envVar());
        assertEquals("2048", EnvRegistry.RERANK_GPU_MEM_MB.defaultValue(),
            "RERANK_GPU_MEM_MB declared default should be 2048");
    }

    @Test
    void embedGpuMemMb_hasMappingsAndDefault() {
        assertEquals("justsearch.embed.gpu_mem_mb", EnvRegistry.EMBED_GPU_MEM_MB.sysProp());
        assertEquals("JUSTSEARCH_EMBED_GPU_MEM_MB", EnvRegistry.EMBED_GPU_MEM_MB.envVar());
        assertEquals(1024, EnvRegistry.EMBED_GPU_MEM_MB.getInt(1024),
            "EMBED_GPU_MEM_MB default should be 1024");
    }

    @Test
    void agentContextCompressionFlags_haveMappingsAndDefaults() {
        assertEquals(
            "justsearch.agent.context_compression.enabled",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_ENABLED.sysProp());
        assertEquals(
            "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_ENABLED.envVar());
        assertFalse(EnvRegistry.AGENT_CONTEXT_COMPRESSION_ENABLED.getBoolean(false));

        assertEquals(
            "justsearch.agent.context_compression.min_chars",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_MIN_CHARS.sysProp());
        assertEquals(
            "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_MIN_CHARS.envVar());
        assertEquals(1200, EnvRegistry.AGENT_CONTEXT_COMPRESSION_MIN_CHARS.getInt(1200));

        assertEquals(
            "justsearch.agent.context_compression.keep_last_results",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS.sysProp());
        assertEquals(
            "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS",
            EnvRegistry.AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS.envVar());
        assertEquals(1, EnvRegistry.AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS.getInt(1));
    }

    /**
     * Every reranker EnvRegistry entry with a numeric/boolean default must declare it via the
     * 3-arg constructor so contributeEnvRegistry() registers it at ordinal 100. Entries without
     * a declared default force ResolvedConfigBuilder to maintain a separate fallback — the root
     * cause of the config-sync drift bug fixed in tempdoc 360.
     */
    @Test
    void allRerankEntriesWithDefaults_haveDeclaredDefaultValue() {
        // Search reranker — all entries that have a meaningful default
        EnvRegistry[] rerankEntries = {
            EnvRegistry.RERANK_GPU_ENABLED, EnvRegistry.RERANK_GPU_MEM_MB,
            EnvRegistry.RERANK_GPU_DEVICE_ID, EnvRegistry.RERANK_TOP_K,
            EnvRegistry.RERANK_DEADLINE_MS, EnvRegistry.RERANK_MIN_HITS,
            EnvRegistry.RERANK_MAX_SEQ_LEN, EnvRegistry.RERANK_MAX_AVG_DOC_LENGTH_CHARS,
            // Chunk reranker
            EnvRegistry.RERANK_CHUNKS_GPU_ENABLED, EnvRegistry.RERANK_CHUNKS_GPU_DEVICE_ID,
            EnvRegistry.RERANK_CHUNKS_TOP_K, EnvRegistry.RERANK_CHUNKS_MAX_GPU_CANDIDATES,
            EnvRegistry.RERANK_CHUNKS_DEADLINE_MS, EnvRegistry.RERANK_CHUNKS_MIN_HITS,
            EnvRegistry.RERANK_CHUNKS_MAX_SEQ_LEN, EnvRegistry.RERANK_CHUNKS_ORDER,
        };
        for (EnvRegistry entry : rerankEntries) {
            assertNotNull(entry.defaultValue(),
                entry.name() + " must declare a defaultValue (3-arg constructor) "
                    + "so contributeEnvRegistry() registers it at ordinal 100");
        }
    }

    private static void withSysProp(String key, String value, Runnable assertion) {
        String original = System.getProperty(key);
        try {
            System.setProperty(key, value);
            assertion.run();
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    /**
     * Tempdoc 419 T6.1 — pins the LITE_MODE entry so a future rename surfaces immediately.
     * The bootstrap-side wiring lives in {@code HeadAssembly.createInferenceManager}
     * which reads the same sysprop/env pair.
     */
    @Test
    void liteModeEntryShape() {
        assertEquals("justsearch.lite.mode", EnvRegistry.LITE_MODE.sysProp());
        assertEquals("JUSTSEARCH_LITE_MODE", EnvRegistry.LITE_MODE.envVar());
        // Default is false — only the test-harness fixture (T6.2) sets it.
        assertFalse(EnvRegistry.LITE_MODE.getBoolean(false));
    }

    @Test
    void liteModeRespectsSysProp() {
        withSysProp(EnvRegistry.LITE_MODE.sysProp(), "true",
            () -> assertTrue(EnvRegistry.LITE_MODE.getBoolean(false)));
    }
}
