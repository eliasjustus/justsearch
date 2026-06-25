import * as z from 'zod/v4';

export const CleanModeSchema = z.enum(['none', 'soft', 'hard']);
export const OutputModeSchema = z.enum(['full', 'compact']);
export const ReadyLevelSchema = z.enum(['ready_http', 'ready_worker']);

// ─── Ownership (271) ──────────────────────────────────────

const OwnerSchema = z
  .object({
    source: z.string(),
    agentSessionId: z.string().nullable(),
    confidence: z.string().optional(),
  })
  .passthrough();

const ResourceClaimsSchema = z
  .object({
    apiPort: z.number().int().optional(),
    uiPort: z.number().int().optional(),
    dataDir: z.string().optional(),
  })
  .passthrough();

const LeaseSchema = z
  .object({
    durationSec: z.number().int(),
    renewedAt: z.string(),
    expiresAt: z.string(),
    sequence: z.number().int(),
  })
  .passthrough();

// Tempdoc 542 §B Layer 2: op-lease entries surfaced via quick_health so agents can see what
// critical work is in flight before considering a takeover.
const OpLeaseSchema = z
  .object({
    opId: z.string(),
    opClass: z.string(),
    criticality: z.string(),
    startedAt: z.string(),
    expectedDurationSec: z.number().int().optional(),
    expiresAt: z.string(),
    heartbeatAt: z.string().nullable().optional(),
    originProcess: z.string(),
    holder: z.record(z.unknown()).optional(),
    metadata: z.record(z.unknown()).optional(),
  })
  .passthrough();

const OwnershipProjectionSchema = z
  .object({
    holder: z.object({ source: z.string(), agentSessionId: z.string().nullable() }).passthrough(),
    takeoverPolicy: z.string().nullable(),
    launcherFamily: z.string().nullable(),
    mode: z.string().nullable(),
    lease: LeaseSchema.optional(),
    leaseFresh: z.boolean().optional(),
    callerIsOwner: z.boolean().optional(),
    // Tempdoc 542 §B Layer 2 — active op-leases (filtered to non-expired).
    opLeases: z.array(OpLeaseSchema).optional(),
    // Tempdoc 606 — single ownership-verdict authority projection.
    verdict: z.string().optional(),
    grade: z.string().optional(),
    recommendedAction: z.string().optional(),
    rebuildFirst: z.boolean().optional(),
    provenance: z.record(z.string(), z.unknown()).optional(),
    backendStale: z.boolean().optional(),
    runningHeadStamp: z.string().optional(),
    displacedNotice: z.string().optional(),
  })
  .passthrough();

export const ToolErrorSchema = z
  .object({
    code: z.string().optional(),
    message: z.string(),
    errorClass: z.string().optional(),
    retryAction: z.string().optional(),
    retryAttempt: z.number().int().optional(),
    stack: z.string().optional(),
  })
  .passthrough();

export const StartInputSchema = z
  .object({
    apiPort: z.number().int().min(0).optional(),
    uiPort: z.number().int().positive().optional(),
    dataDir: z.string().min(1).optional(),
    clean: CleanModeSchema.optional(),
    waitLevel: ReadyLevelSchema.optional().describe('Readiness level to wait for after start (default: ready_worker)'),
    skipBuild: z.boolean().optional().describe('Skip Gradle assemble step — launch from existing dist (default: false)'),
    startTimeoutMs: z.number().int().positive().optional().describe('Timeout for dev-runner start subprocess (default: 600000)'),
    waitTimeoutMs: z.number().int().positive().optional().describe('Timeout for readiness polling after start (default: 60000)'),
    takeover: z.enum(['deny', 'warn', 'force']).optional()
      .describe('Takeover policy if another agent owns the backend (default: deny)'),
    hotReload: z.boolean().optional()
      .describe('Enable hot-reload: JDWP agent + DevReloadManager on Worker (default: false). Use with reload tool after code changes.'),
    distFrom: z.string().optional()
      .describe('Tempdoc 606 Piece 4: launch the stack from THIS worktree\'s built dist (must be a '
        + 'sibling worktree under .claude/worktrees, or the main repo). The shared lease stays under '
        + 'the main repo, so a worktree agent can run its own code on the one shared stack. Resolves a '
        + 'rebuildFirst/provenance-mismatch verdict.'),
    sessionId: z.string().optional(),
  })
  .strict();

export const StopInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    force: z.boolean().optional(),
    clean: CleanModeSchema.optional().describe('Clean data dir after stop (default: none)'),
    sessionId: z.string().optional(),
  })
  .strict();

