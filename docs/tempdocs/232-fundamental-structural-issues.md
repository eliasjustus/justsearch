---
title: "232: Fundamental Structural Issues & Criticisms"
type: tempdoc
status: active
created: 2026-02-22
updated: 2026-02-22
---

> NOTE: Noncanonical doc (analysis + investigation). Serves as a consolidated critique of the remaining structural and mathematical gaps within the evaluation systems originally defined in tempdocs 229, 230, and 216.

# 232: Fundamental Structural Issues & Criticisms

## Purpose

To document, analyze, and propose investigations into the most fundamental remaining structural and mathematical issues within JustSearch's current high-level code architectures, specifically targeting the limitations exposed by the unified relevance pipeline (229), agent evaluation battery (230), and consolidated test harness (216). This document is explicitly not focused on implementation, but on architectural analysis and systemic critique.

## Scope

This document evaluates the system at a meta-level: it investigates the design of the evaluation systems themselves.
*   **In Scope**: Objective function validity, baseline promotion mechanics, offline-to-live translation gaps, orchestration dependency risks, and agent trajectory rigidities.
*   **Out of Scope**: Direct source code modifications, specific algorithm tuning, and UI/UX changes.

---

## 1. Objective-Function Subjectivity & Ground-Truth Fragility (Critique of 229) — DEFERRED

> **Deferred:** Requires real user engagement signals (click rank, dwell time) that do not exist pre-launch. Infrastructure can be scaffolded, but the feedback loop cannot close without real users.

### The Issue
Tempdoc 229 formalizes a "Utility" calculation (`0.50 * nDCG@10 + 0.30 * MRR@10 + 0.20 * Precision@10`) to govern the promotion of new search relevance strategies. While mathematically sound as an IR metric combination, the formula is inherently subjective regarding user satisfaction.

The core vulnerability lies in the "ground truth" used to validate this utility function. The system relies heavily on static, synthetic, or human-annotated proxy datasets (like BEIR). Because local user corpora (the core value proposition of JustSearch) are highly diverse, often messy, and inherently private, centralized ground-truth annotation is impossible.

### The Structural Risk
By mathematically gating CI promotion on tests executed against static, proxy datasets, the system risks structurally overfitting to external benchmarks while degrading actual local user experience. We optimize for BEIR, not for the user's specific fragmented `.txt` and `.md` ecosystem.

### Investigation Path
*   Investigate mechanisms for **local, privacy-preserving pseudo-relevance feedback** (e.g., click models, dwell time tracking, successful agent extraction rates) that can run entirely on the user's machine to dynamically adjust the Utility weightings without phoning home.

---

## 2. The Fallacy of the Single "Best" Baseline (Critique of 229 & 216)

### The Issue
The foundational architectural assumption of the consolidation effort (216) and the static relevance policy (229) is that a single "best" baseline exists and must be protected by CI gates. However, Tempdoc 229 correctly identifies that retrieval features (especially dense vector vs. sparse keyword vs. hybrid RRF) interact *non-additively*.

### The Structural Risk
The current system handles non-additive interactions via brute-force feature ablation testing during offline validation. It lacks a runtime foundational Machine Learning framework to continuously learn the *interaction* weights between these features dynamically. The "adaptive policy" is still rules-based rather than statistically learned.

### Investigation Path
*   Research the feasibility of embedding a lightweight **Contextual Bandit architecture** or a highly localized **LambdaMART model** into the `KnowledgeServer` (Worker process). This would allow the system to continuously learn the relative value of BM25 vs. Vector vs. Merge operations at runtime, shifting away from a statically promoted, globally dominant "baseline."

---

## 3. Agent Evaluation Trajectory Variance vs. Terminal Truth (Critique of 230)

### The Issue
Tempdoc 230 establishes rigorous deterministic argument checking for the LLM agent. While this vastly improves upon subjective LLM grading, it introduces severe evaluation rigidity. An LLM ReAct loop is non-deterministic and highly sensitive to initial prompts and exact context window contents.

### The Structural Risk
By gating success on metrics like `firstToolCallOracle` (did the agent call the *exact* right tool with the *exact* right argument on step one?), the framework actively penalizes correct, self-healing, exploratory trajectories. The system architecture struggles to mathematically distinguish between an agent that is "flailing" (failing) and an agent that is executing a valid, multi-step search strategy to arrive at the same terminal truth.

### Investigation Path
*   Investigate moving the agent evaluation battery away from strict path-matching and toward **State-Space Attainment**. The evaluation should define the final required knowledge state or side-effect and measure the efficiency (token cost, time, DB hits) of reaching that state, rather than grading the specific sequential permutation of the trajectory.

