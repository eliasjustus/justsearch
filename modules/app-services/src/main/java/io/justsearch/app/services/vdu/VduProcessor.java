/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.util.TempFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Two-pass VDU (Visual Document Understanding) pipeline via llama-server.
 *
 * <p>Pass 1: Extract text from document images (vision completion)
 * <p>Pass 2: Summarize and extract entities (chat completion)
 *
 * <p>Note: the canonical chat model (Qwen3.5-9B Q4_K_M) is text-only; the
 * VDU pipeline currently requires a separate vision-capable model on the
 * llama-server side. Vision-model selection is tracked separately from
 * tempdoc 374's model-registry work.
 *
 * <p>This processor is thread-safe and can be reused for multiple documents.
 */
public class VduProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(VduProcessor.class);

    private static final String PASS1_PROMPT =
        "Return the plain text representation of this document as if you were reading it naturally."
            + " Do not add any commentary.";

    private static final String PASS2_PROMPT_TEMPLATE = """
        /no_think
        Based on the following extracted document content, provide:
        1. A brief summary (2-3 sentences)
        2. Document type (invoice, contract, receipt, letter, etc.)
        3. Key entities (dates, amounts, names, addresses)

        Output as JSON: {"summary": "...", "doc_type": "...", "entities": {...}}

        Document content:
        %s
        """;

    /** Max tokens for text extraction pass (full-page text can exceed 2048 tokens). */
    private static final int PASS1_MAX_TOKENS = 4096;

    /** Max tokens for enrichment pass. */
    private static final int PASS2_MAX_TOKENS = 512;

    /**
     * Maximum characters to include in the pass 2 (enrichment) prompt.
     *
     * <p>This limit (8,000 chars ≈ 2,000 tokens) is larger than {@code SummaryController}'s 6K
     * limit because vision enrichment benefits from more context (extracted text from pass 1).
     *
     * <p>Rationale for 8K:
     * <ol>
     *   <li>Vision models can handle longer context than chat models
     *   <li>Pass 2 needs sufficient extracted text to enrich accurately
     *   <li>Balances quality against latency (keeps VDU under 30s total)
     * </ol>
     *
     * @see io.justsearch.ui.api.SummaryController#MAX_CONTENT_CHARS 6K summarization limit
     * @see io.justsearch.indexerworker.services.GrpcSearchService#MAX_CONTENT_CHARS 200K transport
     */
    private static final int MAX_CONTEXT_CHARS = 8000;

    /** Timeout for vision completion per page (seconds). */
    private static final long VDU_VISION_TIMEOUT_SECONDS = 120;

    /** Timeout for chat completion (seconds). */
    private static final long VDU_CHAT_TIMEOUT_SECONDS = 60;

    // Tempdoc 518 Appendix G W4.2: three per-role injections replace the concrete ILM holder.
    // Each role is documented at its use site (introspection for vision-capability check;
    // lifecycle for enter/exit VDU mode restart pair; service for vision + chat completions).
    private final OnlineAiRuntimeIntrospection introspection;
    private final OnlineAiLifecycleControl lifecycleControl;
    private final OnlineAiService aiService;
    private final TempFileManager tempFileManager;
    private final ImagePreparer imagePreparer;
    private final VduMetricCatalog catalog;

    /**
     * Creates a new VduProcessor without telemetry (backward compatibility).
     */
    public VduProcessor(OnlineAiRuntimeIntrospection introspection,
                        OnlineAiLifecycleControl lifecycleControl,
                        OnlineAiService aiService,
                        TempFileManager tempFileManager,
                        ImagePreparer imagePreparer) {
        this(introspection, lifecycleControl, aiService, tempFileManager, imagePreparer,
            VduMetricCatalog.noop());
    }

    /**
     * Creates a new VduProcessor with a {@link VduMetricCatalog} for typed metric emission.
     *
     * <p>Tempdoc 417 Phase 3d: pass timers (pass1, pass2, total) now emit through {@code catalog}
     * histograms ({@code vdu.pass1.duration_ms}, {@code vdu.pass2.duration_ms},
     * {@code vdu.total.duration_ms}) instead of the legacy {@code Telemetry.Timer} indirection.
     *
     * <p>Tempdoc 518 Appendix G W4.2: the previously-injected concrete {@code
     * InferenceLifecycleManager} is replaced by three role-typed handles. The migration
     * decouples this class from the inference internals and lets {@code
     * InferenceModuleBoundaryTest} tighten the ArchUnit rule to {@code
     * app-services.bootstrap..} only.
     *
     * @param introspection vision-capability probe ({@link OnlineAiRuntimeIntrospection})
     * @param lifecycleControl enter/exit VDU mode ({@link OnlineAiLifecycleControl})
     * @param aiService vision + chat completions ({@link OnlineAiService})
     * @param tempFileManager manager for temporary files
     * @param imagePreparer prepares images for VLM consumption
     * @param catalog VDU metric catalog (use {@link VduMetricCatalog#noop()} when wireup absent)
     */
    public VduProcessor(OnlineAiRuntimeIntrospection introspection,
                        OnlineAiLifecycleControl lifecycleControl,
                        OnlineAiService aiService,
                        TempFileManager tempFileManager,
                        ImagePreparer imagePreparer,
                        VduMetricCatalog catalog) {
        this.introspection = introspection;
        this.lifecycleControl = lifecycleControl;
        this.aiService = aiService;
        this.tempFileManager = tempFileManager;
        this.imagePreparer = imagePreparer;
        this.catalog = catalog;
    }

    public boolean hasVisionCapability() {
        return introspection.hasVisionCapability();
    }

    /**
     * Process a document through the two-pass VDU pipeline.
     *
     * <p>Synchronous operation - blocks until complete.
     * Temp images are cleaned up automatically via try-with-resources.
     *
     * @param filePath path to PDF or image
     * @return extracted and enriched content
     * @throws VduException if processing fails
     */
    public VduResult process(Path filePath) throws VduException {
        if (!hasVisionCapability()) {
            throw new VduException(
                "Vision capability not available — no vision projector (mmproj) configured. "
                    + "Set JUSTSEARCH_MMPROJ_MODEL or ensure the AI pack includes a vision projector.",
                null);
        }

        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        LOG.info("Starting VDU processing for: {}", filePath.getFileName());
        long startTime = System.currentTimeMillis();

        // Enter VDU mode: restarts server with vision-safe flags (-np 1, --cache-ram 0).
        // Must be exited in finally block to restore normal server configuration.
        try {
            lifecycleControl.enterVduMode();
        } catch (ModeTransitionException e) {
            throw new VduException("Failed to enter VDU mode: " + e.getMessage(), e);
        }

        // Use try-with-resources to ensure temp image cleanup; record total latency via catalog
        // histogram on the way out (Phase 3d: replaces legacy Telemetry.Timer.Sample).
        // Phase 3 critical-analysis fix B1: capture totalStartNanos AFTER PdfImageRenderer
        // construction so the legacy "no record on resource-construction failure" semantics are
        // preserved. Outer finally checks for null to skip the record on that path.
        Long totalStartNanos = null;
        try (PdfImageRenderer pdfRenderer = new PdfImageRenderer(tempFileManager)) {
            totalStartNanos = System.nanoTime();
            List<Path> pageImages;

            if (fileName.endsWith(".pdf")) {
                pageImages = pdfRenderer.render(filePath);
                LOG.debug("Rendered {} pages from PDF", pageImages.size());
            } else {
                // Direct image - no temp files needed from renderer
                pageImages = List.of(filePath);
            }

            // Pass 1: Extract text from each page (timed)
            long pass1StartNanos = System.nanoTime();
            String extractedText;
            try {
                StringBuilder allText = new StringBuilder();
                for (int i = 0; i < pageImages.size(); i++) {
                    LOG.debug("Processing page {}/{}", i + 1, pageImages.size());

                    byte[] imageBytes = imagePreparer.prepare(pageImages.get(i));
                    String pageText = aiService.visionCompletion(PASS1_PROMPT, imageBytes, PASS1_MAX_TOKENS)
                        .orTimeout(VDU_VISION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();

                    if (pageImages.size() > 1) {
                        allText.append("--- Page ").append(i + 1).append(" ---\n");
                    }
                    allText.append(pageText).append("\n\n");
                }
                extractedText = allText.toString().trim();
            } finally {
                catalog.pass1DurationMs.record(elapsedMs(pass1StartNanos), VduPassTags.of());
            }
            LOG.debug("Extracted {} characters from {} pages", extractedText.length(), pageImages.size());
            if (LOG.isTraceEnabled() && !extractedText.isEmpty()) {
                LOG.trace("VDU Pass 1 sample (first 500 chars): {}",
                    extractedText.substring(0, Math.min(500, extractedText.length())));
            }

            // Pass 2: Summarize and extract entities (timed)
            long pass2StartNanos = System.nanoTime();
            String enrichment;
            try {
                String pass2Prompt = String.format(PASS2_PROMPT_TEMPLATE,
                    truncateForPrompt(extractedText, MAX_CONTEXT_CHARS));
                enrichment = aiService.chatCompletion(
                    List.of(Map.of("role", "user", "content", pass2Prompt)),
                    PASS2_MAX_TOKENS,
                    SamplingParams.VDU
                )
                    .orTimeout(VDU_CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            } finally {
                catalog.pass2DurationMs.record(elapsedMs(pass2StartNanos), VduPassTags.of());
            }
            if (LOG.isTraceEnabled() && enrichment != null && !enrichment.isEmpty()) {
                LOG.trace("VDU Pass 2 enrichment (first 300 chars): {}",
                    enrichment.substring(0, Math.min(300, enrichment.length())));
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("VDU complete for {}: {} chars, {} pages, {}ms",
                filePath.getFileName(), extractedText.length(), pageImages.size(), elapsed);

            return new VduResult(extractedText, enrichment, pageImages.size());

        } catch (IOException e) {
            LOG.error("VDU I/O error for: {}", filePath, e);
            throw new VduException("Failed to read/render document: " + e.getMessage(), e);
        } catch (Exception e) {
            // Check for timeout (wrapped in CompletionException)
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException || e instanceof TimeoutException) {
                LOG.error("VDU timeout for: {} (vision={}s, chat={}s limits)",
                    filePath, VDU_VISION_TIMEOUT_SECONDS, VDU_CHAT_TIMEOUT_SECONDS);
                catalog.timeoutTotal.increment(VduTimeoutTags.of());
                throw new VduException("VDU timeout exceeded", e);
            }
            LOG.error("VDU processing failed for: {}", filePath, e);
            throw new VduException("VDU processing failed: " + e.getMessage(), e);
        } finally {
            // Always restore normal server configuration after VDU batch.
            try {
                lifecycleControl.exitVduMode();
            } catch (ModeTransitionException e) {
                LOG.error("Failed to exit VDU mode; server may remain in vision-safe config", e);
            }
            // Record total duration on both success and failure paths IF the
            // PdfImageRenderer was successfully constructed (legacy Timer.Sample was opened
            // inside the try-with-resources head; failure to acquire the renderer never started
            // the timer). Phase 3 critical-analysis fix B1.
            if (totalStartNanos != null) {
                catalog.totalDurationMs.record(elapsedMs(totalStartNanos), VduPassTags.of());
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Truncate text to fit within prompt context window. */
    private String truncateForPrompt(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [truncated]";
    }

    /** Result of VDU processing. */
    public record VduResult(String extractedText, String enrichment, int pageCount) {}

    /** VDU-specific exception for better error handling. */
    public static class VduException extends Exception {
        public VduException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