// Tempdoc 606 3c: block until the shared dev stack becomes acquirable (owner releases /
// goes abandoned / a critical op clears), then return — replacing the conflict→ask-user→
// manual-retry round-trip with a single waited call. Polls the ONE ownership verdict.
export const AcquireWhenFreeInputSchema = z
  .object({
    timeoutSec: z.number().int().positive().max(1800).optional()
      .describe('Max seconds to wait for the stack to become acquirable (default: 120).'),
    pollMs: z.number().int().min(500).max(30_000).optional()
      .describe('Poll interval in ms (default: 2000).'),
    sessionId: z.string().optional(),
  })
  .strict();

export const AcquireWhenFreeOutputSchema = z
  .object({
    ok: z.boolean(),
    acquirable: z.boolean(),
    verdict: z.string().optional(),
    grade: z.string().optional(),
    recommendedTakeover: z.enum(['deny', 'warn', 'force']).optional(),
    recommendedAction: z.string().optional(),
    waitedMs: z.number().optional(),
    ownership: OwnershipProjectionSchema.optional(),
    error: ToolErrorSchema.optional(),
  })
  .passthrough();

const DevRunnerErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    stack: z.string().optional(),
  })
  .passthrough();

export const DevRunnerStartJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string(),
        apiPort: z.number().int().positive(),
        uiPort: z.number().int().positive(),
        apiBaseUrl: z.string(),
        uiUrl: z.string(),
        dataDir: z.string(),
        pids: z
          .object({
            runnerPid: z.number().int().positive().optional(),
            backendRootPid: z.number().int().positive().optional(),
            frontendRootPid: z.number().int().positive().optional(),
          })
          .passthrough()
          .optional(),
        readiness: z
          .object({
            ready_http: z.boolean().optional(),
          })
          .passthrough()
          .optional(),
        owner: OwnerSchema.optional(),
        resourceClaims: ResourceClaimsSchema.optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const DevRunnerStopJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().nullable(),
        killedPids: z.array(z.number()).optional(),
        portsClosed: z.boolean().optional(),
        stopReportPath: z.string().nullable().optional(),
        note: z.string().optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const DevRunnerCleanupJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().nullable(),
        portsClosed: z.boolean().optional(),
        note: z.string().optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const StatusInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const DevRunnerStatusJsonSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      alive: z
        .object({
          runner: z.boolean().optional(),
          backendRoot: z.boolean().optional(),
          frontendRoot: z.boolean().optional(),
        })
        .passthrough()
        .optional(),
      ports: z
        .object({
          api: z
            .object({
              port: z.number().int().optional(),
              listening: z.boolean().optional(),
            })
            .passthrough()
            .optional(),
          ui: z
            .object({
              port: z.number().int().optional(),
              listening: z.boolean().optional(),
            })
            .passthrough()
            .optional(),
        })
        .passthrough()
        .optional(),
      readiness: z
        .object({
          ready_http: z.boolean().optional(),
        })
        .passthrough()
        .optional(),
      owner: OwnerSchema.optional(),
      resourceClaims: ResourceClaimsSchema.optional(),
      ownership: OwnershipProjectionSchema.optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }).nullable(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const StatusOutputSchema = DevRunnerStatusJsonSchema;

export const CaptureIncludeSchema = z.enum(['debug', 'policy', 'inference', 'gpu', 'ui_ready', 'effective_config']);

export const CaptureEvidenceInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    scenario: z.string().min(1).max(80).regex(/^[A-Za-z0-9][A-Za-z0-9_-]*$/).optional(),
    outRoot: z.string().min(1).optional(),
    include: z.array(CaptureIncludeSchema).optional(),
    trace: z.boolean().optional(),
    timeoutMs: z.number().int().positive().optional(),
    /**
     * Repo-relative attachment paths. The server enforces an allowlist for the given runId.
     * This exists (instead of raw paths) so we can deterministically reject traversal attempts.
     */
    attachments: z.array(z.string().min(1)).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const CaptureEvidenceOutputSchema = z
  .object({
    ok: z.boolean(),
    runId: z.string().meta({ format: 'uuid' }),
    bundleDir: z.string().min(1),
    exitCode: z.number().int(),
    outRoot: z.string().min(1),
    attachments: z.array(z.string().min(1)),
    stderrTail: z.string().optional(),
  })
  .passthrough();

export const TailLogKindSchema = z.enum([
  'backend_stdout',
  'backend_stderr',
  'frontend_stdout',
  'frontend_stderr',
  'stop_report',
]);

export const TailLogInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    kind: TailLogKindSchema,
    maxBytes: z.number().int().positive().max(1_000_000).optional(),
    maxLines: z.number().int().positive().max(10_000).optional(),
    grepPattern: z.string().optional().describe('Regex pattern to filter log lines'),
    sessionId: z.string().optional(),
  })
  .strict();