---

## 4. False Consolidation of Orchestration vs. Representation (Critique of 216) — RESOLVED

> **Resolved by Tempdoc 233:** The custom TypeScript DAG engine (`dag-scheduler.mjs`) with 9 declarative runners, dependency-ordered execution, atomic writes, and Windows Job Objects via koffi directly addresses this criticism. See [233-governance-infrastructure-modernization.md](./233-governance-infrastructure-modernization.md).

### The Issue
Tempdoc 216 achieves representation consolidation by forcing all disparate evaluation pipelines to emit output matching the `bench-suite.v2` schema. However, it admits that the orchestration is still deeply fragmented across multiple PowerShell scripts, Java integration tests, and Node.js runners.

### The Structural Risk
The dependency graph is inverted. The `build-benchmark-scorecard.mjs` script acts as a passive God Object aggregating output files, rather than an active centralized orchestrator driving execution. If an upstream script (like the BEIR runner) diverges in how it provisions its environment (e.g., silently disabling the ANN service as noted in finding F-7), the downstream consolidated scorecard blindly consumes and gates on invalid evidence.

### Investigation Path
*   Explore designing a true **Unified Execution Protocol**. Instead of standardizing the output JSON, standardize the input specification and environment generation. The orchestrator must be able to predictably mock/start the 3-process architecture (`Head`, `Body`, `Brain`) into a verified initial state before *any* lane script runs.

---

## 5. The Offline-to-Live Transfer Confidence Gap (Critique of 229, 230, 216) — DEFERRED

> **Deferred:** Shadow Evaluation requires real user queries and engagement to compare candidate vs. baseline pipelines. Cannot be validated pre-launch.

### The Issue
Across all three evaluation-focused tempdocs, a massive architectural gap exists regarding "Offline-to-Live Transfer." Extensive offline evaluations execute against static datasets. When a modified retrieval pipeline passes all theoretical CI gates, it is promoted and released.

### The Structural Risk
There is fundamentally no automated telemetry mechanism designed to mathematically prove that an offline benchmark win successfully translated into a live, local user-perceived performance gain. The entire evaluation ontology stops at the border of the developer's CI runner.

### Investigation Path
*   Investigate the architectural requirements for implementing strictly local, client-side **Shadow Evaluation (Dark Launching)**. For example, when a user executes a query, the system executes the current baseline *and* a silent candidate pipeline in the background. The system then locally compares the outputs (e.g., did the candidate identify the document the user ultimately clicked on faster/higher?) without exposing the user to the experimental architecture until statistical significance is reached locally.

---

## Proceeding Theoretical Work: Execution Plan

> **Constraint-First Research Protocol (Lessons from Phase 2)**
> To avoid premature architectural proposals that violate JustSearch's core invariants, all subsequent investigations in this document must strictly adhere to the following protocol:
> 1.  **Define Inflexible Constraints:** Explicitly state the boundaries (e.g., zero telemetry, no external APIs, <100ms latency, local-only execution) *before* proposing any solutions.
> 2.  **Broad Literature Search:** Source solutions not just from modern "AI hype" blogs (which assume cloud architectures), but from formal Information Retrieval (IR) literature, static analysis papers, and established open-source projects operating under similar constraints.
> 3.  **Constraint-Filtering:** Ruthlessly discard any "industry standard" solution that fails the invariants defined in Step 1, regardless of its popularity.
> 4.  **Mathematical / Architectural Feasibility:** Only propose solutions that can be realistically computed on a local consumer CPU/GPU within the existing process architecture (Head, Body, Brain).

---

To move these criticisms from theory into actionable architectural designs, the theoretical work of this tempdoc will proceed through the following phased investigations.

