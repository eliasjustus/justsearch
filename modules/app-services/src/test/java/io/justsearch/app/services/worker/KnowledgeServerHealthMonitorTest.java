package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import org.junit.jupiter.api.Test;

final class KnowledgeServerHealthMonitorTest {

  @Test
  void tickTriggersInitializationOnErrorToReadyTransition() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, "error");
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth())
        .thenAnswer(
            inv -> {
              cap.transition(CapabilityHealth.READY, null);
              return true;
            });

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap).checkHealth();
    verify(bootstrap, times(1)).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickTriggersInitializationOnPendingToReadyTransition() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth())
        .thenAnswer(
            inv -> {
              cap.transition(CapabilityHealth.READY, null);
              return true;
            });

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, times(1)).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickDoesNotTriggerInitializationWhenAlreadyReady() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(true);

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickDoesNotTriggerInitializationWhenStillDegraded() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.DEGRADED, "error");
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(false);

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.tick();

    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  @Test
  void tickSwallowsExceptionsSoExecutorSurvives() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    when(bootstrap.workerCapability()).thenReturn(cap);
    doThrow(new RuntimeException("transient gRPC failure")).when(bootstrap).checkHealth();

    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);

    assertDoesNotThrow(monitor::tick);
    verify(bootstrap).checkHealth();
    verify(bootstrap, never()).completeReadyInitializationFromMonitor();
  }

  // ---- Tempdoc 630: resume detection + eager re-validation -------------------------------------

  @Test
  void firstTickSeedsClockAndDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // first tick: no prior wall stamp → never a resume

    verify(bootstrap, never()).client();
    verify(client, never()).reconnect();
    verify(client, never()).reindexPersistedRoots();
  }

  @Test
  void normalCadenceTickDoesNotReValidate() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 10_500L; // a normal ~10s tick (with jitter), under the 30s threshold
    monitor.tick();

    verify(bootstrap, never()).client();
    verify(client, never()).reconnect();
    verify(client, never()).reindexPersistedRoots();
  }

  @Test
  void largeGapTriggersEagerReconnectAndReconcile() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    when(bootstrap.checkHealth()).thenReturn(true);
    when(bootstrap.client()).thenReturn(client);

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L; // a 1-hour gap → suspend/resume
    monitor.tick();

    verify(client, times(1)).reconnect();
    verify(client, times(1)).reindexPersistedRoots();
  }

  @Test
  void resumeReValidationSurvivesUnavailableClient() {
    // A resume while the worker client is not yet available (client() throws) must not abort the
    // tick or flip the capability to DEGRADED.
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    WorkerCapability cap = new WorkerCapability();
    cap.transition(CapabilityHealth.READY, null);
    when(bootstrap.workerCapability()).thenReturn(cap);
    when(bootstrap.checkHealth()).thenReturn(true);
    when(bootstrap.client()).thenThrow(new IllegalStateException("Knowledge Server not started"));

    long[] clock = {1_000_000L};
    KnowledgeServerHealthMonitor monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000L, () -> clock[0]);
    monitor.tick(); // seed
    clock[0] += 3_600_000L;
    assertDoesNotThrow(monitor::tick);
    // checkHealth still ran on both ticks; the resume path did not knock the capability to DEGRADED.
    verify(bootstrap, times(2)).checkHealth();
  }

  @Test
  void constructorRejectsNullBootstrap() {
    assertThrows(IllegalArgumentException.class, () -> new KnowledgeServerHealthMonitor(null));
  }

  @Test
  void closeIsIdempotent() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerHealthMonitor monitor = new KnowledgeServerHealthMonitor(bootstrap);
    monitor.close();
    assertDoesNotThrow(monitor::close);
  }

  @Test
  void nonPositivePollIntervalFallsBackToDefault() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(bootstrap, 0L).close());
    assertDoesNotThrow(() -> new KnowledgeServerHealthMonitor(bootstrap, -1L).close());
  }
}