export const TailLogOutputSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().meta({ format: 'uuid' }),
        kind: TailLogKindSchema,
        path: z.string().min(1),
        truncated: z.boolean(),
        bytesRead: z.number().int().min(0),
        text: z.string(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        runId: z.string().meta({ format: 'uuid' }),
        kind: TailLogKindSchema,
        error: ToolErrorSchema,
      })
      .passthrough(),
  ]);

export const FetchApiEndpointSchema = z.enum([
  'status',
  'health',
  'effective_config',
  'debug_state',
  'policy_effective',
  'inference_status',
  'gpu_capabilities',
  'ui_ready',
  'ai_runtime_status',
]);

export const FetchApiJsonInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    endpoint: FetchApiEndpointSchema,
    jsonPath: z.string().optional().describe('Dot-path to extract a subtree from the response (e.g., "llm.model_path")'),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const FetchApiJsonOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      endpoint: FetchApiEndpointSchema,
      url: z.string().min(1),
      statusCode: z.number().int().nullable(),
      json: z.any().optional(),
      textTail: z.string().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      endpoint: FetchApiEndpointSchema,
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      json: z.any().optional(),
      textTail: z.string().optional(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const SearchQueryInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    query: z.string().min(1),
    cursor: z.string().optional().describe('Pagination cursor from a previous search response'),
    limit: z.number().int().positive().max(200).optional(),
    mode: z.string().optional(),
    querySyntax: z.enum(['SIMPLE', 'LUCENE']).optional(),
    verbose: z.boolean().optional(),
    summaryOnly: z.boolean().optional().describe('Return only totalHits and tookMs, omitting result details'),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().max(60_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const SearchQueryOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      query: z.string(),
      url: z.string().min(1),
      statusCode: z.number().int(),
      totalHits: z.number(),
      tookMs: z.number(),
      results: z.array(z.any()),
      nextCursor: z.string().optional(),
      facets: z.any().optional(),
      correctionApplied: z.boolean().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      query: z.string(),
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const IngestInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    paths: z.array(z.string().min(1)).min(1),
    timeoutMs: z.number().int().positive().max(120_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const IngestOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      url: z.string().min(1),
      statusCode: z.number().int(),
      accepted: z.number().int(),
      error: z.string().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Generic API Call ---

export const ApiCallMethodSchema = z.enum(['GET', 'POST', 'DELETE']);

export const ApiCallInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    method: ApiCallMethodSchema.default('GET'),
    path: z.string().min(1),
    body: z.any().optional(),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().max(60_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const ValidateEvidenceInputSchema = z
  .object({
    bundleDir: z.string().min(1),
    timeoutMs: z.number().int().positive().optional(),
    strictReasons: z.boolean().optional(),
    allowReasons: z.array(z.string().min(1)).optional(),
    enforceDeterminism: z.boolean().optional(),
    sessionId: z.string().optional(),
  })
  .strict();

export const ValidateEvidenceSubResultSchema = z
  .object({
    ok: z.boolean(),
    exitCode: z.number().int(),
    stdoutTail: z.string(),
    stderrTail: z.string(),
    errors: z.array(z.string()).optional(),
  })
  .passthrough();

export const ValidateEvidenceOutputSchema = z
  .object({
    ok: z.boolean(),
    bundleDir: z.string().min(1),
    evidenceBundle: ValidateEvidenceSubResultSchema,
    determinismBudget: ValidateEvidenceSubResultSchema,
    errors: z.array(z.string()).optional(),
    warnings: z.array(z.string()).optional(),
  })
  .passthrough();

// --- Agent Chat ---

export const AgentChatInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    prompt: z.string().min(1),
    maxIterations: z.number().int().positive().max(20).default(10),
    autoApprove: z.boolean().default(true),
    timeoutMs: z.number().int().positive().max(600_000).default(120_000)
      .describe('Socket inactivity timeout in ms — resets on each SSE chunk'),
    totalTimeoutMs: z.number().int().positive().max(600_000).optional()
      .describe('Total elapsed time limit in ms (default: none — only inactivity timeout applies)'),
    maxBytes: z.number().int().positive().max(5_000_000).default(2_000_000),
    verbose: z.boolean().default(false),
    sessionId: z.string().optional(),
  })
  .strict();

const AgentToolCallSchema = z
  .object({
    callId: z.string(),
    toolName: z.string(),
    arguments: z.string(),
    risk: z.enum(['low', 'medium', 'high']).optional(),
    approved: z.boolean(),
    success: z.boolean().nullable(),
    output: z.string().nullable(),
    iteration: z.number().optional(),
    trace: z
      .object({
        runId: z.string().optional(),
        stepId: z.string().optional(),
        spanId: z.string().optional(),
        parentSpanId: z.string().optional(),
        agentId: z.string().optional(),
        toolCallId: z.string().optional(),
        iteration: z.number().optional(),
      })
      .passthrough()
      .optional(),
  })
  .passthrough();

/** Per-iteration detail (only included in verbose mode). */
const AgentIterationSchema = z
  .object({
    iteration: z.number(),
    phase: z.string(),
    textBefore: z.string(),
    toolCallIds: z.array(z.string()),
    trace: z
      .object({
        runId: z.string().optional(),
        stepId: z.string().optional(),
        spanId: z.string().optional(),
        parentSpanId: z.string().optional(),
        agentId: z.string().optional(),
        toolCallId: z.string().optional(),
        iteration: z.number().optional(),
      })
      .passthrough()
      .optional(),
  })
  .passthrough();

const AgentBudgetUpdateSchema = z
  .object({
    phase: z.string(),
    tokensConsumed: z.number(),
    tokensRemaining: z.number(),
    trace: z
      .object({
        runId: z.string().optional(),
        stepId: z.string().optional(),
        spanId: z.string().optional(),
        parentSpanId: z.string().optional(),
        agentId: z.string().optional(),
        toolCallId: z.string().optional(),
        iteration: z.number().optional(),
      })
      .passthrough()
      .optional(),
  })
  .passthrough();

export const AgentChatOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      prompt: z.string(),
      sessionId: z.string().nullable(),
      toolCalls: z.array(AgentToolCallSchema),
      finalResponse: z.string(),
      iterationsUsed: z.number().nullable(),
      toolCallsExecuted: z.number().nullable(),
      totalTokensUsed: z.number().nullable(),
      durationMs: z.number(),
      iterations: z.array(AgentIterationSchema).optional(),
      budgetUpdates: z.array(AgentBudgetUpdateSchema).optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      prompt: z.string(),
      sessionId: z.string().nullable(),
      toolCalls: z.array(AgentToolCallSchema).optional(),
      finalResponse: z.string().optional(),
      totalTokensUsed: z.number().nullable().optional(),
      durationMs: z.number().optional(),
      error: ToolErrorSchema,
      iterations: z.array(AgentIterationSchema).optional(),
      budgetUpdates: z.array(AgentBudgetUpdateSchema).optional(),
    })
    .passthrough(),
]);