### Phase 1: Simulated Pseudo-Relevance Feedback (Zero-User Constraint)
*Constraint Acknowledgment: The application currently has no active testers or users. Therefore, live telemetry cannot drive early validation.*
1.  **Define Telemetry Primitives:** Identify signals the Head (UI) *will* capture (e.g., dwell time, click rank) to form future `Personal Utility Scores`.
2.  **Design the Synthetic User Harness:** Draft an architecture for an automated "Synthetic User" (using the Brain process's LLM) that reads queries and deterministic scenarios, "clicks" on the resulting UI elements, and generates pseudo-random/heuristically driven engagement metrics to populate the local sidecar database.
3.  **Shadow Evaluation Architecture:** Draft the flow for how the Worker can execute a candidate search pipeline in the background and compare its theoretical `Synthetic Utility Score` against the baseline.

### Phase 2: Pre-Retrieval Query Performance Prediction (QPP)
*Strategic Pivot:* Based on latency and zero-user constraints, we are abandoning Generative/LLM routing and Contextual Bandits in favor of mathematical QPP formulas evaluated directly against the local Lucene index. (See Appendices A and B for detailed research).
1.  **Define Pre-Retrieval QPP Metrics:** Research and select the most viable mathematical predictors of query ambiguity that can be calculated *before* a search runs (e.g., Maximum Inverse Document Frequency (`Max-IDF`), Query Scope, or Simplified Clarity Score).
2.  **Design the QPP Router:** Architect a lightweight routing layer within the Java `KnowledgeServer`. When a query arrives, it instantly queries the local Lucene statistics (which are held in memory). 
3.  **Threshold Calibration:** Establish the offline testing harness to find the optimal QPP thresholds. 

### Phase 3: State-Space Agent Evaluation
*Strategic Pivot:* Based on industry benchmarks (WebArena, SWE-bench) operating under strict deterministic constraints, we are moving away from grading the agent's *trajectory* (which tools it called) and moving entirely to *Backend State Verification* (what the final environment looks like). (See Appendix C for detailed industry analysis).
1.  **Deconstruct Scenario Signatures:** Redesign the current deterministic scenarios (`agent-live-battery-scenarios.v1.json`) to drop `requiredToolSuccess` and `firstToolCallOracle`. Replace them entirely with rigorous `Terminal State Assertions`.
2.  **Implement Mock-Backend Verification:** Design the evaluation runner to inject mock data into a temporary SQLite/Lucene instance. The agent's task is evaluated *solely* by querying that local DB after the agent finishes, verifying if the data was correctly mutated or if the exact correct string was returned in the final `answer_user` JSON payload.
3.  **Efficiency Cost Modeling (Trajectory Diagnostics):** While the pass/fail grade is 100% based on the final state, implement a diagnostic "Progress Rate" (similar to AgentBoard) that tracks token burn, redundant DB hits, and loop states to diagnose *why* an agent failed to reach the terminal state, without penalizing self-healing.

### Phase 4: Unified Execution Orchestration

Following the resolution of the internal uncertainties (Head/Body tight coupling, MMF suicide pact, Brain embedded DLL), we conducted external research into optimizing headless integration testing for tightly-coupled multi-process Windows applications.

**Constraint Analysis against Industry Standard Tools:**
The research examined several standard DevOps tools for multi-process testing and found them unsuitable due to JustSearch's local-first Windows constraints:
*   **Docker / Testcontainers:** While standard for Java/Node.js testing, moving JustSearch into a Linux container fundamentally breaks the "Local-First Desktop Integration" constraint (it tests the wrong OS API surface, particularly around file watching, paths, and Win32 MMFs). Windows Containers are too heavy and slow for rapid CI.
*   **Tox / PM2:** Tox is Python-centric, and PM2 is Node-centric. While they handle process spawning, they are designed for long-running deployments, not the high-churn start/stop deterministic cycles required by an evaluation framework.
*   **GitHub Actions Services:** Relying on CI runner features (like `jobs.<job_id>.services`) splits the orchestration logic away from the test code, making it impossible to run the evaluation suite locally on a developer's machine with the exact same determinism as CI.

**The Proposed Architecture: Embedded C# Test Orchestrator with Job Objects**
To achieve a deterministic, reproducible, and truly headless testing environment on Windows without heavy containerization, the Orchestrator should be implemented as a **custom NUnit integration test suite in C#**, acting as a superior process manager.

*   **Technology Stack:** NUnit (or xUnit) running on .NET, driving `System.Diagnostics.Process`.
*   **Process Tree Safety (The Killer Feature):** The orchestrator must utilize **Windows Job Objects** via P/Invoke (e.g., `CreateJobObject`, `SetInformationJobObject` with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`). When the NUnit test runner spawns the JustSearch Head (`HeadlessApp.java`), it assigns it to a Job Object.
    *   *Why this matters:* If the NUnit test crashes, the CI pipeline cancels the job, or the agent makes a fatal syntax error, the OS kernel automatically massacres the Head process, the Body process (via the MMF suicide pact), and the embedded Brain DLL. Zero orphaned zombie processes lock up the gRPC port (`127.0.0.1`) for the next test.
*   **Database Isolation Control:** Because the Java `KnowledgeServer` locks onto its `-Djustsearch.data.dir` at boot, the C# Orchestrator uses `[SetUp]` methods to create a fresh, isolated `Path.GetTempPath()` directory for every single test case. It then passes this directly into the HeadlessApp's spawning arguments, enforcing strict test isolation.
*   **Evaluation Hooking:** The LLM Agents (evaluating JustSearch) will be given tools that interact *through* the C# NUnit orchestrator, allowing the evaluation to execute setup conditions, trigger the agent, and assert on the final database state.

---

## Appendices: Specialized Research Deep Dives

### Appendix A: Research on Dynamic Query Routing Industry Solutions

As part of the investigation into Phase 2, a broad scan of current IR and RAG literature was conducted to determine how the industry currently solves the problem of routing queries between different algorithms (BM25 vs. Vector). The industry has largely moved away from static "always-on" hybrid pipelines toward **Dynamic Query Routing**.

#### Evaluated Approaches against JustSearch Constraints
JustSearch has strict local-first constraints: strictly local compute, < 100ms routing latency budget, zero initial user click telemetry, and entirely unknown personal corpora.

1.  **Heuristic-Based Routing (Logical)**
    *   *Mechanism:* Hardcoded regex or string-length rules (e.g., LlamaIndex QueryRouter basics).
    *   *Verdict:* **Keep as Guardrail.** Fast but too brittle for primary routing.
2.  **LLM-Based Intent Classification (Semantic)**
    *   *Mechanism:* Passing the query to an LLM to categorize intent (e.g., LangChain RouterChain).
    *   *Verdict:* **Reject.** Disastrous latency profile for a desktop app (200ms+ overhead).
3.  **High-Speed Classifier Models (Fast Semantic)**
    *   *Mechanism:* Embedding the query and running through a tiny SVM or Random Forest (e.g., Semantic Router).
    *   *Verdict:* **Consider (Strongly).** Extremely fast, but requires massive training datasets of "good" BM25 vs Vector queries to train the classifier before launch.
4.  **Late-Fusion / Dynamic Weighting**
    *   *Mechanism:* Running both BM25 and Vector and dynamically weighting the blend via Learning-to-Rank algorithms.
    *   *Verdict:* **Reject.** Too computationally wasteful (running both always) and impossible to train effectively pre-launch due to the Zero-User constraint.
5.  **Query Performance Prediction (QPP)**
    *   *Mechanism:* Pure mathematical analysis of the query against the *actual local index* to estimate algorithm effectiveness.
    *   *Verdict:* **Target Architecture.** Fast, requires zero generative AI, adapts natively to the user's specific corpus on Day 1 without telemetry.

---

### Appendix B: Deep Dive into Query Performance Prediction (QPP)

#### What is QPP?
Query Performance Prediction is a field of Information Retrieval (IR) dedicated to estimating *how well* a search engine will perform on a specific query, *without* needing human relevance judgments or knowing the "true" best documents.

Instead of asking "Is this document relevant?", QPP asks **"Is this query inherently difficult or ambiguous for this specific database?"**

#### The Core Concept: Ambiguity vs. Specificity
QPP algorithms generally try to measure the "clarity" or "specificity" of a query relative to the corpus it is searching. 
*   If a query is highly specific (e.g., "error code 0x80070005"), a keyword search (BM25) will handle it perfectly. The QPP score will be very high.
*   If a query is broad or conceptual (e.g., "how do I fix the database connection issue"), a keyword search will likely return chaotic, low-quality results. The QPP score will be low, signaling that the system should route the query to a Semantic/Vector Search engine instead.

#### The Two Types of QPP
There are two main categories of QPP, based on *when* the prediction happens:

##### 1. Pre-Retrieval QPP (The Target for JustSearch)
This happens *before* the main search executes. It relies solely on the linguistics of the query and the static statistics of the local index (like Lucene).
*   **How it works:** When the query arrives, the router instantly looks at the global statistics of the query terms in the database. 
*   **Example Metric: Maximum Inverse Document Frequency (Max-IDF).** 
    *   If a user searches for "react hook useEffect", the router checks how rare these words are in the user's hard drive. If `useEffect` only appears in 5 out of 10,000 files, its IDF is massive. The Max-IDF of the query is high.
    *   *Routing Decision:* High Max-IDF means the query contains highly specific, rare keywords. Route to **BM25**, because exact matching will easily find those 5 files.
    *   If a user searches for "meeting notes about the project", and all those words appear in 8,000 out of 10,000 files, the Max-IDF is tiny.
    *   *Routing Decision:* Low Max-IDF means the keywords are too common to be useful filters. Route to **Vector Search** to understand the semantic intent instead of relying on token frequency.

##### 2. Post-Retrieval QPP
This happens *after* a fast, shallow search executes.
*   **How it works:** The system runs a very fast BM25 search and only looks at the mathematical distribution of the resulting scores (not the documents themselves).
*   **Example Metric: Score Variance.**
    *   If the top document has a BM25 score of 85, and the 10th document has a score of 12, there is a massive drop-off (high variance). 
    *   *Routing Decision:* High variance means BM25 confidently found a highly relevant "winner." Return the BM25 results immediately.
    *   If the top document has a score of 15, and the 100th document has a score of 14.8, the scores are flat (low variance). BM25 is essentially guessing; the query didn't "pop" any specific documents out.
    *   *Routing Decision:* Low variance means BM25 failed to find a clear signal. Throw away the BM25 results and execute a **Vector Search** instead.

#### Why QPP is Perfect for JustSearch's Local-First Architecture
Because JustSearch indexes wildly different, highly personal files (code, PDFs, chat logs) directly on a user's machine, we cannot pre-train a universal model that knows what a "good" query looks like. 

By using **Pre-Retrieval QPP metrics like Max-IDF**, the router's intelligence is mathematically derived from the user's *actual* file statistics. It automatically adapts to their specific corpus the moment it is indexed, with absolutely zero latency overhead (since IDF statistics are held in RAM by Lucene anyway).

---

### Appendix C: Deep Dive into State-Space Agent Evaluation

As part of the Phase 3 investigation, a broad literature review of modern LLM Agent Evaluation frameworks (e.g., WebArena, SWE-bench, AgentBoard) was conducted. The goal was to solve JustSearch's "Trajectory Rigidity" problem—where agents are unfairly penalized for taking valid but non-standard paths.

#### Industry Shift: From Process to Outcome
The industry has recognized that grading an LLM agent based on its *process* (the exact sequence of tools it calls) is inherently brittle. Because LLMs are non-deterministic reasoning engines, they will invent novel, valid paths to solve problems (or self-heal from mistakes). Gating exactly *how* they solve a problem stifles their utility.

The gold-standard benchmarks have shifted to **Outcome-Based Evaluation via Backend State Verification**.

#### Evaluated Approaches against JustSearch Constraints
JustSearch has strict local-first constraints: evaluations must be 100% deterministic (no "LLM-as-a-judge"), must run namelessly in CI without live internet, and must be Path Agnostic.

1.  **"LLM-as-a-Judge" Trajectory Grading**
    *   *Mechanism:* Sending the entire agent transcript to a massive model (like GPT-4) and asking, "Did the agent do a good job?"
    *   *Verdict:* **Reject.** Violates determinism and local-first constraints. A local 8B model cannot reliably judge a complex transcript, and sending data to OpenAI violates privacy.

2.  **Fine-Grained Trajectory Matching (Old JustSearch Method)**
    *   *Mechanism:* The test framework asserts that `step 1 = search`, `step 2 = read_file`.
    *   *Verdict:* **Reject.** This is why we are here. It penalizes an agent if it decides to run `read_file` twice to double-check its work.

3.  **Backend State Verification (The WebArena/SWE-bench Method)**
    *   *Mechanism:* The evaluation framework completely ignores *how* the agent works. Instead, it resets the environment (e.g., spins up a fresh database) before the test. After the agent finishes, the framework runs SQL queries or unit tests against the database. If the database state matches the expected goal, the agent passes.
    *   *Verdict:* **Target Architecture.** This is 100% deterministic, stateless, and perfectly path-agnostic.

#### How State Verification Works in Practice
1.  **WebArena:** If the task is "Cancel my flight," the evaluation doesn't check if the agent clicked the "Cancel" button. It makes a REST API call to the mock airline backend to verify that the ticket status in the database is actually marked `CANCELLED`.
2.  **SWE-bench:** If the task is "Fix this bug," the evaluation doesn't read the agent's code. It simply applies the agent's patch to the repository and runs the existing unit tests. If the tests pass, the agent succeeds.

#### Application to JustSearch (The Phase 3 Plan)
For JustSearch, this means deleting assertions like `firstToolCallOracle` from the agent battery scenarios.

Instead, a scenario will look like this:
*   **Setup:** The test runner pushes a mocked `invoice_042.pdf` file into a local test Lucene index.
*   **Task:** Ask the agent for the total amount on the invoice.
*   **Verification:** The test runner inspects the agent's final `answer_user` JSON payload. It runs a deterministic string/regex match against the payload to ensure the exact number `$4,250.00` is present.
*   **Diagnostics:** While the pass/fail is 100% based on that final state, we track "Trajectory Diagnostics" (like the AgentBoard framework computes progress rates) to measure efficiency—e.g., did the agent burn 30,000 tokens reading the wrong files before finding the right one? This provides debug data without altering the pass/fail grade.

