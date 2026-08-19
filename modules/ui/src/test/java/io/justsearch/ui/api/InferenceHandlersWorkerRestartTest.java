/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.WorkerRecoveryAuthority;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/worker/restart} in the state it was WRITTEN for and used to fail in (tempdoc 825
 * §D5 decision 4): the worker never started, so there is no bootstrap bound here and no spawner to
 * restart. Pre-825 both arms answered 503 "Knowledge Server not configured" — the operator's escape
 * hatch was dead in exactly the state they would reach for it.
 *
 * <p>These cases pin the ROUTING, not the recovery: the authority is a stub, because the budget and
 * the vetoes are the monitor's law and are pinned by {@code BootRecoveryDecisionTest} /
 * {@code KnowledgeServerBootRecoveryTest}. What must not regress here is that the endpoint consults
 * the one authority instead of growing a second restart path.
 */
@DisplayName("POST /api/worker/restart routes through the boot-recovery authority")
final class InferenceHandlersWorkerRestartTest {

  private static InferenceHandlers handlersWithNoWorker() {
    return new InferenceHandlers(
        mock(io.justsearch.app.api.OnlineAiService.class),
        null, // no KnowledgeServerBootstrap — the 821 §O.4 state
        mock(io.justsearch.gpu.GpuCapabilitiesService.class),
        mock(io.justsearch.app.api.EnterprisePolicyService.class),
        mock(io.justsearch.app.services.settings.UiSettingsStore.class),
        null,
        null,
        null);
  }

  private static Context mockContext() {
    Context ctx = mock(Context.class);
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.endpointHandlerPath()).thenReturn("/api/worker/restart");
    return ctx;
  }

  /** Records what the endpoint asked for, so "it consulted the authority" is itself asserted. */
  private static final class StubAuthority implements WorkerRecoveryAuthority {
    private final Verdict verdict;
    private final AtomicInteger calls = new AtomicInteger();

    StubAuthority(Verdict verdict) {
      this.verdict = verdict;
    }

    @Override
    public Verdict requestRecoveryNow() {
      calls.incrementAndGet();
      return verdict;
    }
  }

  @Test
  @DisplayName("with no worker bound and no authority wired, the legacy 503 stands")
  void withoutAuthorityItStill503s() {
    Context ctx = mockContext();

    handlersWithNoWorker().handleRestartWorker(ctx);

    verify(ctx).status(503);
  }

  @Test
  @DisplayName("an accepted recovery answers 202 — scheduled, not 'restarted'")
  void acceptedRecoveryAnswers202() {
    InferenceHandlers handlers = handlersWithNoWorker();
    StubAuthority authority = new StubAuthority(WorkerRecoveryAuthority.Verdict.ACCEPTED);
    handlers.setWorkerRecovery(authority);
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    assertEquals(1, authority.calls.get(), "the endpoint must consult the one recovery authority");
    // 202, not 200: a worker boot takes tens of seconds, so the honest answer is "scheduled".
    verify(ctx).status(202);
  }

  @Test
  @DisplayName("a vetoed or exhausted recovery keeps 503 — the state will not change by itself")
  void declinedRecoveryKeeps503() {
    for (WorkerRecoveryAuthority.Verdict verdict :
        List.of(
            WorkerRecoveryAuthority.Verdict.VETOED_SUPERVISION,
            WorkerRecoveryAuthority.Verdict.VETOED_RESTART_EXHAUSTED,
            WorkerRecoveryAuthority.Verdict.EXHAUSTED)) {
      InferenceHandlers handlers = handlersWithNoWorker();
      handlers.setWorkerRecovery(new StubAuthority(verdict));
      Context ctx = mockContext();

      handlers.handleRestartWorker(ctx);

      verify(ctx).status(503);
    }
  }

  @Test
  @DisplayName("NOT_APPLICABLE falls through to the ordinary path rather than answering for it")
  void notApplicableFallsThrough() {
    InferenceHandlers handlers = handlersWithNoWorker();
    handlers.setWorkerRecovery(new StubAuthority(WorkerRecoveryAuthority.Verdict.NOT_APPLICABLE));
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    // With no bootstrap there is nothing to fall through TO, so the legacy 503 is correct here —
    // what matters is that the recovery authority did not claim a request it could not serve.
    verify(ctx).status(503);
  }
}