// ─── AI Runtime Activate ───────────────────────────────────

export const AiActivateInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    variantId: z.string().min(1).default('cuda12'),
    timeoutMs: z.number().int().positive().max(120_000).default(60_000),
    pollIntervalMs: z.number().int().positive().max(10_000).default(2_000),
    sessionId: z.string().optional(),
  })
  .strict();

export const AiActivateOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      variantId: z.string(),
      activationState: z.string(),
      phase: z.string(),
      message: z.string(),
      durationMs: z.number(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      variantId: z.string(),
      activationState: z.string().optional(),
      phase: z.string().optional(),
      message: z.string().optional(),
      durationMs: z.number().optional(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// ─── Quick Health ──────────────────────────────────────────

export const QuickHealthInputSchema = z
  .object({
    probe: z.boolean().optional().describe('HTTP-probe running backend (default: true)'),
    sessionId: z.string().optional(),
  })
  .strict();

export const QuickHealthOutputSchema = z
  .object({
    running: z.boolean(),
    runId: z.string().meta({ format: 'uuid' }).nullable(),
    apiPort: z.number().int().positive().nullable(),
    uiPort: z.number().int().positive().nullable(),
    httpReady: z.boolean().nullable(),
    workerReady: z.boolean().nullable(),
    aiActive: z.boolean().nullable(),
    inferenceOrphan: z.boolean().optional(),
    ownership: OwnershipProjectionSchema.optional(),
  })
  .passthrough();

// ─── Reload (hot-reload) ──────────────────────────────────

export const ReloadInputSchema = z.object({
  module: z.string().optional()
    .describe('Gradle module to compile (default: worker-services)'),
  debugPort: z.number().int().positive().optional()
    .describe('JDWP debug port for HotSwapPush (default: 5005)'),
  skipCompile: z.boolean().optional()
    .describe('Skip Gradle compile, only push + signal (default: false)'),
  sessionId: z.string().optional(),
}).strict();

// ─── Preflight ─────────────────────────────────────────────

export const PreflightInputSchema = z.object({ sessionId: z.string().optional() }).strict();

export const PreflightOutputSchema = z
  .object({
    ready: z.boolean(),
    checks: z.object({
      workerDist: z.boolean(),
      headDist: z.boolean(),
      noStaleRun: z.boolean(),
      modelsDir: z.boolean(),
      noInferenceOrphan: z.boolean(),
      // Tempdoc 618 §3: is the llama-server runtime resolvable for `ai_activate`?
      llamaVariantResolvable: z.boolean(),
    }),
    details: z.record(z.string(), z.string()).optional(),
  })
  .passthrough();
